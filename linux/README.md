# AWG Bar for Linux

The Linux client: `helper/` is the root service (Go; it runs awgcore, our own AmneziaWG core, as a
process of its own, or `amneziawg-go` built in for the fallback package), `app/` the tray and
window (C++/Qt), `gnome/` the GNOME Shell extension that takes the app's place on Fedora,
`packaging/` the .deb and the RPM (`packaging/rpm/`) with their systemd unit and polkit policy.

- Installing and using it: the main [README](../README.md).
- How the helper keeps the VPN up through sleep and network changes, its kill switch and DNS:
  [docs/how-it-works.md](../docs/how-it-works.md#linux).
- Building the packages and running the tests: [docs/building.md](../docs/building.md#linux).
