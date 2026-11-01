# Security

This project is maintained on a best-effort basis, with **no support and no response-time commitment**.

**Things to know:**

- The kettle's local protocol has **no authentication or encryption**. Anyone on the same network can control the kettle. Keep the kettle on a network you trust, ideally a separate network for IoT devices. Never expose TCP port 6002 to the internet.
- The app stores the kettle network's password (if you enter one) in app-private storage on the phone, excluded from backups.
- The tools never write Wi-Fi passwords to logs.

**Reporting a vulnerability.** If you find a problem in this project's code (for example, a way to make it send a command it shouldn't), please use the repository's **private vulnerability reporting** if it's enabled. Otherwise open an issue without exploit details. Reports may not be answered. Problems in the kettle's own firmware can't be fixed here.
