# AppKettle local protocol

This is how an AppKettle Wi-Fi kettle talks on a local network. It is enough to write your own client. The tools in [`../tools/`](../tools/) and the Android app in [`../android-app/`](../android-app/) implement it.

Each fact has a status:

- **Confirmed**: seen working on a real kettle.
- **From the vendor app**: found by static analysis of the original Android app. Not tested on a kettle.
- **Never send**: commands that could damage the kettle.

Contents: 1. Discovery · 2. Connection · 3. Frames · 4. The AA packet · 5. Commands · 6. Status ·
7. Scan reply · 8. Acks · 9. Behavior · 10. Wi-Fi pairing (smart-config) · 11. Untested and forbidden commands · 12. The vendor cloud

---

## 1. Discovery (UDP 15103)

- The vendor app broadcasts `Probe#YYYY-MM-DD-HH-MM-SS-W` once a second to the subnet broadcast address on **UDP port 15103**. W is the day of the week (0 = Sunday); other clients send `-2`.
- The kettle should answer with `deviceId#key#mcutype#version#mcuVersion#data#{JSON}`, where the device ID starts with `GD`. The reply's source address is the kettle's IP.
- **Not confirmed.** No reply was seen in testing (a PC firewall may have blocked it). Don't rely on it. The tools and the app find kettles reliably with a **TCP sweep** instead: connect to port 6002 on each address, send only the keep-alive, and read the `imei` from the first status frame.

## 2. Connection (TCP 6002) — Confirmed

- One TCP connection to **port 6002**. No other port is open.
- When you connect, send the keep-alive `##000bKeepConnect&&`. Send it again every 25–30 s. The kettle echoes it back.
- The kettle sends an `0xA4` status push when you connect. After that it sends `0x36` status frames whenever something changes, at least every ~15 s, and about once a second while heating.
- **Power-save.** The kettle dozes. Ping replies take ~80–100 ms and idle links get dropped. Retry a connect for up to 30 s (every 2 s) before deciding the kettle isn't there. A quick ping sweep often misses it.
- The kettle may serve only one client at a time. While a phone holds a connection, a PC may not get one.

## 3. Frames — Confirmed

```
##LLLL{json}&&
```

`LLLL` is the JSON body's length in bytes, as 4 hex digits. For example, `##005F{...}&&` is a 95-byte body. Upper-case hex works. The kettle itself sends lower case.

App → kettle:

```json
{"app_cmd":"62","imei":"GD0-00000-0000","SubDev":"","data2":"<AA packet, upper-case hex>"}
```

Kettle → app:

```json
{"wifi_cmd":"62","imei":"GD0-00000-0000","data3":"<AA packet, hex>","suc":"00","seq":"54545"}
```

- `imei` is the kettle's ID, e.g. `GD0-xxxxx-xxxx`. Every frame the kettle sends contains it, so a client can learn it from the first frame.
- `seq` is `"00"` on command replies.
- An encrypted variant of the framing exists in the vendor app (`##8` + 3 hex digits + AES data). Every kettle seen so far uses plain frames, so the encrypted variant isn't documented here.

## 4. The AA packet — Confirmed

| Field | Bytes | Notes |
|---|---|---|
| head | 1 | `AA` |
| length | 2 | Big-endian. Counts the bytes after this field, **including** the checksum |
| prefix | 2 | `0000` for normal commands. `0100` for Wi-Fi/setup commands (0x3D, 0x3E). The kettle sends `0300` on 0x36 and `0100` on 0xA4 |
| msgUnique | 6 | zeros |
| serial | 1 | Message counter. The kettle echoes it in its reply |
| api | 1 | command code |
| retain | 2 | `0000` |
| content | n | payload |
| checksum | 1 | see below |

**Stuffing.** After the head byte, every `AA` byte is sent as `AA 55`. This applies to the length, the body and the checksum. When receiving, drop any `55` that directly follows an `AA`.

**Checksum.** Add up all bytes of the *stuffed* length + body, keep the low 8 bits, and XOR with `FF`.

Worked example (WAKE, serial 1): `AA 000D 0000 000000000000 01 41 0000` → 0x0D + 0x01 + 0x41 = 0x4F → checksum `B0` → `AA000D000000000000000001410000B0`.

```python
def checksum(h):                      # h = stuffed length+body as hex
    return format((sum(int(h[i:i+2], 16) for i in range(0, len(h), 2)) ^ 0xFF) & 0xFF, "02X")

def packet(api, serial, prefix="0000", content=""):
    msg = prefix + "000000000000" + format(serial, "02X") + api + "0000" + content
    st = stuff(format(len(msg)//2 + 1, "04X") + msg)
    return "AA" + st + stuff(checksum(st))
```

Quirk: the A4 push can declare a length that doesn't match its real size. Don't trust the length field on A4. Parse by position instead.

## 5. Commands

| Code | Name | Prefix | Content | Status |
|---|---|---|---|---|
| `0x41` | WAKE | 0000 | none | **Confirmed.** Send before ON unless the kettle is already Heating or in Keep Warm |
| `0x39` | ON (start heating) | 0000 | 5 bytes: `boilType target keepWarmMin brew delay` | **Confirmed** (100 °C with keep warm 0 and 5) |
| `0x3A` | OFF | 0000 | none | **Confirmed.** Stops heating and also cancels keep warm |
| `0x4A` | set keep warm | 0000 | 1 byte: minutes, 0 = off | **Confirmed** after a boil. **Refused in Standby** (ack `00`) |
| `0x3D` | Wi-Fi scan | **0100** | none | **Confirmed**, also over the normal LAN connection |
| `0x3E` | join Wi-Fi by BSSID | 0100 | see below | Acked `C8` once, but the kettle never joined the target. See [re-pairing.md](re-pairing.md#method-c-last-resort-0x3e-over-an-existing-connection) |

### ON payload

`[boilType, target °C, keepWarmMin, brew, delay]`, all 1 byte:

- `00 64 00 00 00`: boil (100 °C), no keep warm
- `00 64 05 00 00`: boil, then keep warm for 5 minutes
- `00 50 00 00 00`: heat to 80 °C

Notes:

- Targets run from 40 to 100. The vendor app's keep-warm choices were 0, 5, 10, 15, 20 and 30 minutes.
- `boilType`, `brew` and `delay` were always 0 in testing.

Example frames (synthetic ID):

```
WAKE  ##005F{"app_cmd":"62","imei":"GD0-00000-0000","SubDev":"","data2":"AA000D000000000000000001410000B0"}&&
ON    ##0069{"app_cmd":"62","imei":"GD0-00000-0000","SubDev":"","data2":"AA001200000000000000000239000000640000004E"}&&
ON+kw ##0069{"app_cmd":"62","imei":"GD0-00000-0000","SubDev":"","data2":"AA0012000000000000000002390000006405000049"}&&
OFF   ##005F{"app_cmd":"62","imei":"GD0-00000-0000","SubDev":"","data2":"AA000D0000000000000000013A0000B7"}&&
KW 5  ##0061{"app_cmd":"62","imei":"GD0-00000-0000","SubDev":"","data2":"AA000E0000000000000000014A000005A1"}&&
SCAN  ##005F{"app_cmd":"62","imei":"GD0-00000-0000","SubDev":"","data2":"AA000D0100000000000000013D0000B3"}&&
```

`python tools/kettle.py frames` prints these.

### Keep-warm rules — Confirmed

- The kettle only keeps water warm at **60 °C or above**.
- To keep warm after a boil, put `keepWarmMin` in the ON payload. The kettle goes straight from Heating to Keep Warm and holds the boil temperature.
- To start keep warm on water that is already hot (state Ready, Heating or Keep Warm, water ≥ 60 °C), send `0x4A` with the minutes. Don't send WAKE first. The vendor app sent the minutes in lower-case hex. Upper case works.
- **In Standby (state 2) the kettle refuses `0x4A`** (ack `00`). The vendor app hides keep warm in Standby and lets the user choose it for the next boil instead.
- To stop keep warm, send OFF `0x3A` while in Keep Warm (proven). The vendor app sends `0x4A` with 0 while heating.

### 0x3E (join a network by BSSID)

Content is `len + BSSID-text + len + password`:

- `len` is 1 byte.
- `BSSID-text` is the access point's MAC address (the device's hardware network ID) as **12 ASCII hex characters**, exactly as the scan reported it (for example `0a1b2c3d4e5f`).
- `password` is ASCII.

Example: BSSID text `0a1b2c3d4e5f`, password `example1`, serial 1:

```
AA 0023 0100 000000000000 01 3E 0000 0C 306131623263336434653566 08 6578616D706C6531 <checksum>
```

Rules:

- **Send a fresh 0x3D scan first** and take the BSSID text from that reply. The kettle only joins an access point it can currently see, and the original app always scanned immediately before sending 0x3E.
- Copy the BSSID text **exactly as scanned** (same characters, same case, same length byte). Don't type it in or reformat it as `aa:bb:cc…`.
- The kettle closes the connection after acking 0x3E.

The kettle stores **one access point's BSSID**, not the network name. A kettle moved this way is tied to that single access point. Smart-config (§10) stores the name instead, so prefer it.

## 6. Status (0x36 and 0xA4) — Confirmed

Content is 11 bytes:

| Byte | Meaning | Example |
|---|---|---|
| 0 | `C8` on 0x36. **Varies on 0xA4**, so skip it there | `C8` |
| 1 | boil mode (vendor app: 0 normal, 1 favorite, 2/3 schedule, 4 baby bottle) | `00` |
| 2 | state | `02` |
| 3–4 | keep-warm seconds left, big-endian | `012C` = 300 |
| 5 | water temperature °C | `22` = 34 |
| 6 | target °C | `64` = 100 |
| 7–8 | water volume ml, big-endian | `01F4` = 500 |
| 9 | brew (matches ON byte 4) | `00` |
| 10 | delay (matches ON byte 5) | `00` |

States: `0` not on base · `1` unknown · `2` Standby · `3` Ready · `4` Heating · `5` Keep Warm.

- The base weighs the water to about ±30 ml. Show volume as approximate.
- A client that only strips a leading `C8` will misread A4 frames as "not on base". Skip byte 0 instead.
- °C/°F is a display setting only. The kettle always works in °C.
- The status can also be requested by sending `0x36` with no content. The vendor app does this when the kettle goes quiet (not tested).

Decoded examples:

```
C80004012C5A6401F40000   Heating, keep warm 300 s, 90 °C, target 100, 500 ml
C80005012B646401F40000   Keep Warm, 299 s left, 100 °C, target 100, 500 ml
C800030000646401F40000   Ready, 100 °C, target 100, 500 ml (after OFF)
```

At altitude the target byte changes after a boil: the kettle reports the temperature it actually boiled at (for example `5F` = 95 at about 1,500 m).

## 7. Scan reply (0x3D) — Confirmed

Content: `C8`, a count byte, then entries of:

```
[entryLen][ssidLen][SSID][bssidLen][BSSID as 12 ASCII hex chars][security][signal]
```

`entryLen` counts itself.

- `security` was `01` for every WPA2 network seen.
- `signal` looks like a 0–100 quality figure, not dBm.

## 8. Acks — Confirmed

A reply has the same `api` code and `serial` as the command it answers. Content byte 0 is the result: `C8` = accepted, `00` = refused.

## 9. Behavior — Confirmed

- **Temperature lag.** After ON, the state changes to Heating within 1 s, but the temperature reading can stay the same for ~25 s.
- **Boil detection and altitude.** The kettle detects the boil itself and switches off (to Ready). At altitude water boils below 100 °C (about 95 °C at 1,500 m). When that happens, the kettle **rewrites its target from 100 to the temperature it boiled at**. A client can use this to learn the local boil point. Treat any target of 90 or more as "boil" and send 100: the kettle stops at the real boil anyway.
- **Keep warm** counts down in bytes 3–4. OFF cancels it.
- **Off mode.** After about 20 minutes without Wi-Fi, the kettle turns its display and Wi-Fi light off. Press Power on the base (or unplug and re-plug it) to wake it.
- **Minimum water** is 250 ml and **capacity** is 1.7 L. Refuse to boil below the minimum.

## 10. Wi-Fi pairing: smart-config (UDP 15100)

This isn't part of the TCP protocol. It is how a kettle is told which network to join. While the kettle is in pairing mode (fast-flashing Wi-Fi light; see [re-pairing.md](re-pairing.md)), a sender on the same Wi-Fi transmits the network name (SSID) and password in two ways at once. Both are re-implemented in [`tools/smartconfig_send.py`](../tools/smartconfig_send.py).

**A. Multicast addresses.** 24-byte packets (`"1"` × 24) go to port 15100 with TTL 1. The data is in the destination address:

```
239.6.0.0 … 239.11.0.0                          preamble
239.12.0.(ssidLen+1)                            SSID length
239.12.(i+1).(ssid[i])   ×2 per byte            SSID bytes, each twice
239.12.(ssidLen+1).(sum8(ssid))                 SSID checksum
239.6.0.0 … 239.11.0.0                          preamble
239.14.0.(pwLen+1)  …  239.14.(pwLen+1).(sum8)  the same for the password
```

**B. Broadcast lengths.** Packets of `"1"` × N go to the subnet broadcast address, port 15100, from source port 38000. The data is in the length N:

```
40,50 ×20                                       preamble
(pwLen+160) ×10, pw[i]+200 …, sum8(pw)+200      password first
(ssidLen+80) ×10, ssid[i]+460 …, sum8(ssid)+460 then the SSID
```

An empty password is sent as 160 ×20.

`sum8` is the sum of the bytes taken as *signed* bytes, written in hex, keeping the last two hex digits (for ASCII text this equals `sum & 0xFF`).

**Timing.** Each round:

1. Wait 30 ms.
2. For each i: send multicast[i], wait 10 ms, send length[i].
3. Send the rest of the longer list, 10 ms apart.

Repeat the rounds. The vendor app sent for ~45 s. **150 s works better in practice.**

Worked example, SSID `AB` and password `pw`:

```
list B = 40,50 ×20, 162 ×10, 312, 319, 431, 82 ×10, 525, 526, 591
```

**Reply.** The kettle is supposed to send `RMTConfigSuccess#<id>#…` to UDP 15100, and the app answers `RMTSMARTCONFIGACK` ×20. **No reply was ever seen, even when pairing worked.** Check success on the network instead.

Smart-config stores the **SSID and password**. The kettle then joins any access point with that name and password, which is what makes the "name your phone hotspot like your network" method work.

**It only works through some access points.** It worked every time through an Android phone hotspot. It failed through an enterprise access-point system, a router in repeater mode, and a PC-hosted Windows hotspot. See [re-pairing.md](re-pairing.md#troubleshooting).

## 11. Untested and forbidden commands (from the vendor app)

None of these has been sent to a real kettle unless stated. All use the same AA packet, and `C8` means OK.

| Code | Name | Prefix | Content | Notes |
|---|---|---|---|---|
| 0x31 | heartbeat | 0100 | none | sent periodically by the vendor app |
| 0x33 | device info | 0000 | none | Reply: unique code, upstream Wi-Fi name, MCU version, Wi-Fi version, power. Read-only; the safest first experiment |
| 0x36 | request status | 0000 | none | |
| 0x3B / 0x3C | start / stop keep warm (old) | 0000 | none | superseded by 0x4A |
| 0x42 | standby | 0000 | none | the vendor app sends it when Power is tapped in Ready |
| 0x43 / 0x44 | read / write schedule | 0000 | type, temp, start (3 bytes, seconds), boil time (2), brew, delay, keep warm, name, user id | type 0 = cancel, 2/3 = schedule, 4 = baby bottle (70 °C) |
| 0x45 | rename kettle (its setup-hotspot name `AK_…`) | 0100 | `04`, len, ASCII | changes stored data; avoid |
| 0x48 / 0x49 | Wi-Fi / MCU update status | 0100 / 0000 | none | read-only |

### NEVER SEND

| Code | Name | Why |
|---|---|---|
| **0x47** | start MCU firmware update | The update server is gone. This could leave the kettle unusable |
| **0x35** | write unique code / serial number | A factory tool. It could change the ID that clients rely on |

Also avoid:

- 0x46 (set Wi-Fi mode): meaning unknown
- 0x3F, 0x52/0x53: purpose unknown
- 0x3E, except as described in [re-pairing.md](re-pairing.md#method-c-last-resort-0x3e-over-an-existing-connection)

No factory-reset command exists. The tools in this project **cannot** build 0x47 or 0x35: `kettle_protocol.build_command()` refuses them and the tests check this.

## 12. The vendor cloud

The kettle also tries to reach `query.jingxuncloud.com` (last seen at 47.90.212.28). That service no longer answers (its ports are closed), so the cloud is dead. Local control needs no internet. You may block the kettle's internet access if you like.
