# AWG Bar

A small native macOS menu bar client for [AmneziaWG](https://docs.amnezia.org/documentation/amnezia-wg/).
It lives only in the menu bar: no Dock icon, no main window. Imports AmneziaVPN keys (`vpn://…`)
and plain AmneziaWG `.conf` files, connects with one button, shows the server and traffic, and can
send chosen subnets around the VPN.

There is also a Linux version for Ubuntu 22.04+ and Debian 12+, see [linux/README.md](linux/README.md).
The Android app (Android 10+, APK from GitHub Releases) is in [android/README.md](android/README.md).
All three share one version number and are released together: each GitHub release `vX.Y.Z` holds
the macOS DMG, the Linux packages and the Android APKs.

No paid Apple Developer account is needed: instead of a Network Extension it uses a small root
helper (a LaunchDaemon) that runs `amneziawg-go` and `awg-quick`.

## Install from a release

AWG Bar is in beta: releases are published as pre-releases.

Apple Silicon, macOS 26 or later. Download `awgbar-<version>-macos-arm64.dmg` from
[Releases](https://github.com/artemimennoi/awgbar/releases), open it and drag `AWG Bar` to
`Applications`. The app is ad-hoc signed, not notarized, so macOS blocks the first launch: allow it
in System Settings → Privacy & Security → **Open Anyway**, or clear the quarantine flag once:

```bash
xattr -dr com.apple.quarantine "/Applications/AWG Bar.app"
```

## Updates

Settings menu → **Check for Updates…** looks at this repository's releases (betas included); the
app never checks on its own. If there is a newer version, **Update to x.y.z** appears in the
window: it downloads the DMG, checks the release's `SHA256SUMS` against its Ed25519 signature
(`SHA256SUMS.sig`, whose public key is built into the app), checks the DMG against `SHA256SUMS`,
replaces the app in place and relaunches it, keeping the VPN connected. The app must be in a
folder you can write to, such as `/Applications`. Without a valid signature nothing is installed,
so a release swapped on GitHub without the signing key is refused. If the update bumps the helper,
the app asks to reinstall it (your password once).

## Build from source

Requirements: macOS 26 or later, Xcode Command Line Tools (Swift), Go, git, curl.

```bash
make install
```

This builds `amneziawg-go`, `awg`, `awg-quick` and a self-contained `bash` from pinned sources,
assembles `build.noindex/AWG Bar.app` and copies it to `/Applications`. Open it, click the shield in the
menu bar and press **Install Helper**: macOS asks for your password once.

The app icon is an Icon Composer file, `Resources/AppIcon.icon`, whose layers come from
`Resources/make-icon.py`. Its compiled form is committed, so the build doesn't need Xcode; after
changing the icon, run `make icon` with Xcode installed to regenerate it.

Remove the helper and the app (tunnel configs are kept unless you run the uninstall script with `--purge`):

```bash
make uninstall
```

## How it works

```
AWG Bar.app (SwiftUI MenuBarExtra, runs as you)
    │  JSON over /var/run/awgbar.sock, peer uid checked
    ▼
local.awgbar.helper (LaunchDaemon, root)
    │  bash awg-quick up|down /Library/Application Support/AWGBar/run/<tunnel>.conf
    ▼
amneziawg-go (utun) + routes + DNS
```

- Tunnel configs are stored in `/Library/Application Support/AWGBar/tunnels` (root, 0600) and are
  excluded from Time Machine: they hold private keys in the clear.
- Only one tunnel is up at a time. Quitting the app disconnects the VPN.
- **Split tunnelling** («Bypass VPN»): listed subnets (`10.*`, `192.168.0.0/16`, `fd00::/8`, …) are
  routed through your normal gateway. They reuse awg-quick's endpoint routes, so they follow
  network changes and are removed on disconnect. Requires a full-tunnel config (`0.0.0.0/0`).
- **Kill switch**: while a full-tunnel config (`0.0.0.0/0` or `::/0`) is connected, a pf anchor
  (`com.apple/AWGBar`, loaded without touching `/etc/pf.conf`) lets out only the tunnel, the
  server, local networks, the Bypass VPN subnets and DHCP. That stops a hostile DHCP server pushing
  routes around the VPN (option 121, "TunnelVision"), IPv6 leaking past a v4-only config, and the
  traffic leaking if `amneziawg-go` dies: then the menu says "Connection lost · traffic blocked"
  until you disconnect. The rules go away on disconnect and on quit.
- **DNS**: a full-tunnel config without `DNS` uses 1.1.1.1 and 1.0.0.1 through the tunnel, as
  AmneziaVPN does, instead of the router's. The tunnel's name servers are kept in the tunnel even
  when a Bypass VPN subnet covers them (AmneziaDNS 172.29.172.254 inside `172.16.0.0/12`).
- **IPv6 leak protection**: if a config sends `::/0` into the tunnel but gives it no IPv6 address,
  IPv6 is blackholed while connected instead of silently bypassing the VPN.
- English and Russian UI, switchable in the settings menu (English by default).

## Security model

- The helper accepts requests only from root and from the user who installed it. It never
  replaces a tunnel on import (a new name is required), so nothing can swap the server behind a
  name you trust.
- awg-quick runs `PreUp`/`PostUp`/`PreDown`/`PostDown` through `eval` as root. AWG Bar never passes
  a config to it as written: every config is parsed against an allowlist of keys known to awg and
  awg-quick, values are validated, and a canonical copy is rendered before each connect. Hooks,
  `Table` and `SaveConfig` are dropped.
- Everything that runs as root afterwards is copied into root-owned locations at install time.
  The install script itself runs once from the app bundle, with your password, like any admin
  prompt: only press Install Helper for an app you got from the releases.
- **No logs by default.** The helper writes nothing unless **Debug Log** is enabled in the settings
  menu; it then logs to `/var/log/awgbar-helper.log` (root only, 0600), which is deleted when the
  option is turned off. Private keys are never logged.

Debugging aid, shows exactly what awg-quick gets:

```bash
sudo /Library/PrivilegedHelperTools/local.awgbar.helper --print-config <tunnel>
```

## Known limitations

- AWG Bar and the AmneziaVPN app share `/var/run/amneziawg`; don't run both tunnels at once.
- If the Mac loses power while connected, DNS servers set by awg-quick stay in the network
  settings until the next connect/disconnect (same as upstream wg-quick).
- While connected, other VPNs (Tailscale and the like) can't reach their servers: the kill switch
  lets only AWG Bar's tunnel out. Add their networks to Bypass VPN if you need them.
- Amnezia Premium/Free keys can't be imported: their config is issued by Amnezia's API at connect time.

## Releases

`VERSION` holds the one version all clients use. `make release` builds the DMG, the four Linux
packages and the Android APKs into `build.noindex/release` and writes `SHA256SUMS` with its
signature; publish them as release `v$(cat VERSION)`. Signing needs the Ed25519 key in
`~/.config/awgbar/release-ed25519.key` (made by `swift scripts/sign.swift keygen`) and the Android
key described in [android/README.md](android/README.md). Keep backups of both: without the first no
macOS update can be signed, without the second Android won't update the app.

## Translations

UI strings go through `L("English text")`; Russian lives in `Resources/ru.lproj/AWGBar.strings`.

## License

MIT, see `LICENSE`. Bundled third-party components keep their own licenses, see `THIRD_PARTY_NOTICES.md`.
