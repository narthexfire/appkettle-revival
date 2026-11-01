"""kettle.py against a fake kettle on 127.0.0.1 (synthetic ID). Checks the commands sent and the safety guards."""
import io
import json
import os
import socket
import sys
import threading
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import kettle  # noqa: E402
import kettle_protocol as P  # noqa: E402

IMEI = "GD0-00000-0000"


class FakeKettle(threading.Thread):
    """Accepts one connection: echoes the keep-alive, sends a status, acks every command with C8."""

    def __init__(self, state=2, temp=20, ml=900):
        super().__init__(daemon=True)
        self.srv = socket.socket()
        self.srv.bind(("127.0.0.1", 0))   # loopback only
        self.srv.listen(1)
        self.port = self.srv.getsockname()[1]
        self.state, self.temp, self.ml = state, temp, ml
        self.received = []

    def status(self):
        content = "C800%02X0000%02X64%04X0000" % (self.state, self.temp, self.ml)
        pkt = P.build_packet("36", 0x2A, "0300", content)
        body = json.dumps({"wifi_cmd": "62", "imei": IMEI, "data3": pkt.lower(), "suc": "00", "seq": "54545"})
        return ("##%04x%s&&" % (len(body), body)).encode()

    def run(self):
        c, _ = self.srv.accept()
        c.settimeout(5)
        buf = b""
        try:
            while True:
                d = c.recv(4096)
                if not d:
                    break
                buf += d
                frames, buf = P.split_frames(buf)
                for t in frames:
                    if "KeepConnect" in t:
                        c.sendall(P.KEEP_ALIVE + self.status())
                        continue
                    f = P.parse_frame(t)
                    self.received.append(f["api"])
                    ack = P.build_packet(f["api"], f["serial"], "0000", "C8")
                    body = json.dumps({"wifi_cmd": "62", "imei": IMEI, "data3": ack.lower(), "suc": "00", "seq": "00"})
                    c.sendall(("##%04x%s&&" % (len(body), body)).encode())
        except OSError:
            pass
        finally:
            c.close()
            self.srv.close()


def run_cli(args):
    out = io.StringIO()
    code = 0
    with redirect_stdout(out):
        try:
            kettle.main(args)
        except SystemExit as e:
            code = e.code if isinstance(e.code, int) else (0 if e.code is None else 1)
            if not isinstance(e.code, int) and e.code:
                print(e.code)
    return code, out.getvalue()


class Cli(unittest.TestCase):
    def test_status_reads_id_and_sends_no_command(self):
        k = FakeKettle(); k.start()
        code, out = run_cli(["status", "--host", "127.0.0.1", "--port", str(k.port)])
        k.join(3)
        self.assertIn(IMEI, out)
        self.assertIn("Standby, 20 C", out)
        self.assertEqual(k.received, [])

    def test_boil_sends_wake_then_on(self):
        k = FakeKettle(); k.start()
        code, out = run_cli(["boil", "--host", "127.0.0.1", "--port", str(k.port)])
        k.join(3)
        self.assertEqual(code, 0, out)
        self.assertEqual(k.received, ["41", "39"])

    def test_boil_temp_out_of_range_rejected_before_connecting(self):
        import contextlib
        for bad in ("120", "30", "abc"):
            with contextlib.redirect_stderr(io.StringIO()):
                code, out = run_cli(["boil", "--host", "127.0.0.1", "--port", "1", "--temp", bad])
            self.assertEqual(code, 2, bad)   # argparse error; no connection attempted, nothing sent

    def test_temp_type(self):
        self.assertEqual(kettle.temp_c("40"), 40)
        self.assertEqual(kettle.temp_c("100"), 100)

    def test_boil_refuses_low_water(self):
        k = FakeKettle(ml=100); k.start()
        code, out = run_cli(["boil", "--host", "127.0.0.1", "--port", str(k.port)])
        k.join(3)
        self.assertNotEqual(code, 0)
        self.assertEqual(k.received, [])

    def test_keep_warm_refused_in_standby(self):
        k = FakeKettle(state=2, temp=95); k.start()
        code, out = run_cli(["keep-warm", "--host", "127.0.0.1", "--port", str(k.port), "--minutes", "10"])
        k.join(3)
        self.assertIn("Standby", out)
        self.assertEqual(k.received, [])

    def test_keep_warm_on_hot_water(self):
        k = FakeKettle(state=3, temp=95); k.start()
        code, out = run_cli(["keep-warm", "--host", "127.0.0.1", "--port", str(k.port), "--minutes", "10"])
        k.join(3)
        self.assertEqual(k.received, ["4A"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
