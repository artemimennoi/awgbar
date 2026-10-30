# AWG Bar

A small [AmneziaWG](https://docs.amnezia.org/documentation/amnezia-wg/) VPN client for macOS
(menu bar), Linux (tray) and Android. Paste an AmneziaVPN key (`vpn://…`) or open an AmneziaWG
`.conf`, press Connect. It shows the server and traffic, can send chosen subnets around the VPN,
and blocks traffic outside the tunnel while connected (kill switch).

It is in beta. Get it from [the latest release](https://github.com/artemimennoi/awgbar/releases);
to build it yourself, see [docs/building.md](docs/building.md).

## Download

All three clients are released together under one version: each release holds every file below.

| System | File | Needs |
|---|---|---|
| macOS | `awgbar-<version>-macos-arm64.dmg` | Apple Silicon, macOS 26+ |
| Ubuntu 22.04, 24.04, Debian 12 | `awgbar_<version>_qt5_<amd64\|arm64>.deb` | |
| Ubuntu 25.04+, Debian 13 | `awgbar_<version>_qt6_<amd64\|arm64>.deb` | |
| Android | `awgbar-android-<version>-arm64-v8a.apk` (`universal` if unsure) | Android 10+ |

The two Linux packages differ only in the Qt they use, so the app matches your desktop's style.

## Install

**macOS.** Open the DMG and drag AWG Bar to Applications. The app isn't notarized, so the first
launch is blocked: allow it in System Settings → Privacy & Security → **Open Anyway**, or run
`xattr -dr com.apple.quarantine "/Applications/AWG Bar.app"`. Click the shield in the menu bar
and press **Install Helper** (your password, once): the helper is what brings the tunnel up.

**Linux.** `sudo apt install ./awgbar_<version>_qt5_amd64.deb` (your file name). That's the only
password; AWG Bar appears in the applications menu and starts at login. On GNOME the tray icon
needs the AppIndicator extension (on by default in Ubuntu, `gnome-shell-extension-appindicator`
on Debian); without it the window is the app.

**Android.** Open the APK on the phone and allow installing from that browser or file manager, or
`adb install -r <file>.apk` from a computer. On phones with Google Play, Google is phasing in
[developer verification](https://developer.android.com/developer-verification) for apps from
outside Play (from 2026–2027 depending on the country); `adb install` keeps working regardless.

## Use

- **Add a tunnel**: **From Clipboard** with a `vpn://` key copied, or **From File…** for a `.conf`,
  a file with a key, or an AmneziaVPN JSON export or backup. On Android you can also scan
  AmneziaVPN's QR codes, or open a `vpn://` link or a shared file: the app shows what it got and
  adds it when you confirm. Amnezia Premium/Free keys can't be imported (their config comes from
  Amnezia's API at connect time).
- **Connect** with the power button. One tunnel is up at a time; switch tunnels while
  disconnected. On macOS and Linux quitting the app disconnects.
- **Bypass**: subnets that go through your normal network instead, like
  `10.*, 192.168.0.0/16, fd00::/8`. On macOS and Linux local networks stay reachable anyway; on
  Android add them here to reach a printer or a NAS.
- **Kill switch**: while connected with a config that sends all traffic into the tunnel, nothing
  else gets out, apart from local networks and Bypass subnets. If the tunnel breaks, traffic
  stays blocked until it comes back or you disconnect. Other VPNs (Tailscale and the like) can't
  reach their servers meanwhile. On Android turn on Always-on VPN and "Block connections without
  VPN" for AWG Bar in the system VPN settings to block traffic even while disconnected.
- **Debug Log** in the settings menu writes a root-only log on macOS and Linux; it's off and
  deleted by default.
- Android also has a quick settings tile and an optional status notification with Disconnect.
  The UI is in English and Russian.

## Update

- **macOS**: settings menu → **Check for Updates…**, then **Update to x.y.z**. The update is
  checked against the release's signature before it replaces the app; the VPN stays connected.
  When the helper changes too, the app asks for your password once to reinstall it.
- **Linux**: install the new package over the old one; the VPN reconnects by itself.
- **Android**: install the new APK over the old one.

## Remove

- **macOS**: `sudo bash "/Applications/AWG Bar.app/Contents/Resources/uninstall-helper.sh"`
  (add `--purge` to delete the tunnels too), then move the app to the Trash.
- **Linux**: `sudo apt remove awgbar` keeps the tunnels, `sudo apt purge awgbar` deletes them.
- **Android**: uninstall the app.

## More

- [How it works](docs/how-it-works.md): the helpers, the kill switch, DNS, the security model and
  known limitations.
- [Building and releasing](docs/building.md): building each client from source, tests, CI and
  how a release is made and signed.
- License: MIT (`LICENSE`); bundled components keep theirs (`THIRD_PARTY_NOTICES.md`).
