# Third-party software

This repository contains only awgbar's own code (MIT, see `LICENSE`). `make` downloads and builds
the components below; a built `AWGBar.app` ships their binaries alongside awgbar as separate
programs, each under its own license. Exact revisions are pinned in the `Makefile`.

| Component | Used as | License | Source |
|---|---|---|---|
| amneziawg-go | `Contents/Resources/bin/amneziawg-go`, the userspace tunnel | MIT | https://github.com/amnezia-vpn/amneziawg-go |
| amneziawg-tools | `bin/wg` (the `awg` tool) and `bin/awg-quick` (`src/wg-quick/darwin.bash`) | GPL-2.0 | https://github.com/amnezia-vpn/amneziawg-tools |
| GNU Bash 5.2.37 | `bin/bash`, runs awg-quick | GPL-3.0-or-later | https://ftp.gnu.org/gnu/bash/bash-5.2.37.tar.gz |

`bin/awg-quick` is modified: `Resources/awg-quick-darwin.patch` is applied at build time and is
itself distributed under GPL-2.0, like the file it changes. If you distribute a built app, you
must also offer the corresponding source of these components (the URLs and revisions above plus
the patch) and keep their license texts available.

## Linux packages

The Linux helper (`linux/helper`) links these Go modules statically; the `.deb` ships their
license texts in `/usr/share/doc/awgbar/licenses`. Versions are pinned in `linux/go.mod`.

| Component | License | Source |
|---|---|---|
| amneziawg-go | MIT | https://github.com/amnezia-vpn/amneziawg-go |
| netlink, netns | Apache-2.0 | https://github.com/vishvananda/netlink |
| godbus/dbus | BSD-2-Clause | https://github.com/godbus/dbus |
| golang.org/x/sys, x/net, x/crypto | BSD-3-Clause | https://go.googlesource.com |

The Linux app links the distribution's Qt (LGPL-3.0) dynamically.
