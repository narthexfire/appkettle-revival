# How it was figured out

This project exists so that kettles whose maker and cloud service are gone can still be used. The work was done for **interoperability** only.

## 1. Public prior work

Several open-source projects had already documented the kettle's local control protocol: a TCP connection on port 6002, `##`-framed JSON carrying an "AA" hex packet, and a keep-alive. See [NOTICE](../NOTICE) for the projects and thanks. Those projects covered switching the kettle on and off and reading its status. They did not cover putting a kettle onto a new Wi-Fi network.

## 2. Testing against a real kettle

The known commands were rebuilt from the published descriptions and tried, one at a time, on a kettle the author owns. Every command sent was logged with the kettle's reply. Each kettle behavior in [protocol.md](protocol.md) marked "Confirmed" was observed this way:

- the acks
- the A4 lead byte
- the boil-point rewrite
- keep warm being refused in Standby

## 3. Static analysis of the vendor app

The original Android app is no longer distributed by its maker. Old public builds remain on third-party APK archives. These builds were checked against each other (same signing certificate) and examined with **static analysis only**, for interoperability. They were never installed or run.

The analysis answered three questions:

- **How did pairing work?** The app used a vendor SDK's "smart-config" scheme, which codes the network name and password into multicast addresses and broadcast packet lengths. Its algorithm and timing are described in [protocol.md §10](protocol.md#10-wi-fi-pairing-smart-config-udp-15100).
- **What does 0x3E carry?** An access point's BSSID text, not the network name.
- **Which other commands exist?** This produced the untested-command list and the **never-send** list (0x47 firmware update, 0x35 serial-number write).

**No vendor code is reproduced in this project.** The protocol was re-implemented from the facts (addresses, lengths, timing, field layouts), and the re-implementation was checked with unit tests. Vendor credentials and keys found in the app are not published.

## 4. Proving the pairing method

The smart-config sender was first proven by pairing the kettle to a network it had never known (a phone hotspot with a test name).

Attempts through other kinds of network failed, although the sender's packets were identical (see [re-pairing.md, Troubleshooting](re-pairing.md#troubleshooting)):

- enterprise / managed access points
- consumer repeaters
- a computer-hosted hotspot

The "name the hotspot like the real network" method followed from one fact: the kettle stores only the network name and password. It was confirmed end to end, with the kettle controlled on the real network afterwards.

## 5. Limits

- All results come from **one kettle model** (and one physical kettle).
- Firmware differences between production batches are unknown.
- Statements marked "from the vendor app" have not been tested on hardware.
