#!/bin/bash
# Builds the awgbar RPM for Fedora Workstation inside a container of the oldest Fedora the package
# targets (see linux/Makefile). Run by `make -C linux rpm`, not by hand:
#   build-rpm.sh <version> <out dir> [awgcore|amneziawg-go]
# amneziawg-go is for CI only, which cannot fetch awgcore's private repository; nobody ships it.
# Expects the repository at /src, the helper built at /src/linux/build/awgbar-helper-amd64, its Go
# modules' licenses in /src/linux/build/licenses and, with awgcore (the default), awgcore at
# /src/linux/build/awgcore-amd64 with its crates' licenses next to it.
set -euo pipefail

version=$1
out=$2
userspace=${3:-awgcore}
arch=amd64

dnf -q -y install rpm-build systemd-rpm-macros gettext glib2-devel >/dev/null 2>"/tmp/dnf.log" ||
    { cat /tmp/dnf.log >&2; exit 1; }

top=$(mktemp -d)
src=$top/SOURCES
mkdir -p "$src/licenses/licenses"
pkg=/src/linux/packaging

install -m755 "/src/linux/build/awgbar-helper-$arch" "$src/awgbar-helper"
# The package: awgcore, and a helper without amneziawg-go that refuses a CPU awgcore can't run on.
# CI's build: no awgcore, amneziawg-go built into the helper.
if [ "$userspace" = awgcore ]; then
    install -m755 "/src/linux/build/awgcore-$arch" "$src/awgcore"
    tunnel="awgcore, our own AmneziaWG core, as a process of its own"
else
    tunnel="amneziawg-go, built in (a CI build)"
fi

# The .deb's unit, with the binaries in /usr/libexec/awgbar (why: awgbar.spec).
sed -e 's|/usr/lib/awgbar/|/usr/libexec/awgbar/|g' \
    -e '/^ExecStart=/i Environment=AWGBAR_AWGCORE=/usr/libexec/awgbar/awgcore' \
    "$pkg/awgbar-helper.service" > "$src/awgbar-helper.service"
grep -qx 'ExecStart=/usr/libexec/awgbar/awgbar-helper' "$src/awgbar-helper.service"
! grep -q '/usr/lib/awgbar' "$src/awgbar-helper.service"
install -m644 "$pkg/io.github.artemimennoi.awgbar.policy" "$pkg/icons/awgbar.svg" "$src/"

bash /src/linux/gnome/build.sh "$src" "$version"

# The .deb's copyright file, with this package's paths.
fix_paths() { sed -e 's|usr/lib/awgbar/|usr/libexec/awgbar/|g' -e 's|/usr/share/doc/awgbar/licenses|/usr/share/licenses/awgbar/licenses|g'; }
fix_paths < "$pkg/copyright" > "$src/licenses/copyright"
install -m644 /src/linux/build/licenses/*.txt "$src/licenses/licenses/"
if [ "$userspace" = awgcore ]; then
    fix_paths < "$pkg/copyright-awgcore" >> "$src/licenses/copyright"
    mkdir -p "$src/licenses/licenses/awgcore"
    install -m644 "/src/linux/build/awgcore-$arch-licenses"/* "$src/licenses/licenses/awgcore/"
fi

rpmbuild -bb --quiet \
    --define "_topdir $top" \
    --define "pkg_version $version" \
    --define "pkg_release 1" \
    --define "tunnel $tunnel" \
    --define "tunnel_core $userspace" \
    "$pkg/rpm/awgbar.spec"

mkdir -p "$out"
for rpm in "$top"/RPMS/x86_64/*.rpm; do
    cp "$rpm" "$out/"
    echo "built $out/$(basename "$rpm")"
    rpm -qp --requires "$rpm" | grep -v '^rpmlib(' | tr '\n' ' '
    echo
done
