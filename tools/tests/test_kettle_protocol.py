"""Offline tests for kettle_protocol.py. All IDs, addresses and network names are synthetic.

The expected AA packets are the byte sequences that worked on a real kettle (they contain no
identifiers: the kettle ID travels only in the JSON wrapper, and here it is synthetic).
Run from the tools folder:  python -m unittest discover -s tests
"""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import kettle_protocol as P  # noqa: E402

IMEI = "GD0-00000-0000"


class Packets(unittest.TestCase):
    def test_proven_command_packets(self):
        self.assertEqual(P.build_command(P.WAKE, 1), "AA000D000000000000000001410000B0")
        self.assertEqual(P.build_command(P.ON, 2, P.on_content(100, 0)), "AA001200000000000000000239000000640000004E")
        self.assertEqual(P.build_command(P.ON, 2, P.on_content(100, 5)), "AA0012000000000000000002390000006405000049")
        self.assertEqual(P.build_command(P.OFF, 1), "AA000D0000000000000000013A0000B7")
        self.assertEqual(P.build_command(P.SCAN, 1), "AA000D0100000000000000013D0000B3")
        self.assertEqual(P.build_command(P.KEEP_WARM, 1, P.keep_warm_content(5)), "AA000E0000000000000000014A000005A1")

    def test_checksum_example(self):
        # docs/protocol.md: 0x0D + 0x01 + 0x41 = 0x4F -> B0
        self.assertEqual(P.checksum("000D0000000000000000014100 00".replace(" ", "")), "B0")

    def test_set_wifi_example(self):
        content = P.set_wifi_content("0a1b2c3d4e5f".encode("ascii").hex(), "example1")
        self.assertEqual(content, "0C3061316232633364346535660865" "78616D706C6531")
        pkt = P.build_command(P.SET_WIFI, 1, content)
        self.assertTrue(pkt.startswith("AA00230100000000000000013E0000"))
        u = P.unstuff(pkt)
        self.assertEqual(P.checksum(P.stuff(u[2:-2])), u[-2:])

    def test_stuffing_round_trip(self):
        pkt = P.build_packet("3D", 0xAA, "0100", "02AAAA")
        self.assertIn("AA55", pkt)
        self.assertEqual(P.unstuff(pkt).count("AA"), 4)   # head + serial + 2 content bytes
        p = P.parse_packet(pkt)
        self.assertEqual((p["serial"], p["api"], p["content"]), (0xAA, "3D", "02AAAA"))

    def test_never_send(self):
        for code in ("47", "35"):
            with self.assertRaises(ValueError):
                P.build_command(code, 1)
        with self.assertRaises(ValueError):
            P.build_command("46", 1)   # not in the safe list either

    def test_ranges(self):
        with self.assertRaises(ValueError):
            P.on_content(101, 0)
        with self.assertRaises(ValueError):
            P.keep_warm_content(31)


class Wire(unittest.TestCase):
    def test_wire_length_and_json(self):
        w = P.wire(IMEI, P.build_command(P.WAKE, 1)).decode()
        self.assertTrue(w.startswith("##005F{") and w.endswith("}&&"), w)
        body = json.loads(w[6:-2])
        self.assertEqual(body, {"app_cmd": "62", "imei": IMEI, "SubDev": "", "data2": "AA000D000000000000000001410000B0"})

    def test_split_frames(self):
        frames, rest = P.split_frames(P.KEEP_ALIVE + b'##0010{"a":1}&&##00')
        self.assertEqual(len(frames), 2)
        self.assertEqual(rest, b"##00")


class Decode(unittest.TestCase):
    def status_frame(self, api, lead, state, temp, target, ml, kw=0):
        content = "%02X00%02X%04X%02X%02X%04X0000" % (lead, state, kw, temp, target, ml)
        pkt = P.build_packet(api, 0x2A, "0300", content).lower()
        return '{"wifi_cmd":"62","imei":"%s","data3":"%s","suc":"00","seq":"54545"}' % (IMEI, pkt)

    def test_status_36(self):
        f = P.parse_frame(self.status_frame("36", 0xC8, 2, 34, 100, 500))
        self.assertEqual(f["imei"], IMEI)
        st = P.parse_status(f["api"], f["content"])
        self.assertEqual((st["state"], st["temp_c"], st["target_c"], st["water_ml"]), ("Standby", 34, 100, 500))

    def test_status_a4_lead_byte_varies(self):
        f = P.parse_frame(self.status_frame("A4", 0x25, 2, 34, 100, 500))
        self.assertEqual(P.parse_status(f["api"], f["content"])["state"], "Standby")

    def test_keep_warm_countdown(self):
        f = P.parse_frame(self.status_frame("36", 0xC8, 5, 100, 100, 500, kw=299))
        st = P.parse_status(f["api"], f["content"])
        self.assertEqual((st["state"], st["keep_warm_s"]), ("Keep Warm", 299))

    def test_scan_reply(self):
        entries = [P.scan_entry("KettleNet", "0a1b2c3d4e5f", 1, 57), P.scan_entry("Neighbor", "020000000002", 1, 12)]
        content = "C8" + "%02X" % len(entries) + "".join(entries)
        nets = P.parse_scan(content)
        self.assertEqual([n["ssid"] for n in nets], ["KettleNet", "Neighbor"])
        self.assertEqual(nets[0]["bssid_text"], "0a1b2c3d4e5f")
        self.assertEqual(nets[0]["signal"], 57)
        self.assertEqual(nets[0]["entry_len"], 1 + 1 + 9 + 1 + 12 + 1 + 1)
        # round trip through a full packet
        pkt = P.build_packet("3D", 1, "0100", content)
        self.assertEqual(P.parse_scan(P.parse_packet(pkt)["content"]), nets)


if __name__ == "__main__":
    unittest.main(verbosity=2)
