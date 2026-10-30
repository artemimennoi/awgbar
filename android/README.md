# AWG Bar for Android

A small AmneziaWG client that works like the macOS AWG Bar. One screen holds:
- the shield, which turns green when connected, with the server address, traffic and a Connect/Disconnect button;
- the tunnel list (tap to select; ••• renames or deletes) with import from the clipboard or a file;
- the "Bypass VPN" subnets.

A QR code from AmneziaVPN can be scanned with the camera, including a series of several codes. The look follows Material 3 Expressive (Android 16): grouped rounded rows, bottom sheets, a graphite and green palette, and Material You colors as an option. You can turn on a status notification that stays up while connected and shows the tunnel, the traffic and a Disconnect button. There is also a quick settings tile, and the app works as the system always-on VPN. English and Russian.

The app imports the same things as on macOS: `vpn://…` keys, AmneziaVPN JSON (key, connection
export, backup) and plain `.conf` files. A `vpn://` link or a `.conf` shared from another app opens
in the app, which shows the server and adds it only when you confirm.
Keys that a tunnel cannot use (`PostUp`, `Table`, …) are dropped. The parameters of AWG 1.5/2.0
(`S3`, `S4`, `H1`–`H4` ranges, `I1`–`I5`, …) are passed to the tunnel unchanged.

"Bypass VPN" takes subnets like `10.*, 192.168.0.0/16, fd00::/8`. The app removes them from every
peer's AllowedIPs, so that traffic goes past the tunnel. Apply reconnects the active tunnel. The
tunnel's DNS servers stay in the tunnel, and a full-tunnel config keeps blocking the address family
it doesn't route (IPv6 for a v4-only config) with or without bypass subnets. A full-tunnel config
without `DNS` uses 1.1.1.1 and 1.0.0.1, as AmneziaVPN does.

Security notes: tunnels stay in app-private storage and are excluded from cloud backups and from
device-to-device transfer. The quick settings tile and the notification's Disconnect ask to unlock
the phone first, and the lock screen shows only that the VPN is on. For a kill switch outside the
tunnel's own lifetime, turn on Always-on VPN and "Block connections without VPN" for AWG Bar in
Android's VPN settings.

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

Release builds are signed with your own key. Gradle reads `android/keystore.properties` or
`~/.android/awgbar-release.properties`, neither of which is in git:

```
storeFile=/Users/you/.android/awgbar-release.jks
storePassword=…
keyAlias=awgbar
keyPassword=…
```

The version comes from the repository's `VERSION` file, shared with the macOS and Linux clients
(`versionCode` is x·10000 + y·100 + z). `make release` at the top level builds the APKs together with
the other clients' files for one GitHub Release `vX.Y.Z`.
Always sign with the same key: Android only installs an update over a build signed with the same key.
Back up the keystore together with its properties file. A release build is about 4 MB per ABI
(R8 strips unused code); a debug build is much larger because nothing is stripped.

## Install (sideload, no Google Play)

- Download `awgbar-android-<version>-arm64-v8a.apk` from the GitHub Release `v<version>` on the phone, open it and allow "Install unknown apps" for
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
