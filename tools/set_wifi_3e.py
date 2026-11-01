#!/usr/bin/env python3
"""Move a kettle that is ALREADY REACHABLE to another Wi-Fi access point with command 0x3E.

READ docs/re-pairing.md FIRST. Prefer smartconfig_send.py (the proven method). Use 0x3E
only if you understand these limits:
  * 0x3E stores ONE access point's BSSID (its radio's MAC address, as 12 hex characters), not the
    network name. The kettle is then tied to that single access point; if it is replaced,
    or the network has several access points, the kettle may not reconnect.
  * In the one real test the kettle acknowledged 0x3E (C8), left its network and never
    joined the target (an enterprise access-point system). It was recovered with
    smart-config. A consumer router may behave better; this is untested.
  * After a 0x3E the kettle closes the connection. If it does not appear on the target
    network, put it back with smartconfig_send.py.

Steps:
  python set_wifi_3e.py scan --host 192.168.1.50
      lists the networks the kettle can see (read-only).
  python set_wifi_3e.py join --host 192.168.1.50 --ssid "MyNetwork" [--bssid 0a1b2c3d4e5f]
      scans again, picks the strongest access point named MyNetwork (or the given BSSID),
      asks for the password, shows the frame and asks you to type YES, then sends 0x3E ONCE.
  python set_wifi_3e.py build --bssid 0a1b2c3d4e5f --imei GD0-00000-0000
      prints the frame for a test password; sends nothing.

The password must be 8-32 characters, letters and digits only (WPA2-Personal, 2.4 GHz).
It is read from a prompt (or --password-env VAR) and never printed.
"""
import argparse
import getpass
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # find kettle_protocol.py next to this file
import kettle_protocol as P
from kettle import Link, log


def scan(host, port):
    L = Link(host, port)
    L.wait_status()
    L.sock.sendall(P.wire(L.imei, P.build_command(P.SCAN, 1)))
    log("sent SCAN (0x3D); waiting up to 30 s ...")
    r = L.pump(30, want_api=P.SCAN)
    if not r:
        L.close()
        raise SystemExit("No scan reply.")
    return L, P.parse_scan(r["content"]) or []


def show(nets):
    log("%-32s %-14s %s" % ("SSID", "BSSID text", "signal"))
    for n in sorted(nets, key=lambda n: -n["signal"]):
        log("%-32s %-14s %d" % (n["ssid"], n["bssid_text"], n["signal"]))


def redacted(frame_text, password):
    h = password.encode("ascii").hex()
    for v in (h.upper(), h.lower(), password):
        if v:
            frame_text = frame_text.replace(v, "<PASSWORD>")
    return frame_text


def get_password(a):
    if a.password_env:
        pw = os.environ.get(a.password_env)
        if pw is None:
            raise SystemExit("environment variable %s is not set" % a.password_env)
    else:
        pw = getpass.getpass("Password for %r: " % a.ssid)
    if not (pw.isascii() and pw.isalnum() and 8 <= len(pw) <= 32):
        log("WARNING: the kettle expects 8-32 letters and digits only.")
    return pw


def cmd_scan(a):
    L, nets = scan(a.host, a.port)
    L.close()
    show(nets)


def cmd_join(a):
    L, nets = scan(a.host, a.port)
    show(nets)
    cand = [n for n in nets if n["ssid"] == a.ssid and (not a.bssid or n["bssid_text"].lower() == a.bssid.lower())]
    if not cand:
        L.close()
        raise SystemExit("%r (bssid %s) is not in the kettle's scan; it must be visible to the kettle." % (a.ssid, a.bssid or "any"))
    best = max(cand, key=lambda n: n["signal"])
    log("Chosen: %r BSSID %s signal %d" % (best["ssid"], best["bssid_text"], best["signal"]))
    pw = get_password(a)
    pkt = P.build_command(P.SET_WIFI, 2, P.set_wifi_content(best["bssid_field_hex"], pw))
    frame = P.wire(L.imei, pkt)
    log("Frame: " + redacted(frame.decode(), pw))
    if input("This ties the kettle to that one access point. Type YES to send 0x3E once: ").strip() != "YES":
        L.close()
        raise SystemExit("Not sent.")
    L.sock.sendall(frame)
    r = L.pump(15, want_api=P.SET_WIFI)
    if r:
        log("ack: %s" % ("OK (C8)" if r.get("content", "")[:2].upper() == "C8" else r.get("content")))
    else:
        log("no ack (the kettle may already have left the network)")
    L.close()
    log("Now connect your PC/phone to %r and look for the kettle: python kettle.py find" % a.ssid)


def cmd_build(a):
    pkt = P.build_command(P.SET_WIFI, 1, P.set_wifi_content(a.bssid.encode("ascii").hex(), "example1"))
    log(P.wire(a.imei, pkt).decode() + "   (password 'example1')")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("scan", help="list networks the kettle can see (read-only)")
    p.add_argument("--host", required=True)
    p.add_argument("--port", type=int, default=P.PORT)
    p.set_defaults(f=cmd_scan)
    p = sub.add_parser("join", help="send 0x3E once (asks for confirmation)")
    p.add_argument("--host", required=True)
    p.add_argument("--port", type=int, default=P.PORT)
    p.add_argument("--ssid", required=True, help="network name as shown by scan")
    p.add_argument("--bssid", help="12 hex characters from scan, to pick one access point")
    p.add_argument("--password-env", metavar="VAR", help="read the password from this environment variable")
    p.set_defaults(f=cmd_join)
    p = sub.add_parser("build", help="print an example 0x3E frame; sends nothing")
    p.add_argument("--bssid", default="0a1b2c3d4e5f")
    p.add_argument("--imei", default="GD0-00000-0000")
    p.set_defaults(f=cmd_build)
    a = ap.parse_args(argv)
    a.f(a)


if __name__ == "__main__":
    sys.exit(main())
