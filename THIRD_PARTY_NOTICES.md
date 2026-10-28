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

## Android app (`android/`)

| Component | Used as | License | Source |
|---|---|---|---|
| amneziawg-android `tunnel` module (at `ff15093`) | `android/tunnel`, vendored and trimmed | Apache-2.0 (`android/tunnel/COPYING`) | https://github.com/amnezia-vpn/amneziawg-android |
| amneziawg-go v3.1.20260814 | `libwg-go.so` inside the APK | MIT | https://github.com/amnezia-vpn/amneziawg-go |
| Go runtime 1.25.14 (with WireGuard's boottime patch) | compiled into `libwg-go.so` | BSD-3-Clause | https://go.dev |
| AndroidX, Jetpack Compose, kotlinx.coroutines | app dependencies | Apache-2.0 | https://developer.android.com/jetpack |
