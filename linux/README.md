# AWG Bar for Linux

The Linux client: `helper/` is the root service (Go, amneziawg-go built in), `app/` the tray and
window (C++/Qt), `packaging/` the .deb with its systemd unit and polkit policy.

- Installing and using it: the main [README](../README.md).
- How the helper keeps the VPN up through sleep and network changes, its kill switch and DNS:
  [docs/how-it-works.md](../docs/how-it-works.md#linux).
- Building the packages and running the tests: [docs/building.md](../docs/building.md#linux).
