# FAQ and troubleshooting

This project has no support. This page tries to answer everything first. Work down the list in order.

Not technical? An AI assistant can walk you through it step by step — see [Stuck? Ask an AI assistant](../README.md#stuck-ask-an-ai-assistant).

## Which situation am I in?

| Situation | What to do |
|---|---|
| The kettle's Wi-Fi light is steady and it worked with the old app on your current Wi-Fi | It is probably still on your network. Use **Search for kettle** in the app, or `python tools/kettle.py find` |
| The light is off and the display is dark | It's in **off mode** (20 minutes without Wi-Fi). Press **Power** on the base, then look again |
| The light flashes slowly or keeps blinking | It's looking for a network it can't find. **Re-pair it**: [re-pairing.md](re-pairing.md) |
| The light **flashes fast** by itself, or after you held the Wi-Fi button (about 5 seconds) | It's in **pairing mode**. Without a pairing sender it goes back to normal after about 50 seconds. If it keeps doing this, it has no usable network: **re-pair it** |
| You changed router, Wi-Fi name or password, or moved | **Re-pair it**: [re-pairing.md](re-pairing.md) |

**There is no "AP mode" on these kettles.** The original app's help mentioned a setup hotspot named `AK_…`; the kettles tested never create one, whatever button you hold. The phone-hotspot method in [re-pairing.md](re-pairing.md) is the way to give a kettle a new network.

## Step by step: why can't I reach my kettle?

1. **Look at the kettle.**
   - Display and Wi-Fi light dark → press **Power** on the base (off mode), wait a minute, go on.
   - Light flashing fast → pairing mode; wait 1 minute for it to settle.
2. **Is your phone/computer on the same Wi-Fi as the kettle?** Not a guest network, not cellular data, not a VPN to elsewhere.
3. **Search.** App: Settings → your kettle → **Search for kettle**. Computer: `python kettle.py find`. Try twice; the kettle dozes.
4. **Not found? Look in the router's client list** (its settings page; sometimes "Attached devices" or "DHCP clients") for a device whose MAC address (the device's hardware network ID) starts with **`00:26:29`**.
   - Listed → type its IP address into the app and tap **Test connection** (or `python kettle.py status --host <IP>`). If that fails, see step 6.
   - Not listed → the kettle is not on this network. **Re-pair it** ([re-pairing.md](re-pairing.md)).
5. **After re-pairing, still not on the network?** Check the network rules (2.4 GHz, WPA2-Personal, 8–32 letters and digits). If you used Method A, look in the **phone's hotspot connected-devices list** instead; the computer can't see the kettle there.
6. **Listed in the router but unreachable?** The router may isolate devices ("client isolation", "AP isolation", or a guest network). Turn that off, or put the phone on the same network as the kettle. Close other apps that might hold the kettle's single connection.

## The app

**Which phones?** Android 8.0 or newer. There is no iPhone version. On a computer, use the Python tools instead.

**How do I install it?** Download the signed APK from the project's **Releases** page, check its SHA-256 (see the [README](../README.md#get-the-app)), and open it on the phone. Android asks you to allow "Install unknown apps" for your browser or file manager, and Play Protect may warn that the app is unknown; that is normal for apps installed outside the Play Store. Only install APKs from the project's own Releases page. To build it yourself instead, see [android-app/README.md](../android-app/README.md).

**How do I update it?** Install the newer APK from the Releases page over the old one. Your settings are kept. This works only with APKs signed with the same key; if Android says the app "conflicts with an existing package", you built or installed a copy signed differently: uninstall it first (this clears the app's settings).

**How do I set it up?**

1. Put the phone on the same Wi-Fi as the kettle.
2. Open the app (it's called **Kettle** on your phone). On the **Not set up** card, tap **Set up**, then **Search for kettle**.

The app finds the kettle, saves its address and Kettle ID, and connects.

If you already know the kettle's IP address, type it in and tap **Test connection** instead: the Kettle ID fills itself in. Then tap **Save address**.

**"Can't search: connect the phone to the kettle's Wi-Fi network first"** The phone isn't on Wi-Fi, or it's on cellular data. Connect the phone to the kettle's Wi-Fi.

**"Searched the network; kettle not found"** Work through these:

1. Press **Power** on the base and search again. The kettle dozes and may need a few tries.
2. Check the phone and the kettle are on the **same** network. A guest network or a separate "IoT" network is a different network.
3. Look in your router's client list for a device whose MAC address starts with `00:26:29`.
   - If it's there, type its IP address into the app and tap Test connection.
   - If it's not there, the kettle isn't on your network: re-pair it.
4. Some routers stop devices from seeing each other ("client isolation", "AP isolation"). Turn that off for the kettle's network.

**More than one kettle?** Settings → **Add another kettle**. The search skips kettles that are already set up in the app.

**The address keeps changing.** Ask your router for a "DHCP reservation" for the kettle's MAC address. The app also searches again by itself when the kettle moves.

**Can I control it away from home?** Not directly. The app talks only on your local network. A VPN into your home network (for example your router's VPN feature) works, because the phone is then "local".

**Does the app send data anywhere?** No. It has no analytics and no accounts, and makes no internet connections. It talks only to kettles on your local network. Location permission is optional; Android requires it only to show the Wi-Fi name in Kettle details.

## The kettle

**Why does "Boil" stop below 100 °C?** At altitude, water boils at a lower temperature (about 95 °C at 1,500 m). The kettle detects the boil and stops. Afterwards it reports the temperature it boiled at as its target. That's normal, and the app learns it.

**Keep warm won't start.** Keep warm needs the water at **60 °C or above**. The kettle refuses it in **Standby**. Choose keep warm before boiling, or start it while the water is still hot (Ready).

**The water reading is wrong.** Recalibrate the scale:

1. Take the kettle off the base.
2. Hold **+** and **Keep Warm** on the base until it beeps (the display shows `000`).
3. Put the kettle back.

**It won't boil from the app.** It needs at least 250 ml of water (the MIN mark). The app and the tools warn below that.

**Can I factory-reset it?** No reset command or button sequence is known, and you don't need one. Re-pairing replaces the Wi-Fi settings.

**Will this update the firmware?** No. Firmware updates are impossible: the update server is gone. The tools and the app can't send the firmware-update command (0x47), and you shouldn't try.

## The Python tools

- **What do I need?** Python 3.8+. Run the commands from the `tools` folder.
- **`find` finds nothing.** Your computer must be on the same network as the kettle. Use `--subnet 192.168.1.0/24` (your own range) if the computer has several network adapters.
- **`Could not connect`.** Wrong address, kettle asleep (press Power), or a phone app is holding the only connection. Close the app and try again.
- **Firewall pop-up when pairing** (Windows: "allow access"; macOS: "accept incoming network connections"). The pairing sender also listens for a reply on UDP 15100. Allowing it or canceling both work.

## Reporting a problem

Issues may not be answered. If you open one, include:

- the output of the command (remove your Wi-Fi password)
- your kettle's state (lights, display)
- your router or access-point type

**Never post your Wi-Fi password or your kettle's full ID publicly.**
