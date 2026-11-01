#!/usr/bin/env python3
"""Control and read an AppKettle on your local network (TCP port 6002). No cloud.

Examples (replace 192.168.1.50 with your kettle's address):
  python kettle.py find                          find kettles on this PC's network (read-only)
  python kettle.py status --host 192.168.1.50    show state, temperature and water (read-only)
  python kettle.py watch  --host 192.168.1.50    print every status update for 60 s (read-only)
  python kettle.py scan   --host 192.168.1.50    list the Wi-Fi networks the kettle can see (read-only)
  python kettle.py boil   --host 192.168.1.50    boil (100 C); --temp 80 --keep-warm 10 for other settings
  python kettle.py off    --host 192.168.1.50    stop heating or keep warm
  python kettle.py keep-warm --host 192.168.1.50 --minutes 10   keep hot water warm (water must be >= 60 C)
  python kettle.py frames --imei GD0-00000-0000  print example frames, send nothing

Standard library only (Python 3.8+). The kettle's ID ("imei") is read from the kettle
itself, so you only need its IP address. This tool can only send WAKE 0x41, ON 0x39,
OFF 0x3A, KEEP WARM 0x4A and SCAN 0x3D; it can never send 0x47 or 0x35 (see docs/protocol.md).
Boiling water is dangerous: only switch a kettle on when someone is with it.
"""
import argparse
import ipaddress
import os
import socket
import sys
import time
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # find kettle_protocol.py next to this file
import kettle_protocol as P

MIN_WATER_ML = 250


def log(*a):
    print(*a, flush=True)


class Link:
    """One TCP connection to a kettle. Retries the connect: the kettle power-saves and may be slow."""

    def __init__(self, host, port=P.PORT, connect_for=30.0):
        self.host, self.port = host, port
        self.sock = None
        self.buf = b""
        self.imei = None
        self.status = None
        self.serial = 0
        self.last_ka = 0.0
        end = time.time() + connect_for
        err = None
        while time.time() < end:
            try:
                self.sock = socket.create_connection((host, port), timeout=5)
                break
            except OSError as e:
                err = e
                time.sleep(2)
        if not self.sock:
            raise SystemExit("Could not connect to %s:%d (%s). Is the kettle on its base, on this network, "
                             "and is the address right? Try: python kettle.py find" % (host, port, err))
        self.sock.settimeout(0.5)
        self._keep_alive()

    def _keep_alive(self):
        self.sock.sendall(P.KEEP_ALIVE)
        self.last_ka = time.time()

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass

    def pump(self, seconds, want_api=None, want_serial=None):
        """Read frames for up to `seconds`. Returns the first frame with api == want_api (and serial), else None."""
        end = time.time() + seconds
        while time.time() < end:
            if time.time() - self.last_ka > 25:
                self._keep_alive()
            try:
                d = self.sock.recv(4096)
                if not d:
                    log("The kettle closed the connection.")
                    return None
                self.buf += d
            except socket.timeout:
                continue
            frames, self.buf = P.split_frames(self.buf)
            for text in frames:
                f = P.parse_frame(text)
                if not f:
                    continue
                if f.get("imei"):
                    self.imei = f["imei"]
                st = P.parse_status(f.get("api", ""), f.get("content", "")) if f.get("api") else None
                if st:
                    self.status = st
                if want_api and f.get("api") == want_api and (want_serial is None or f.get("serial") == want_serial):
                    return f
        return None

    def wait_status(self, seconds=15):
        end = time.time() + seconds
        while time.time() < end and (self.status is None or self.imei is None):
            self.pump(1)
        if self.status is None or self.imei is None:
            raise SystemExit("Connected, but the kettle sent no status within %d s. Try again." % seconds)
        return self.status

    def command(self, api, content="", label=None, wait=10):
        """Send one command and wait for its ack. Returns True on ack C8."""
        self.serial = self.serial % 250 + 1
        pkt = P.build_command(api, self.serial, content)
        self.sock.sendall(P.wire(self.imei, pkt))
        log("sent %s (0x%s)" % (label or api, api))
        r = self.pump(wait, want_api=api, want_serial=self.serial)
        if r is None:
            log("  no reply")
            return False
        ok = r.get("content", "")[:2].upper() == "C8"
        log("  ack %s" % ("OK (C8)" if ok else "REFUSED (%s)" % r.get("content", "")[:2]))
        return ok


def fmt(st):
    s = "%s, %d C (target %d), water ~%d ml" % (st["state"], st["temp_c"], st["target_c"], st["water_ml"])
    if st["state"] == "Keep Warm":
        s += ", keep warm %d s left" % st["keep_warm_s"]
    return s


# ------------------------------------------------------------------ commands

def cmd_status(a):
    L = Link(a.host, a.port)
    st = L.wait_status()
    log("Kettle ID: %s" % L.imei)
    log("Status:    %s" % fmt(st))
    L.close()


def cmd_watch(a):
    L = Link(a.host, a.port)
    L.wait_status()
    log("Kettle ID: %s" % L.imei)
    end = time.time() + a.seconds
    last = None
    while time.time() < end:
        L.pump(1)
        if L.status and L.status != last:
            log(time.strftime("%H:%M:%S"), fmt(L.status))
            last = L.status
    L.close()


def cmd_boil(a):
    L = Link(a.host, a.port)
    st = L.wait_status()
    log("Before: %s" % fmt(st))
    if st["state_code"] not in (2, 3, 4, 5):
        raise SystemExit("Not sending: the kettle is not on its base (or reports an unknown state).")
    if st["water_ml"] < MIN_WATER_ML and not a.allow_low_water:
        raise SystemExit("Not sending: the kettle reports about %d ml; the minimum is %d ml. Fill it, or add "
                         "--allow-low-water if the reading is wrong." % (st["water_ml"], MIN_WATER_ML))
    target = 100 if a.temp >= 90 else a.temp   # the kettle stops by itself at the real boil
    if a.keep_warm and target < 60:
        raise SystemExit("Keep warm needs a target of 60 C or more.")
    if st["state_code"] not in (4, 5):
        if not L.command(P.WAKE, label="WAKE"):
            raise SystemExit("WAKE was not acknowledged; ON not sent.")
    ok = L.command(P.ON, P.on_content(target, a.keep_warm), label="ON %d C, keep warm %d min" % (target, a.keep_warm))
    L.pump(3)
    if L.status:
        log("After:  %s" % fmt(L.status))
    L.close()
    sys.exit(0 if ok else 1)


def cmd_off(a):
    L = Link(a.host, a.port)
    L.wait_status()
    ok = L.command(P.OFF, label="OFF")
    L.pump(3)
    if L.status:
        log("After: %s" % fmt(L.status))
    L.close()
    sys.exit(0 if ok else 1)


def cmd_keep_warm(a):
    L = Link(a.host, a.port)
    st = L.wait_status()
    log("Before: %s" % fmt(st))
    if a.minutes == 0:
        # Stopping keep warm: OFF is the proven way when the kettle is in Keep Warm.
        ok = L.command(P.OFF, label="OFF") if st["state_code"] == 5 else L.command(P.KEEP_WARM, "00", "KEEP WARM off")
    else:
        if st["state_code"] == 2:
            raise SystemExit("The kettle is in Standby: it refuses keep warm there. Boil with --keep-warm instead.")
        if st["state_code"] not in (3, 4, 5) or st["temp_c"] < 60:
            raise SystemExit("Keep warm only works on water at 60 C or more (Ready, Heating or Keep Warm).")
        ok = L.command(P.KEEP_WARM, P.keep_warm_content(a.minutes), label="KEEP WARM %d min" % a.minutes)
    L.pump(3)
    if L.status:
        log("After:  %s" % fmt(L.status))
    L.close()
    sys.exit(0 if ok else 1)


def cmd_scan(a):
    L = Link(a.host, a.port)
    L.wait_status()
    L.serial = 0
    pkt = P.build_command(P.SCAN, 1)
    L.sock.sendall(P.wire(L.imei, pkt))
    log("sent SCAN (0x3D); waiting up to 30 s ...")
    r = L.pump(30, want_api=P.SCAN)
    L.close()
    if not r:
        raise SystemExit("No scan reply.")
    nets = P.parse_scan(r["content"]) or []
    log("%-32s %-14s %-8s %s" % ("SSID", "BSSID text", "secure", "signal"))
    for n in sorted(nets, key=lambda n: -n["signal"]):
        log("%-32s %-14s %-8s %d" % (n["ssid"], n["bssid_text"], "yes" if n["security"] else "no", n["signal"]))


def _probe(ip, port, timeout):
    """Keep-alive only; returns the kettle ID if a kettle answers with a status frame."""
    try:
        with socket.create_connection((ip, port), timeout=timeout) as s:
            s.settimeout(0.5)
            s.sendall(P.KEEP_ALIVE)
            buf, end = b"", time.time() + 3
            while time.time() < end:
                try:
                    d = s.recv(4096)
                except socket.timeout:
                    continue
                if not d:
                    break
                buf += d
                frames, buf = P.split_frames(buf)
                for t in frames:
                    f = P.parse_frame(t)
                    if f and f.get("imei"):
                        return f["imei"]
    except OSError:
        pass
    return None


def own_ipv4():
    """This PC's address on its default route. A UDP connect sends no packet."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))
        return s.getsockname()[0]
    finally:
        s.close()


def cmd_find(a):
    net = ipaddress.ip_network(a.subnet or (own_ipv4() + "/24"), strict=False)
    if net.num_addresses > 1024:
        raise SystemExit("Subnet too large; give a /22 or smaller with --subnet.")
    hosts = [str(h) for h in net.hosts()]
    log("Looking for kettles on %s (TCP %d, keep-alive only) ..." % (net, a.port))
    found = []
    for rnd in range(a.rounds):
        with ThreadPoolExecutor(64) as ex:
            for ip, imei in zip(hosts, ex.map(lambda h: _probe(h, a.port, 0.8), hosts)):
                if imei and ip not in [f[0] for f in found]:
                    found.append((ip, imei))
                    log("  found kettle %s at %s" % (imei, ip))
        if found:
            break
        log("  round %d/%d: nothing yet (kettles power-save; retrying)" % (rnd + 1, a.rounds))
        time.sleep(5)
    if not found:
        log("No kettle answered. Check the kettle is on this Wi-Fi network, then see docs/faq.md.")
        sys.exit(1)


def cmd_frames(a):
    for label, api, content in (("WAKE", P.WAKE, ""), ("ON 100 C", P.ON, P.on_content(100, 0)),
                                ("ON 100 C + keep warm 5", P.ON, P.on_content(100, 5)), ("OFF", P.OFF, ""),
                                ("KEEP WARM 5", P.KEEP_WARM, P.keep_warm_content(5)), ("SCAN", P.SCAN, "")):
        log("%-24s %s" % (label, P.wire(a.imei, P.build_command(api, 1, content)).decode()))


def temp_c(text):
    """argparse type for --temp: a whole number from 40 to 100 (checked before anything is sent)."""
    try:
        t = int(text)
    except ValueError:
        raise argparse.ArgumentTypeError("%r is not a whole number of degrees C" % text)
    if not 40 <= t <= 100:
        raise argparse.ArgumentTypeError("%d C is out of range: choose 40 to 100" % t)
    return t


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    def with_host(p):
        p.add_argument("--host", required=True, help="the kettle's IP address")
        p.add_argument("--port", type=int, default=P.PORT, help="default 6002")
        return p

    with_host(sub.add_parser("status", help="show the kettle's status (read-only)")).set_defaults(f=cmd_status)
    p = with_host(sub.add_parser("watch", help="print status updates (read-only)"))
    p.add_argument("--seconds", type=float, default=60)
    p.set_defaults(f=cmd_watch)
    p = with_host(sub.add_parser("boil", help="heat to a temperature (default: boil)"))
    p.add_argument("--temp", type=temp_c, default=100, help="40..100 C; 90 or more is sent as 100 (boil)")
    p.add_argument("--keep-warm", type=int, default=0, choices=[0, 5, 10, 15, 20, 30], help="minutes after the boil")
    p.add_argument("--allow-low-water", action="store_true", help="send even if the water reading is below 250 ml")
    p.set_defaults(f=cmd_boil)
    with_host(sub.add_parser("off", help="stop heating / keep warm")).set_defaults(f=cmd_off)
    p = with_host(sub.add_parser("keep-warm", help="keep hot water warm now (0 = stop)"))
    p.add_argument("--minutes", type=int, required=True, choices=[0, 5, 10, 15, 20, 30])
    p.set_defaults(f=cmd_keep_warm)
    with_host(sub.add_parser("scan", help="list Wi-Fi networks the kettle sees (read-only)")).set_defaults(f=cmd_scan)
    p = sub.add_parser("find", help="find kettles on this PC's network (read-only)")
    p.add_argument("--subnet", help="e.g. 192.168.1.0/24 (default: this PC's /24)")
    p.add_argument("--port", type=int, default=P.PORT)
    p.add_argument("--rounds", type=int, default=4)
    p.set_defaults(f=cmd_find)
    p = sub.add_parser("frames", help="print example frames; sends nothing")
    p.add_argument("--imei", default="GD0-00000-0000", help="kettle ID to put in the frames")
    p.set_defaults(f=cmd_frames)
    a = ap.parse_args(argv)
    a.f(a)


if __name__ == "__main__":
    main()
