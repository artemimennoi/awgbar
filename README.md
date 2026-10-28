# awgbar

A small native macOS menu bar client for [AmneziaWG](https://docs.amnezia.org/documentation/amnezia-wg/).
It lives only in the menu bar: no Dock icon, no main window. Imports AmneziaVPN keys (`vpn://…`)
and plain AmneziaWG `.conf` files, connects with one button, shows the server and traffic, and can
send chosen subnets around the VPN.

No paid Apple Developer account is needed: instead of a Network Extension it uses a small root
helper (a LaunchDaemon) that runs `amneziawg-go` and `awg-quick`.

## Install from a release

awgbar is in beta: releases are published as pre-releases.

Apple Silicon, macOS 26 or later. Download `awgbar-<version>-macos-arm64.dmg` from
[Releases](https://github.com/artemimennoi/awgbar/releases), open it and drag `AWGBar` to
`Applications`. The app is ad-hoc signed, not notarized, so macOS blocks the first launch: allow it
in System Settings → Privacy & Security → **Open Anyway**, or clear the quarantine flag once:

```bash
xattr -dr com.apple.quarantine /Applications/AWGBar.app
```

## Updates

Settings menu → **Check for Updates…** looks at this repository's releases (betas included); the
app never checks on its own. If there is a newer version, **Update to x.y.z** appears in the
window: it downloads the DMG, verifies it against the release's `SHA256SUMS` and its code
signature, replaces the app in place and relaunches it, keeping the VPN connected. The app must be
in a folder you can write to, such as `/Applications`. The checksum guards against a broken
download; authenticity relies on HTTPS to GitHub.

## Build from source

Requirements: macOS 26 or later, Xcode Command Line Tools (Swift), Go, git, curl.

```bash
make install
```

This builds `amneziawg-go`, `awg`, `awg-quick` and a self-contained `bash` from pinned sources,
assembles `build/AWGBar.app` and copies it to `/Applications`. Open it, click the shield in the
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
AWGBar.app (SwiftUI MenuBarExtra, runs as you)
    │  JSON over /var/run/awgbar.sock, peer uid checked
    ▼
local.awgbar.helper (LaunchDaemon, root)
    │  bash awg-quick up|down /Library/Application Support/AWGBar/run/<tunnel>.conf
    ▼
amneziawg-go (utun) + routes + DNS
```

- Tunnel configs are stored in `/Library/Application Support/AWGBar/tunnels` (root, 0600).
- Only one tunnel is up at a time. Quitting the app disconnects the VPN.
- **Split tunnelling** («Bypass VPN»): listed subnets (`10.*`, `192.168.0.0/16`, `fd00::/8`, …) are
  routed through your normal gateway. They reuse awg-quick's endpoint routes, so they follow
  network changes and are removed on disconnect. Requires a full-tunnel config (`0.0.0.0/0`).
- **IPv6 leak protection**: if a config sends `::/0` into the tunnel but gives it no IPv6 address,
  IPv6 is blackholed while connected instead of silently bypassing the VPN.
- English and Russian UI, switchable in the settings menu (English by default).

## Security model

- The helper accepts requests only from root and from the user who installed it.
- awg-quick runs `PreUp`/`PostUp`/`PreDown`/`PostDown` through `eval` as root. awgbar never passes
  a config to it as written: every config is parsed against an allowlist of keys known to awg and
  awg-quick, values are validated, and a canonical copy is rendered before each connect. Hooks,
  `Table` and `SaveConfig` are dropped.
- Everything that runs as root is copied into root-owned locations at install time; nothing is
  executed from the user-writable app bundle.
- **No logs by default.** The helper writes nothing unless **Debug Log** is enabled in the settings
  menu; it then logs to `/var/log/awgbar-helper.log` (root only, 0600), which is deleted when the
  option is turned off. Private keys are never logged.

Debugging aid, shows exactly what awg-quick gets:

```bash
sudo /Library/PrivilegedHelperTools/local.awgbar.helper --print-config <tunnel>
```

## Known limitations

- awgbar and the AmneziaVPN app share `/var/run/amneziawg`; don't run both tunnels at once.
- If the Mac loses power while connected, DNS servers set by awg-quick stay in the network
  settings until the next connect/disconnect (same as upstream wg-quick).
- Amnezia Premium/Free keys can't be imported: their config is issued by Amnezia's API at connect time.

## Translations

UI strings go through `L("English text")`; Russian lives in `Resources/ru.lproj/AWGBar.strings`.

## License

MIT, see `LICENSE`. Bundled third-party components keep their own licenses, see `THIRD_PARTY_NOTICES.md`.
