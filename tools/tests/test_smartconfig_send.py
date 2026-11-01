"""Unit tests for the pure encoder in smartconfig_send.py.
Run from the tools folder:  python -m unittest discover -s tests
  or: python tests/test_smartconfig_send.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import smartconfig_send as S  # noqa: E402

PRE = ["239.%d.0.0" % i for i in range(6, 12)]


class Sum8(unittest.TestCase):
    def test_ascii(self):
        self.assertEqual(S.sum8(b"pw"), 231)
        self.assertEqual(S.sum8(b"AB"), 131)

    def test_wraps_last_two_hex_digits(self):
        self.assertEqual(S.sum8(b"oldpass123"), sum(b"oldpass123") & 0xFF)
        self.assertEqual(S.sum8(b"\x05"), 5)          # toHexString "5"
        self.assertEqual(S.sum8(b""), 0)

    def test_signed_bytes_negative_sum(self):
        # Java: -1 -> "ffffffff" -> "ff" -> 255
        self.assertEqual(S.sum8(b"\xff"), 255)
        self.assertEqual(S.sum8(b"\x80\x80"), 0)       # -256 -> ffffff00 -> 00


class DocExample(unittest.TestCase):
    """Worked example from docs/protocol.md: SSID "AB", password "pw"."""

    def test_length_list(self):
        expected = [40, 50] * 20 + [162] * 10 + [312, 319, 431] + [82] * 10 + [525, 526, 591]
        self.assertEqual(S.length_list("AB", "pw"), expected)

    def test_multicast_list(self):
        expected = (PRE + ["239.12.0.3", "239.12.1.65", "239.12.1.65",
                           "239.12.2.66", "239.12.2.66", "239.12.3.131"]
                    + PRE + ["239.14.0.3", "239.14.1.112", "239.14.1.112",
                             "239.14.2.119", "239.14.2.119", "239.14.3.231"])
        self.assertEqual(S.multicast_list("AB", "pw"), expected)

    def test_empty_password(self):
        L = S.length_list("AB", "")
        self.assertEqual(L[40:60], [160] * 20)
        self.assertEqual(L[60:], [82] * 10 + [525, 526, 591])
        M = S.multicast_list("AB", "")
        self.assertEqual(M[-8:], PRE + ["239.14.0.1", "239.14.1.0"])


class Target(unittest.TestCase):
    ssid, pw = "Kitchen-Kettle-01", "oldpass123"

    def test_lengths(self):
        L = S.length_list(self.ssid, self.pw)
        self.assertEqual(len(L), 40 + 10 + 10 + 1 + 10 + 17 + 1)
        self.assertEqual(L[40:50], [170] * 10)
        self.assertEqual(L[50:60], [b + 200 for b in b"oldpass123"])
        self.assertEqual(L[60], (sum(b"oldpass123") & 0xFF) + 200)
        self.assertEqual(L[61:71], [97] * 10)
        self.assertEqual(L[71:88], [b + 460 for b in b"Kitchen-Kettle-01"])
        self.assertEqual(L[88], (sum(b"Kitchen-Kettle-01") & 0xFF) + 460)

    def test_multicast(self):
        M = S.multicast_list(self.ssid, self.pw)
        self.assertEqual(len(M), 6 + 1 + 34 + 1 + 6 + 1 + 20 + 1)
        self.assertEqual(M[6], "239.12.0.18")
        self.assertEqual(M[7:9], ["239.12.1.75", "239.12.1.75"])   # "K"
        self.assertEqual(M[41], "239.12.18.%d" % (sum(b"Kitchen-Kettle-01") & 0xFF))
        self.assertEqual(M[48], "239.14.0.11")
        self.assertEqual(M[-1], "239.14.11.%d" % (sum(b"oldpass123") & 0xFF))


class Schedule(unittest.TestCase):
    def test_interleave_lengths_longer(self):
        sched = S.round_schedule(["a", "b"], [1, 2, 3, 4])
        self.assertEqual(sched, [(30, "M", "a"), (10, "B", 1), (0, "M", "b"), (10, "B", 2),
                                 (10, "B", 3), (10, "B", 4)])

    def test_interleave_mcast_longer(self):
        sched = S.round_schedule(["a", "b", "c"], [1])
        self.assertEqual(sched, [(30, "M", "a"), (10, "B", 1), (10, "M", "b"), (10, "M", "c")])

    def test_broadcast(self):
        self.assertEqual(S.subnet_broadcast("192.168.1.101", 24), "192.168.1.255")


class PasswordRules(unittest.TestCase):
    ARGS = ["--ssid", "KettleNet", "--iface-ip", "10.0.0.2", "--dry-run"]

    def run_main(self, pw, *extra):
        import contextlib, io
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return S.main(self.ARGS + ["--password", pw] + list(extra))

    def test_check_password(self):
        self.assertEqual(S.check_password("kettlepass1"), [])
        self.assertTrue(S.check_password("short1"))           # < 8
        self.assertTrue(S.check_password("a" * 33))           # > 32
        self.assertTrue(S.check_password("has space1"))       # not alphanumeric
        self.assertTrue(S.check_password("sym!bol123"))
        self.assertTrue(S.check_password("cafécafé"))  # not ASCII

    def test_invalid_password_refused_without_force(self):
        for bad in ("short1", "has space1", "a" * 33):
            with self.assertRaises(SystemExit) as cm:
                self.run_main(bad)
            self.assertEqual(cm.exception.code, 2)

    def test_force_sends_anyway(self):
        self.assertEqual(self.run_main("has space1", "--force"), 0)

    def test_valid_password_accepted(self):
        self.assertEqual(self.run_main("kettlepass1"), 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
