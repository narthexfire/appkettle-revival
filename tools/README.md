# Python tools

Requirements:

- **Python 3.8 or newer**, standard library only. Nothing else to install.
- Run the commands from this folder.
- Every script has `--help`.

**First time with Python?**

1. Get this project: on the project page use **Code → Download ZIP** (GitHub) or the download button (Codeberg), and unzip it, for example to your Desktop.
2. Install Python 3 from [python.org/downloads](https://www.python.org/downloads/).
   - **Windows:** check **"Add python.exe to PATH"** in the installer. If you didn't, type `py` instead of `python` below.
   - **macOS / Linux:** type `python3` instead of `python` below.
3. Open a terminal (Windows: Start → `cmd`; macOS: Applications → Utilities → Terminal), type `cd ` followed by a space, drag the unzipped `tools` folder onto the window, and press Enter.
4. Try `python kettle.py --help`.

The re-pairing guide explains how to find your computer's IP address on a hotspot ([../docs/re-pairing.md#python](../docs/re-pairing.md#setting-up-python-first-time-only)).

| File | What it does |
|---|---|
| `kettle.py` | Find, read and control a kettle on your network |
| `smartconfig_send.py` | Pair a kettle with a Wi-Fi network (see [../docs/re-pairing.md](../docs/re-pairing.md)) |
| `set_wifi_3e.py` | Last resort: move a reachable kettle to one access point with 0x3E. Read its `--help` and the re-pairing guide first |
| `kettle_protocol.py` | The encoder and decoder used by the scripts (no network access) |
| `tests/` | Offline tests: `python -m unittest discover -s tests` |

## kettle.py

```
python kettle.py find                                  # read-only: sweeps this PC's /24 on TCP 6002
python kettle.py find --subnet 192.168.1.0/24
python kettle.py status --host 192.168.1.50            # read-only
python kettle.py watch  --host 192.168.1.50 --seconds 300
python kettle.py scan   --host 192.168.1.50            # read-only: networks the kettle can see
python kettle.py boil   --host 192.168.1.50            # boil
python kettle.py boil   --host 192.168.1.50 --temp 80 --keep-warm 10
python kettle.py keep-warm --host 192.168.1.50 --minutes 10   # water must be >= 60 C, not in Standby
python kettle.py off    --host 192.168.1.50
python kettle.py frames --imei GD0-00000-0000          # print example frames; sends nothing
```

You don't need the kettle's ID: the tool reads it from the kettle's first message.

### Guards

- **`boil`** refuses when the kettle is off its base. It also refuses below 250 ml of water, unless you add `--allow-low-water`.
- **`keep-warm`** follows the kettle's own rules:
  - It needs water at 60 °C or above.
  - It is refused in Standby.

## smartconfig_send.py

```
python smartconfig_send.py --ssid "My Network" --iface-ip <this PC's IP on the phone hotspot> --duration 150
python smartconfig_send.py --ssid "My Network" --password "abc12345" --iface-ip 10.0.0.2 --dry-run
```

- The password is prompted for, or read with `--password-env VAR`. It is never logged.
- A password the kettle can't use (not 8–32 letters and digits) is **refused**; add `--force` to send it anyway.
- Exit code 2 with "no RMTConfigSuccess reply" is **normal**: kettles don't send that reply even when pairing works. Check the network instead.
- `--log FILE` also appends the output to a file.

## Safety

- The scripts can send only these commands:
  - WAKE 0x41, ON 0x39, OFF 0x3A, KEEP WARM 0x4A and SCAN 0x3D (`kettle.py`)
  - 0x3E, only after you type YES (`set_wifi_3e.py`)
- `kettle_protocol.build_command()` refuses everything else, including **0x47** (firmware update) and **0x35** (serial-number write). The tests check this.
- `find`, `status`, `watch` and `scan` send only the keep-alive (and, for `scan`, 0x3D).

## Windows notes

- Use `python` or `py`, whichever your installation provides.
- The pairing sender listens on UDP 15100 for a reply, so Windows Firewall may ask the first time. Either answer is fine.
- A **Windows Mobile Hotspot does not work for pairing**. Use an Android phone hotspot (see the re-pairing guide).
