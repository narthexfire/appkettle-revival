# Contributing

Thank you for your interest. Please note: **this project provides no support, and issues and pull requests may not be answered or merged.** Feel free to fork it.

If you do send something:

- **Never send 0x47 or 0x35** to a kettle, and never add code that can. `kettle_protocol.build_command()` and its tests must keep refusing them.
- Mark protocol facts as **confirmed** (tested on a kettle) or **untested**. Say which kettle and firmware you used, if you know.
- Use **synthetic data only** in tests, docs and screenshots:
  - IDs like `GD0-00000-0000`
  - addresses like `192.168.1.x`
  - network names like `KettleNet`

  Never commit real device IDs, MAC addresses (hardware network IDs), Wi-Fi names, passwords, captures or phone screenshots.
- Python: standard library only, Python 3.8+. Run `python -m unittest discover -s tests` in `tools/`.
- Android: `./gradlew testDebugUnitTest` must pass.
