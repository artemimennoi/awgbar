# AWG Bar for Android

The Android client: `app/` is the app (Kotlin, Compose: `KeyImport` for vpn:// / JSON / .conf,
`TunnelConfig` for the key allowlist and bypass subnets, `Vpn` for the one backend, `Tile`,
`MainActivity`), `tunnel/` the `tunnel` module of
[amneziawg-android](https://github.com/amnezia-vpn/amneziawg-android) at `ff15093` (Apache-2.0,
see `tunnel/COPYING`), cut down to the userspace backend. Its tunnel library `libwg-go.so` is built
from awgcore (`tunnel/tools/awgcore`, the default) or from amneziawg-go v3.1
(`tunnel/tools/libwg-go`, `-PawgCore=go`); `GoBackend` loads either.

- Installing and using it: the main [README](../README.md).
- What it does about backups, the lock screen, IPv6 and imports from other apps:
  [docs/how-it-works.md](../docs/how-it-works.md#android).
- Building, signing and releasing: [docs/building.md](../docs/building.md#android).
