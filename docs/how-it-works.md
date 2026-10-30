# How AWG Bar works

The three clients share the config handling, the import formats and the network rules; how they
bring a tunnel up differs. Using them is described in the [README](../README.md), building them in
[building.md](building.md).

## Common to all clients

- **Configs are sanitized.** Every config is parsed against an allowlist of the keys awg and
  amneziawg-go know, values are validated, and a canonical copy is what the tunnel gets. `PostUp`
  and the other hooks, `Table` and `SaveConfig` are dropped, so no config can run commands. The AWG 1.5/2.0 parameters (`S3`, `S4`, `H1`–`H4` ranges, `I1`–`I5`, …) pass through.
- **Imports** accept `vpn://` keys, AmneziaVPN JSON (a decoded key, a connection export or a
  backup, several servers at once) and plain `.conf` files; files and unpacked keys over 1 MiB
  are refused. An
  import never replaces an existing tunnel, so nothing can swap the server behind a name you
  trust.
- **Kill switch.** A config that sends all traffic of a family into the tunnel (`0.0.0.0/0` or
  `::/0`) gets one. While it is connected only the tunnel, the server, local networks, the Bypass
  VPN subnets and DHCP get out. This also covers what routes alone can't: a hostile DHCP server
  pushing routes more specific than the VPN's (option 121, "TunnelVision") and IPv6 slipping past
  a v4-only config. Split-tunnel configs (only some networks into the tunnel) get none.
- **DNS.** The tunnel's DNS servers are used while connected and reached only through the tunnel,
  even when a Bypass VPN subnet covers them (AmneziaDNS, 172.29.172.254, inside `172.16.0.0/12`).
  A full-tunnel config without `DNS` uses 1.1.1.1 and 1.0.0.1, as AmneziaVPN does, rather than
  the router's.
- **IPv6.** A config that sends `::/0` into the tunnel without an IPv6 address gets IPv6 blocked
  rather than sent around the VPN.
- **No logs** unless Debug Log is on; private keys are never logged.

## macOS

```
AWG Bar.app (SwiftUI MenuBarExtra, runs as you)
    │  JSON over /var/run/awgbar.sock, peer uid checked
    ▼
local.awgbar.helper (LaunchDaemon, root)
    │  bash awg-quick up|down /Library/Application Support/AWGBar/run/<tunnel>.conf
    ▼
amneziawg-go (utun) + routes + DNS + pf anchor
```

No paid Apple Developer account is needed: instead of a Network Extension a small root helper (a
LaunchDaemon) runs `amneziawg-go` and `awg-quick`, all built from pinned sources.

- The helper accepts requests only from root and from the user who installed it.
- Tunnels live in `/Library/Application Support/AWGBar/tunnels` (root only, 0600), excluded from
  Time Machine since they hold private keys in the clear. The app never reads them back.
- awg-quick would run hooks through `eval` as root; it only ever gets the sanitized copy.
- Everything that runs as root afterwards is copied into root-owned locations at install time.
  The install script itself runs once from the app bundle with your password, like any admin
  prompt: only press Install Helper for an app from the releases.
- Bypass VPN subnets ride awg-quick's server routes: pinned to the current gateway, moved by its
  route monitor when the network changes, removed on disconnect.
- The kill switch is a pf anchor, `com.apple/AWGBar`, which the stock `/etc/pf.conf` already
  evaluates, so the system ruleset is never rewritten. pf is enabled with a reference token that
  is given back on disconnect. Its rules are stateless, so connections already open (localhost
  included) aren't cut when the VPN connects. The tunnel's packets pass by their source port too,
  so a server answering from another address doesn't lock the tunnel out. DNS queries to the
  tunnel's servers may also leave through Wi-Fi or Ethernet: their addresses are set on the
  network services and macOS sends some queries bound to those, which blocked would kill DNS until
  a reconnect (AmneziaVPN allows the same). What the rules block is logged to `pflog0`. If `amneziawg-go` dies, the rules stay and the menu
  says "Connection lost · traffic blocked" until you disconnect.
- Updates are checked against an Ed25519 signature of the release's `SHA256SUMS`, whose public
  key is in the app; a release changed without the private key is refused.
- Debug Log writes `/var/log/awgbar-helper.log` (root, 0600), deleted when turned off. What
  awg-quick gets for a tunnel: `sudo /Library/PrivilegedHelperTools/local.awgbar.helper --print-config <tunnel>`.

## Linux

```
awgbar (Qt tray and window, runs as you)
    │  JSON over /run/awgbar/helper.sock; polkit decides who may connect
    ▼
awgbar-helper (systemd service, root, network capabilities only)
    │  amneziawg-go built in, netlink, nftables, systemd-resolved, logind
    ▼
awgbar0 (TUN) + policy routing + firewall + DNS
```

The main job is the one the AmneziaVPN client fails at on Linux: after the laptop sleeps, locks or
moves to another network, the VPN comes back by itself.

- Each request is checked with polkit (`io.github.artemimennoi.awgbar.control`): the user at the
  machine needs no password, anyone else (an ssh session) needs an administrator's.
- The service runs as root with only `CAP_NET_ADMIN` and `CAP_NET_RAW`, no access to home
  directories and the rest of the systemd sandbox (`linux/packaging/awgbar-helper.service`).
  Tunnels live in `/var/lib/awgbar/tunnels` (root only, 0600).
- Routing works like `wg-quick`: the tunnel's own packets carry fwmark `0x4157` and skip the VPN
  table, so they follow whatever NetworkManager sets up and no route to the server can go stale.

  ```
  16698: to <tunnel DNS> fwmark 0x4157 lookup main
  16699: to <tunnel DNS> lookup 16727
  16700: to <bypass subnets> lookup main
  16701: lookup main suppress_prefixlength 0
  16702: not fwmark 0x4157 lookup 16727      (default via awgbar0, blackhole behind it)
  ```

- The kill switch is an nftables table, `inet awgbar`. Marked packets always pass, so it never
  blocks the reconnect.
- DNS: with systemd-resolved the servers sit on `awgbar0` with the `~.` routing domain and other
  links stop being DNS default routes; without it `/etc/resolv.conf` is replaced and put back on
  disconnect. If NetworkManager changes either, the helper undoes it within seconds.
- After wake-up, a new default route or address, it looks the server up again outside the tunnel,
  rebinds the socket and starts a handshake; with no handshake in 20 s it recreates the device.
- If the service crashes, systemd restarts it and it reconnects; traffic stays blocked in between
  for as long as it takes (the app shows the tunnel as on; Disconnect lets traffic through). A
  package upgrade tells the running service first, so it keeps its rules and DNS across the
  restart.
- Debug Log writes `/var/log/awgbar-helper.log` (root, 0600), deleted when turned off. As root:
  `/usr/lib/awgbar/awgbar-helper --print-config <tunnel>` shows the config as the helper reads it,
  `--kick` does now what happens after wake-up.

## Android

A single-screen Compose app with the `tunnel` module of
[amneziawg-android](https://github.com/amnezia-vpn/amneziawg-android) (userspace Go backend only).

- Tunnels live in app-private storage, excluded from cloud backups and from device-to-device
  transfer.
- Bypass VPN subnets are cut out of the peers' AllowedIPs. A one-peer full-tunnel config keeps
  blocking the family it doesn't route (IPv6 for a v4-only config) either way.
- Keys from links and other apps are imported only after you confirm. The quick settings tile and
  the notification's Disconnect ask to unlock the phone; the lock screen shows only that the VPN
  is on.
- The app works as the system Always-on VPN; with "Block connections without VPN" Android blocks
  traffic while the tunnel is down too.

## Known limitations

- AWG Bar and the AmneziaVPN client must not run tunnels at the same time (on macOS they share
  `/var/run/amneziawg`).
- While connected, other VPNs can't reach their servers; add their networks to Bypass VPN.
- macOS: if the Mac loses power while connected, the DNS servers awg-quick set stay in the network
  settings until the next connect or disconnect (as with upstream wg-quick).
- Linux: on links managed by systemd-networkd (servers rather than NetworkManager desktops)
  resolved won't let the helper turn off their DNS default route, so DNS may be asked there too.
- Amnezia Premium/Free keys can't be imported: their config is issued by Amnezia's API at connect
  time.
