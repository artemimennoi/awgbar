# AWG Bar for Linux

The Linux version of AWG Bar: a tray icon and a small window for
[AmneziaWG](https://docs.amnezia.org/documentation/amnezia-wg/), for Ubuntu 22.04+ and Debian 12+,
made first for KDE Plasma. It imports AmneziaVPN keys (`vpn://…`) and plain AmneziaWG `.conf`
files, connects with one button, shows the server and traffic, and can send chosen subnets around
the VPN.

Its main job is the one the AmneziaVPN client fails on Linux: after the laptop sleeps, locks or
moves to another network, the VPN comes back by itself. No toggling it off and on, no restarting
the app, no reboot.

## Install

Download the package for your system from
[Releases](https://github.com/artemimennoi/awgbar/releases):

| Package | For |
|---|---|
| `awgbar_<version>_qt5_<arch>.deb` | Ubuntu 22.04 and 24.04 (Kubuntu too), Debian 12 |
| `awgbar_<version>_qt6_<arch>.deb` | Ubuntu 25.04 and newer, Debian 13 |

The two differ only in the Qt they use: Plasma's Breeze style exists only for the Qt version the
desktop itself is built on, so each package matches its Plasma.

```bash
sudo apt install ./awgbar_0.1.0_qt5_amd64.deb
```

This is the only time a password is needed. The package starts the `awgbar-helper` service and
adds AWG Bar to the applications menu and to autostart. Then open AWG Bar, press **From
Clipboard** with a `vpn://` key copied, or **From File…**, and **Connect**.

GNOME shows tray icons only with the AppIndicator extension: Ubuntu has it on by default, on
Debian install `gnome-shell-extension-appindicator`. Without a tray the window is the app.

Remove with `sudo apt remove awgbar` (tunnel configs are kept) or `sudo apt purge awgbar`.

## How it works

```
awgbar (Qt tray and window, runs as you)
    │  JSON over /run/awgbar/helper.sock; polkit decides who may connect
    ▼
awgbar-helper (systemd service, root, network capabilities only)
    │  amneziawg-go built in, netlink, systemd-resolved, logind
    ▼
awgbar0 (TUN) + policy routing + DNS
```

- Tunnel configs are stored in `/var/lib/awgbar/tunnels` (root, 0600).
- Only one tunnel is up at a time. Quitting the app disconnects the VPN; closing the window
  doesn't.
- **Split tunnelling** («Bypass VPN»): listed subnets (`10.*`, `192.168.0.0/16`, `fd00::/8`, …)
  go through your normal network.
- **IPv6 leak protection**: a config that sends `::/0` into the tunnel without an IPv6 address
  gets IPv6 blackholed instead of silently bypassing the VPN.

### Why it survives sleep

The usual ways a Linux VPN client breaks after sleep, and what the helper does instead:

- **A route to the server via the old gateway.** The kernel deletes it when Wi-Fi drops; after
  wake-up the tunnel's own packets go into the tunnel. The helper has no such route: like
  `wg-quick`, the tunnel's packets carry a firewall mark and skip the VPN routing table, so they
  follow whatever NetworkManager sets up.

  ```
  16700: to <bypass subnets> lookup main
  16701: lookup main suppress_prefixlength 0
  16702: not fwmark 0x4157 lookup 16727      (default via awgbar0, blackhole behind it)
  ```

- **DNS overwritten by NetworkManager.** With systemd-resolved the servers sit on `awgbar0` itself
  with the `~.` routing domain, and other links stop being DNS default routes; without resolved,
  `/etc/resolv.conf` is replaced and put back on disconnect. Either way, if NetworkManager changes
  it again after a reconnect, the helper undoes that within seconds.
- **A stale UDP socket or server address.** On wake-up (logind's `PrepareForSleep`), on a new
  default route or address, the helper looks the server up again outside the tunnel, rebinds the
  socket and starts a handshake. If none completes within 20 s, it recreates the tunnel device.
- **A kill switch that blocks its own reconnect.** The blackhole never catches the tunnel's
  marked packets, so blocking traffic around the VPN can't block the VPN itself.
- **A stuck client.** The tunnel lives in the service, not the app. If the service crashes,
  systemd restarts it and it reconnects; traffic stays blocked in between.

## Security model

- The service accepts requests over a socket anyone may open; each request is checked with
  polkit (`io.github.artemimennoi.awgbar.control`): the user at the machine needs no password,
  anyone else (an ssh session) needs an administrator's.
- Configs are parsed against an allowlist of AmneziaWG keys with validated values; anything else
  (`PostUp` and the other hooks, `Table`, `FwMark`) is dropped. Nothing runs a shell.
- The service runs as root but with only `CAP_NET_ADMIN` and `CAP_NET_RAW`, no access to home
  directories and the rest of the systemd sandbox (see `packaging/awgbar-helper.service`).
- **No logs by default.** With **Debug Log** on in the menu it writes `/var/log/awgbar-helper.log`
  (root only, 0600), deleted when the option is turned off. Private keys are never logged.

Debugging aids, as root:

```bash
/usr/lib/awgbar/awgbar-helper --print-config <tunnel>   # the config as the helper reads it
/usr/lib/awgbar/awgbar-helper --kick                    # what happens after wake-up, now
```

## Build

Needs Go and Docker (the app is built in a container of the oldest distribution each package
targets):

```bash
make -C linux deb              # build/dist/awgbar_<version>_qt{5,6}_amd64.deb
make -C linux deb ARCH=arm64
make -C linux test             # helper unit tests
```

The helper also builds alone with `go build ./helper`; the app with CMake
(`-DAWGBAR_QT=5` or `6`) against Qt Widgets and Network.

## Known limitations

- AWG Bar and the AmneziaVPN client must not run tunnels at the same time.
- On links managed by systemd-networkd (servers, not NetworkManager desktops) resolved won't let
  the helper turn off their DNS default route, so DNS may also be asked there.
- Amnezia Premium/Free keys can't be imported: their config is issued by Amnezia's API at
  connect time.
- No built-in updater yet: install a newer package over the old one; the VPN reconnects by itself.
