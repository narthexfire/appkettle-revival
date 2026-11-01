"""Encoder and decoder for the AppKettle local protocol (TCP port 6002).

Pure functions, standard library only, no network access. Used by kettle.py and
set_wifi_3e.py, and by the tests. The protocol is described in docs/protocol.md.

Safety: build_command() refuses every command code that is not in SAFE_COMMANDS.
In particular 0x47 (starts a firmware update) and 0x35 (writes the serial number)
can never be built by this module.
"""
import json
import re

PORT = 6002
DISCOVERY_PORT = 15103
KEEP_ALIVE = b"##000bKeepConnect&&"

# Command codes this module will build (hex strings, upper case).
WAKE = "41"           # wake the kettle (sent before ON)
ON = "39"             # start heating: boilType, target C, keep-warm minutes, brew, delay
OFF = "3A"            # stop heating / stop keep warm
KEEP_WARM = "4A"      # start (minutes) or stop (0) keep warm on hot water
SCAN = "3D"           # list the Wi-Fi networks the kettle can see (read-only)
SET_WIFI = "3E"       # join a network by BSSID (see docs/protocol.md before using)
SAFE_COMMANDS = {WAKE, ON, OFF, KEEP_WARM, SCAN, SET_WIFI}
NEVER_SEND = {"47": "starts an MCU firmware update (could brick the kettle)",
              "35": "overwrites the kettle's serial number / ID"}

PREFIX_OPERATIONAL = "0000"
PREFIX_SETUP = "0100"     # used by 0x3D and 0x3E

STATES = {0: "Not on base", 1: "Unknown (1)", 2: "Standby", 3: "Ready", 4: "Heating", 5: "Keep Warm"}


# ------------------------------------------------------------------ AA packet

def u8hex(n):
    return format(n & 0xFF, "02X")


def checksum(hexstr):
    """Sum of the bytes, low 8 bits, XOR 0xFF (computed over the STUFFED length+body)."""
    total = sum(int(hexstr[i:i + 2], 16) for i in range(0, len(hexstr), 2))
    return u8hex((total ^ 0xFF) & 0xFF)


def stuff(hexstr):
    """After the head byte, every AA byte is sent as AA 55."""
    out = []
    for i in range(0, len(hexstr), 2):
        b = hexstr[i:i + 2].upper()
        out.append("AA55" if b == "AA" else b)
    return "".join(out)


def unstuff(hexstr):
    out, prev_aa = [], False
    for i in range(0, len(hexstr), 2):
        b = hexstr[i:i + 2].upper()
        if prev_aa and b == "55":
            prev_aa = False
            continue
        out.append(b)
        prev_aa = b == "AA"
    return "".join(out)


def build_packet(api, serial, prefix=PREFIX_OPERATIONAL, content=""):
    """The inner 'AA' packet as upper-case hex. No safety check: use build_command()."""
    msg = prefix + "000000000000" + u8hex(serial) + api.upper() + "0000" + content.upper()
    length = format(len(msg) // 2 + 1, "04X")      # bytes after the length field, incl. checksum
    st = stuff(length + msg)
    return "AA" + st + stuff(checksum(st))


def build_command(api, serial, content="", prefix=None):
    api = api.upper()
    if api in NEVER_SEND:
        raise ValueError("refusing to build 0x%s: %s" % (api, NEVER_SEND[api]))
    if api not in SAFE_COMMANDS:
        raise ValueError("0x%s is not in the list of commands this tool sends" % api)
    if prefix is None:
        prefix = PREFIX_SETUP if api in (SCAN, SET_WIFI) else PREFIX_OPERATIONAL
    return build_packet(api, serial, prefix, content)


def wire(imei, packet):
    """The TCP frame: ##<4 hex digits = JSON length><JSON>&&"""
    body = json.dumps({"app_cmd": "62", "imei": imei, "SubDev": "", "data2": packet}, separators=(",", ":"))
    # Upper-case hex length, as in the frames proven on a real kettle (e.g. ##005F).
    return ("##" + format(len(body), "04X") + body + "&&").encode("ascii")


# ------------------------------------------------------------------ commands

def on_content(target_c=100, keep_warm_min=0):
    if not 40 <= target_c <= 100:
        raise ValueError("target must be 40..100 C")
    if not 0 <= keep_warm_min <= 30:
        raise ValueError("keep warm must be 0..30 minutes")
    return u8hex(0) + u8hex(target_c) + u8hex(keep_warm_min) + u8hex(0) + u8hex(0)


def keep_warm_content(minutes):
    if not 0 <= minutes <= 30:
        raise ValueError("keep warm must be 0..30 minutes")
    return u8hex(minutes)


def set_wifi_content(bssid_field_hex, password):
    """0x3E content: len + BSSID text (exactly as the 0x3D scan reported it, hex) + len + password ASCII."""
    pw_hex = password.encode("ascii").hex().upper()
    return u8hex(len(bssid_field_hex) // 2) + bssid_field_hex.upper() + u8hex(len(pw_hex) // 2) + pw_hex


# ------------------------------------------------------------------ decoding

_FRAME = re.compile(rb"##([0-9a-fA-F]{4})(.*?)&&", re.S)


def split_frames(buf):
    """Return (frames, rest) where frames are decoded text frames found in the byte buffer."""
    frames = []
    while b"&&" in buf:
        i = buf.index(b"&&")
        frames.append(buf[:i + 2].decode("ascii", "replace"))
        buf = buf[i + 2:]
    return frames, buf


def parse_frame(text):
    """A kettle frame as a dict: imei, api, serial, prefix, content (hex), or None."""
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        return None
    try:
        obj = json.loads(m.group(0))
    except ValueError:
        return None
    data = obj.get("data3") or obj.get("data2")
    out = {"imei": obj.get("imei")}
    if data:
        p = parse_packet(data)
        if p:
            out.update(p)
    return out


def parse_packet(hexstr):
    u = unstuff(hexstr.upper())
    if len(u) < 32 or not u.startswith("AA"):
        return None
    return {"prefix": u[6:10], "serial": int(u[22:24], 16), "api": u[24:26], "content": u[30:-2], "checksum": u[-2:]}


def parse_status(api, content):
    """Status from a 0x36 or 0xA4 frame's content (11 bytes). Byte 0 is skipped (C8 on 0x36, varies on A4)."""
    c = content.upper()
    if api.upper() not in ("36", "A4") or len(c) < 22:
        return None
    b = bytes.fromhex(c[:22])
    return {"state": STATES.get(b[2], "Unknown (%d)" % b[2]), "state_code": b[2], "boil_mode": b[1],
            "keep_warm_s": (b[3] << 8) | b[4], "temp_c": b[5], "target_c": b[6],
            "water_ml": (b[7] << 8) | b[8], "brew": b[9], "delay": b[10]}


def parse_scan(content):
    """0x3D reply: list of {ssid, bssid_text, bssid_field_hex, security, signal}."""
    c = content.upper()
    if not c.startswith("C8") or len(c) < 4:
        return None
    rest, out = c[4:], []
    while len(rest) >= 10:
        entry_len = int(rest[0:2], 16)
        l1 = int(rest[2:4], 16) * 2
        if 4 + l1 + 2 > len(rest):
            break
        ssid = bytes.fromhex(rest[4:4 + l1]).decode("utf-8", "replace")
        l2 = int(rest[4 + l1:6 + l1], 16) * 2
        if 10 + l1 + l2 > len(rest):
            break
        field = rest[6 + l1:6 + l1 + l2]
        sec = int(rest[6 + l1 + l2:8 + l1 + l2], 16)
        sig = int(rest[8 + l1 + l2:10 + l1 + l2], 16)
        try:
            text = bytes.fromhex(field).decode("ascii")
        except ValueError:
            text = "?"
        out.append({"ssid": ssid, "bssid_text": text, "bssid_field_hex": field, "security": sec,
                    "signal": sig, "entry_len": entry_len})
        rest = rest[10 + l1 + l2:]
    return out


def scan_entry(ssid, bssid_text, security=1, signal=50):
    """Build one 0x3D scan entry (used to make synthetic test data)."""
    s = ssid.encode("utf-8").hex().upper()
    b = bssid_text.encode("ascii").hex().upper()
    body = u8hex(len(s) // 2) + s + u8hex(len(b) // 2) + b + u8hex(security) + u8hex(signal)
    return u8hex(len(body) // 2 + 1) + body
