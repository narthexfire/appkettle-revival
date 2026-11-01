# Kettle: the Android app for AppKettle smart kettles

An Android app to control your AppKettle kettle from your phone over your home Wi-Fi — no cloud, no account. It is a native app (Kotlin, Jetpack Compose) and talks to the kettle over your local network only.

- Android 8.0+ (API 26)
- No internet, no analytics, no account

**Features:**

- Boil, or heat to any temperature from 40 to 100 °C.
- Keep warm for 5–30 minutes.
- Six editable presets per kettle.
- Live temperature, water level and state, with a picture of the kettle.
- Several kettles.
- Automatic search when the kettle's address changes.
- Optional automatic joining of the kettle's own Wi-Fi.
- A "Kettle details" page you can copy for troubleshooting.

**Safety:**

- The app sends only:
  - WAKE 0x41
  - ON 0x39
  - OFF 0x3A
  - KEEP WARM 0x4A
- Searching and "Test connection" send only the keep-alive (and a UDP discovery probe).
- It never sends 0x47 (firmware update) or 0x35 (serial-number write).
- It warns before boiling below the 250 ml minimum.

## Installing

- **Recommended:** the signed APK from the project's **Releases** page; check its SHA-256 as described in the [main README](../README.md#get-the-app).
- Android asks you to allow **"Install unknown apps"** for the browser or file manager you open the APK with. **Google Play Protect** may warn about an app from an unknown developer; that's expected for sideloaded apps.
- The app appears on the phone as **Kettle** (package `io.github.narthexfire.kettle`).
- **Signing certificate SHA-256** of official releases: `8c6b76ffef32136a994ea1131c049245bb51677ab7f5f18f5fa1563cdce9dffc` (check with `apksigner verify --print-certs <apk>`; see the [main README](../README.md#get-the-app)).
- **Updating:** install the newer APK over the old one; settings are kept. Android only allows this when both are signed with the **same key**. If you switch between the release APK and a copy you built yourself (different key), uninstall first; this clears the app's settings.

## First run

1. Put the phone on the same Wi-Fi as the kettle. If the kettle isn't on any Wi-Fi yet, see [../docs/re-pairing.md](../docs/re-pairing.md).
2. Open the app (it's called **Kettle** on your phone). On the **Not set up** card, tap **Set up**, then **Search for kettle**. The app finds the kettle and saves its address and Kettle ID. (Later, the same screen is under ⋮ → **Settings** → your kettle.)
   - Or type the kettle's IP address and tap **Test connection**: the Kettle ID fills itself in. Then tap **Save address**.
3. Optional: rename the kettle in the same screen and tap **Save**. Settings → **Add another kettle** adds more.

Help, troubleshooting and the kettle's own buttons are explained in the app (⋮ → Help) and in [../docs/faq.md](../docs/faq.md).

## Building

You need:

- **JDK 17 or newer** (21 works)
- the **Android SDK** with platform 36 (install Android Studio, or the command-line tools)

```
cd android-app
# Tell Gradle where the SDK is, either with an environment variable:
#   ANDROID_HOME=/path/to/Android/Sdk
# or with a local.properties file containing:  sdk.dir=/path/to/Android/Sdk
./gradlew assembleDebug           # Windows: gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with `adb install app-debug.apk`, or copy it to the phone and open it (allow "install unknown apps").

**Tests** (JVM + Robolectric, no device needed):

```
./gradlew testDebugUnitTest
```

**Screenshots.** `ScreenshotTest` renders the app's screens from synthetic data into `android-app/docs/screenshots/` (git-ignored). The images in `../docs/images/` are copies of some of them, with all metadata stripped:

```
./gradlew recordRoborazziDebug --tests "*ScreenshotTest"
```

### Signed release build (optional)

Release builds are unsigned unless you provide your own key. Nothing secret is in this repository.

1. Create a keystore once, and keep it and its passwords safe:
   ```
   keytool -genkeypair -v -keystore my-release.jks -alias kettle -keyalg RSA -keysize 3072 -validity 10000
   ```
2. Create `android-app/keystore.properties` (git-ignored), or any file outside the repository passed with `-PkettleSigning=/path/to/file`:
   ```
   storeFile=/absolute/path/to/my-release.jks
   storePassword=...
   keyAlias=kettle
   keyPassword=...
   ```
3. Run `./gradlew assembleRelease`. The output is `app/build/outputs/apk/release/app-release.apk`.

If you change the signing key, Android refuses to update an installed copy. Uninstall it first; this clears the app's settings.

## Project layout

```
app/src/main/java/io/github/narthexfire/kettle/
  protocol/   frames, commands, status parser (pure Kotlin)
  net/        connection, search, test connection, local Wi-Fi join
  data/       settings (DataStore), presets, temperature and keep-warm rules
  ui/         Compose screens, theme, kettle illustration
app/src/test/ unit and Robolectric tests (synthetic IDs and addresses only)
```

The typeface is Manrope (SIL Open Font License 1.1). Its license is in `res/raw/manrope_ofl.txt` and shown in the app.
