#!/usr/bin/env python3
"""Pair an AppKettle with a Wi-Fi network ("smart-config" sender). Standard library only.

Gives a kettle a Wi-Fi name (SSID) and password without the original app. Run it on a
PC that is connected, as a normal Wi-Fi client, to an ANDROID PHONE HOTSPOT; then hold
the kettle's Wi-Fi button for about 5 seconds (or longer) until its light flashes fast,
then release it. Read docs/re-pairing.md first:
the method only works through a phone hotspot (not a router, repeater or PC hotspot).

  python smartconfig_send.py --ssid "MyKettleWiFi" --iface-ip 192.168.43.20 --duration 150
  python smartconfig_send.py --ssid "MyKettleWiFi" --password-env KETTLE_WIFI_PW --iface-ip ...
  python smartconfig_send.py --ssid "Test" --password "abc12345" --iface-ip 10.0.0.2 --dry-run

The password is asked for interactively unless --password or --password-env is given.

How it works (details in docs/protocol.md, "Wi-Fi pairing"): the SSID and password are
coded into (a) the destination addresses of 24-byte multicast packets to 239.12.x.y /
239.14.x.y port 15100, TTL 1, and (b) the LENGTHS of broadcast packets of '1' characters
sent to the subnet broadcast address, port 15100, from source port 38000. The kettle,
not yet connected, reads both from the air. A kettle may answer "RMTConfigSuccess#..."
on UDP 15100 (the sender then acknowledges it), but in practice no reply was ever seen,
even when pairing worked: check success on the real network instead.
"""
import argparse
import datetime
import getpass
import ipaddress
import os
import socket
import struct
import sys
import threading
import time

PORT = 15100            # b.a: config / ack port
SRC_PORT = 38000        # b.e: source port of the length (broadcast) sender
REPLY_PORT = 15100      # b.f: port the phone listens on
MCAST_PAYLOAD = b"1" * 24
ACK = b"RMTSMARTCONFIGACK"
LOG_PATH = None          # set by --log


# ---------------------------------------------------------------- encoder ---

def _signed(b):
    """Java byte value (signed)."""
    return b - 256 if b > 127 else b


def sum8(data: bytes) -> int:
    """Checksum: sum of the bytes as signed (Java) bytes, as hex, last 2 hex digits."""
    total = sum(_signed(b) for b in data)
    h = format(total & 0xFFFFFFFF, "x")      # Java toHexString of an int
    if len(h) > 2:
        h = h[-2:]
    return int(h, 16)


def _jlen(s: str) -> int:
    """String length as UTF-16 code units (what the original sender counted)."""
    return len(s.encode("utf-16-le")) // 2


def multicast_list(ssid: str, pw: str) -> list:
    """List A: multicast destination addresses carrying the SSID (239.12) and password (239.14)."""
    sb, pb = ssid.encode("utf-8"), pw.encode("utf-8")
    out = []
    for blk, data in ((12, sb), (14, pb)):
        out += ["239.%d.0.0" % i for i in range(6, 12)]
        out.append("239.%d.0.%d" % (blk, len(data) + 1))
        for i, b in enumerate(data):
            a = "239.%d.%d.%d" % (blk, i + 1, b & 255)
            out += [a, a]
        out.append("239.%d.%d.%d" % (blk, len(data) + 1, sum8(data)))
    return out


def length_list(ssid: str, pw: str) -> list:
    """List B: broadcast payload lengths carrying the password first, then the SSID."""
    sb, pb = ssid.encode("utf-8"), pw.encode("utf-8")
    out = []
    for _ in range(20):
        out += [40, 50]
    if _jlen(pw) == 0:
        out += [_jlen(pw) + 160] * 20
    else:
        out += [_jlen(pw) + 160] * 10
        out += [_signed(b) + 200 for b in pb]
        out.append(sum8(pb) + 200)
    out += [_jlen(ssid) + 80] * 10
    out += [_signed(b) + 460 for b in sb]
    out.append(sum8(sb) + 460)
    return out


def round_schedule(mcast: list, lengths: list) -> list:
    """One sending round as (sleep_ms_before, kind, value).
    kind is 'M' (multicast to address value) or 'B' (broadcast of length value)."""
    sched = []
    first_sleep = 30
    def add(ms, kind, val):
        nonlocal first_sleep
        sched.append((ms + first_sleep, kind, val))
        first_sleep = 0
    if len(lengths) > len(mcast):
        for i in range(len(mcast)):
            add(0, "M", mcast[i])
            add(10, "B", lengths[i])
        for i in range(len(mcast), len(lengths)):
            add(10, "B", lengths[i])
    else:
        for i in range(len(lengths)):
            add(0, "M", mcast[i])
            add(10, "B", lengths[i])
        for i in range(len(lengths), len(mcast)):
            add(10, "M", mcast[i])
    return sched


def subnet_broadcast(ip: str, prefix: int) -> str:
    return str(ipaddress.IPv4Network("%s/%d" % (ip, prefix), strict=False).broadcast_address)


# ------------------------------------------------------------------ log -----

_log_lock = threading.Lock()


def log(*parts):
    line = "%s %s" % (datetime.datetime.now().isoformat(timespec="milliseconds"),
                      " ".join(str(p) for p in parts))
    with _log_lock:
        print(line, flush=True)
        if LOG_PATH:
            with open(LOG_PATH, "a", encoding="utf-8") as f:
                f.write(line + "\n")


# --------------------------------------------------------------- sending ----

class Receiver(threading.Thread):
    def __init__(self, iface_ip):
        super().__init__(daemon=True)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind((iface_ip, REPLY_PORT))
        self.sock.settimeout(0.2)
        self.iface_ip = iface_ip
        self.stop = threading.Event()
        self.config_seen = threading.Event()   # any RMTConfig: stop sender
        self.success = None                    # (text, ip)
        self.own_ones = 0

    def run(self):
        while not self.stop.is_set():
            try:
                data, (ip, port) = self.sock.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError as e:
                log("RECV error:", e)
                continue
            if ip == self.iface_ip and data and data.strip(b"1") == b"":
                self.own_ones += 1          # our own broadcast looped back
                continue
            text = data.decode("utf-8", "replace")
            log("RECV from %s:%d len=%d: %r" % (ip, port, len(data), text))
            if "RMTConfig" in text:
                self.config_seen.set()
                if "RMTConfigSuccess" in text and "GD" in text and self.success is None:
                    self.success = (text, ip)
                    log("REPLY RMTConfigSuccess from", ip, "fields:", text.split("#"))
                    for i in range(20):
                        self.sock.sendto(ACK, (ip, PORT))
                        log("ACK %d/20 -> %s:%d" % (i + 1, ip, PORT))
                        time.sleep(0.060)


def sleep_ms(ms):
    if ms:
        time.sleep(ms / 1000.0)


def run(args, mcast, lengths, bcast):
    iface = args.iface_ip
    rx = Receiver(iface)
    rx.start()
    log("listening on %s:%d" % (iface, REPLY_PORT))

    bsock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    bsock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    bsock.bind((iface, SRC_PORT))

    msock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    msock.bind((iface, 0))
    msock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(iface))
    msock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
    log("bcast sock %s, mcast sock %s (IF=%s TTL=1), bcast dest %s:%d"
        % (bsock.getsockname(), msock.getsockname(), iface, bcast, PORT))

    payloads = {n: b"1" * n for n in set(lengths)}
    sched = round_schedule(mcast, lengths)
    join_ok = True
    t0 = time.monotonic()
    end = t0 + args.duration
    rounds = sent_m = sent_b = 0
    errors = 0
    try:
        while time.monotonic() < end and not rx.config_seen.is_set():
            for ms, kind, val in sched:
                sleep_ms(ms)
                if rx.config_seen.is_set() or time.monotonic() >= end:
                    break
                try:
                    if kind == "M":
                        mreq = socket.inet_aton(val) + socket.inet_aton(iface)
                        if join_ok:
                            try:
                                msock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
                            except OSError as e:
                                join_ok = False
                                log("joinGroup failed (continuing without join/leave):", e)
                        msock.sendto(MCAST_PAYLOAD, (val, PORT))
                        if join_ok:
                            try:
                                msock.setsockopt(socket.IPPROTO_IP, socket.IP_DROP_MEMBERSHIP, mreq)
                            except OSError:
                                pass
                        sent_m += 1
                    else:
                        bsock.sendto(payloads[val], (bcast, PORT))
                        sent_b += 1
                except OSError as e:
                    errors += 1
                    if errors <= 5:
                        log("SEND error (%s %s): %s" % (kind, val, e))
            else:
                rounds += 1
                if rounds == 1 or rounds % 10 == 0:
                    log("round %d done, t=%.1fs, sent mcast=%d bcast=%d"
                        % (rounds, time.monotonic() - t0, sent_m, sent_b))
                continue
            break
    finally:
        log("sender stopped: t=%.1fs rounds=%d mcast=%d bcast=%d errors=%d reason=%s"
            % (time.monotonic() - t0, rounds, sent_m, sent_b, errors,
               "RMTConfig reply" if rx.config_seen.is_set() else "duration"))
        bsock.close()
        msock.close()

    # keep listening briefly (app: success reported 3 s after ACK start)
    wait_end = time.monotonic() + (5 if rx.config_seen.is_set() else 3)
    while time.monotonic() < wait_end:
        time.sleep(0.1)
    rx.stop.set()
    rx.join(2)
    rx.sock.close()
    log("own broadcast echoes ignored:", rx.own_ones)
    if rx.success:
        log("RESULT: RMTConfigSuccess from %s: %r" % (rx.success[1], rx.success[0]))
        return 0
    log("RESULT: no RMTConfigSuccess reply" + (" (RMTConfig seen)" if rx.config_seen.is_set() else ""))
    return 2


def check_password(pw):
    """Problems with a password the kettle is known not to accept (8-32 characters, letters and digits only)."""
    out = []
    if not 8 <= len(pw) <= 32:
        out.append("length %d: the kettle accepts 8 to 32 characters (WPA2 needs at least 8)" % len(pw))
    if not pw.isascii() or not pw.isalnum():
        out.append("contains characters other than letters and digits; the kettle may not accept it")
    return out


def main(argv=None):
    global LOG_PATH
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ssid", required=True, help="the network name the kettle should join (exact, case-sensitive)")
    ap.add_argument("--password", help="the network password (visible in shell history; prefer the prompt)")
    ap.add_argument("--password-env", metavar="VAR", help="read the password from this environment variable")
    ap.add_argument("--iface-ip", required=True, help="this PC's IPv4 address on the phone hotspot (see ipconfig / ip addr)")
    ap.add_argument("--prefix", type=int, default=24, help="subnet prefix of --iface-ip (default 24)")
    ap.add_argument("--duration", type=float, default=150.0, help="seconds to send (default 150)")
    ap.add_argument("--log", help="also append the output to this file (the password is never logged)")
    ap.add_argument("--dry-run", action="store_true", help="print the packet schedule; send nothing")
    ap.add_argument("--force", action="store_true",
                    help="send even if the password breaks the kettle's rules (8-32 letters and digits)")
    ap.add_argument("--rounds-shown", type=int, default=1, help="rounds to print with --dry-run")
    args = ap.parse_args(argv)
    LOG_PATH = args.log
    if args.password is None:
        if args.password_env:
            args.password = os.environ.get(args.password_env)
            if args.password is None:
                ap.error("environment variable %s is not set" % args.password_env)
        else:
            args.password = getpass.getpass("Wi-Fi password for %r: " % args.ssid)
    problems = check_password(args.password)
    for w in problems:
        print(("WARNING: password " if args.force else "ERROR: password ") + w, file=sys.stderr)
    if problems and not args.force:
        ap.error("the kettle is known not to accept this password; use a network password of 8-32 "
                 "letters and digits (see docs/re-pairing.md), or add --force to send it anyway")
    if len(args.ssid.encode("utf-8")) > 32:
        ap.error("SSID longer than 32 bytes")

    mcast = multicast_list(args.ssid, args.password)
    lengths = length_list(args.ssid, args.password)
    bcast = subnet_broadcast(args.iface_ip, args.prefix)
    sched = round_schedule(mcast, lengths)
    round_ms = sum(ms for ms, _, _ in sched)

    log("=== smartconfig_send %s ssid=%r password=<%d chars> iface=%s bcast=%s:%d src_port=%d duration=%ss"
        % ("DRY RUN" if args.dry_run else "LIVE", args.ssid, len(args.password), args.iface_ip,
           bcast, PORT, SRC_PORT, args.duration))
    log("list A (multicast, %d addrs): %s" % (len(mcast), " ".join(mcast)))
    log("list B (broadcast lengths, %d): %s" % (len(lengths), ",".join(map(str, lengths))))
    log("round: %d packets (%d mcast + %d bcast), nominal %d ms of sleeps; ~%d rounds in %ss"
        % (len(sched), sum(1 for s in sched if s[1] == "M"), sum(1 for s in sched if s[1] == "B"),
           round_ms, int(args.duration * 1000 // round_ms), args.duration))

    if args.dry_run:
        t = 0
        for r in range(args.rounds_shown):
            log("-- round %d --" % (r + 1))
            for ms, kind, val in sched:
                t += ms
                if kind == "M":
                    log("  t=%5dms  MCAST %-15s :%d  24 bytes, TTL 1" % (t, val, PORT))
                else:
                    log("  t=%5dms  BCAST %s:%d from :%d  %d bytes" % (t, bcast, PORT, SRC_PORT, val))
        log("DRY RUN: nothing sent")
        return 0
    return run(args, mcast, lengths, bcast)


if __name__ == "__main__":
    sys.exit(main())
