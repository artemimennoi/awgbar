# AWG Bar for Android

A small AmneziaWG client that works like the macOS AWG Bar. One screen holds:
- the shield, which turns green when connected, with the server address, traffic and a Connect/Disconnect button;
- the tunnel list (tap to select; ••• renames or deletes) with import from the clipboard or a file;
- the "Bypass VPN" subnets.

There is also a quick settings tile, and the app works as the system always-on VPN. English and Russian.

The app imports the same things as on macOS: `vpn://…` keys, AmneziaVPN JSON (key, connection
export, backup) and plain `.conf` files. A `vpn://` link or a shared `.conf` opens straight in the app.
Keys that a tunnel cannot use (`PostUp`, `Table`, …) are dropped. The parameters of AWG 1.5/2.0
(`S3`, `S4`, `H1`–`H4` ranges, `I1`–`I5`, …) are passed to the tunnel unchanged.

"Bypass VPN" takes subnets like `10.*, 192.168.0.0/16, fd00::/8`. The app removes them from every
peer's AllowedIPs, so that traffic goes past the tunnel. Apply reconnects the active tunnel.

## Build

Requirements: JDK 17, the Android SDK (platform 36, build-tools 36), NDK 27.2.12479018 and CMake 3.22.1.
The Go toolchain for the tunnel library (1.25.14, with WireGuard's boottime patch) is downloaded
into `~/.gradle/caches/golang` on the first build.

```bash
brew install openjdk@17 android-commandlinetools
```

```bash
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;27.2.12479018" "cmake;3.22.1"
```

```bash
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew assembleDebug
```

The APKs are in `app/build/outputs/apk/debug/`, one per ABI plus `universal`. Unit tests run with
`./gradlew :app:testDebugUnitTest`.

Release builds are signed with your own key. Create `android/keystore.properties` (it is git-ignored):

```
storeFile=awgbar.keystore
storePassword=…
keyAlias=awgbar
keyPassword=…
```

Then `./gradlew assembleRelease`, and attach `app-arm64-v8a-release.apk` (and `universal`) to a GitHub Release.
Always sign with the same key: Android only installs an update over a build signed with the same key.

## Install (sideload, no Google Play)

- Download the APK from GitHub Releases on the phone, open it and allow "Install unknown apps" for
  the browser or file manager. Or use a cable: `adb install -r app-arm64-v8a-release.apk`.
- The debug build has its own id (`…awgbar.debug`) and installs next to the release build.

### Android developer verification

Google requires apps installed on certified Android devices (any phone with Google Play) to come
from a registered developer, including apps installed outside Play. It starts in Brazil, Indonesia,
Singapore and Thailand in September 2026 and is extended to other countries from 2027, so for
Kazakhstan it is a matter of time. What this means for a personal app:

- `adb install` from your computer is not blocked by verification. This is the most reliable
  route for an app only you use.
- For installs from a browser or file manager, register yourself in the Android Developer Console
  (no Google Play account needed; there is a free account type for students and hobbyists with a
  limited number of installs) and register the package name `io.github.artemimennoi.awgbar` with the
  certificate of your release key. Without that, a verifying phone refuses to install the APK.
- Google has also promised an "advanced flow" for experienced users to install unverified apps.
  Check the current terms in the [official FAQ](https://developer.android.com/developer-verification)
  before relying on it.

## Layout

- `app/` — the app: `KeyImport` (vpn:// / JSON / .conf), `TunnelConfig` (key allowlist),
  `Cidr` (bypass arithmetic), `Vpn` (the single backend), `Tile`, `MainActivity` (Compose UI).
- `tunnel/` — the `tunnel` module of [amneziawg-android](https://github.com/amnezia-vpn/amneziawg-android)
  at `ff15093`, Apache-2.0 (see `tunnel/COPYING`), with amneziawg-go v3.1 (MIT). It is cut down to the
  userspace Go backend: there is no root/kernel mode, and the Go version in the Makefile is updated.
