#!/bin/bash
# Builds the awgbar .deb inside a container of the oldest distribution the package targets
# (see linux/Makefile). Run by `make -C linux deb`, not by hand:
#   build-deb.sh <qt5|qt6> <version> <out dir>
# Expects the repository at /src and the helper binary already built at
# /src/linux/build/awgbar-helper-<arch>.
set -euo pipefail

flavor=$1
version=$2
out=$3
qt=${flavor#qt}
arch=$(dpkg --print-architecture)

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
if [ "$qt" = 5 ]; then
    qtdev="qtbase5-dev"
else
    qtdev="qt6-base-dev"
fi
apt-get install -y -qq --no-install-recommends build-essential cmake dpkg-dev $qtdev >/dev/null

build=$(mktemp -d)
cmake -S /src/linux/app -B "$build/app" -DAWGBAR_QT="$qt" -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$build/app" -j"$(nproc)"
strip "$build/app/awgbar"

root=$build/root
install -Dm755 "$build/app/awgbar" "$root/usr/bin/awgbar"
install -Dm755 "/src/linux/build/awgbar-helper-$arch" "$root/usr/lib/awgbar/awgbar-helper"
pkg=/src/linux/packaging
install -Dm644 "$pkg/awgbar-helper.service" "$root/lib/systemd/system/awgbar-helper.service"
install -Dm644 "$pkg/io.github.artemimennoi.awgbar.policy" "$root/usr/share/polkit-1/actions/io.github.artemimennoi.awgbar.policy"
install -Dm644 "$pkg/awgbar.desktop" "$root/usr/share/applications/awgbar.desktop"
install -Dm644 "$pkg/awgbar-autostart.desktop" "$root/etc/xdg/autostart/awgbar.desktop"
install -Dm644 "$pkg/icons/awgbar.svg" "$root/usr/share/icons/hicolor/scalable/apps/awgbar.svg"
for state in on off busy; do
    install -Dm644 "$pkg/icons/awgbar-$state-symbolic.svg" "$root/usr/share/icons/hicolor/scalable/status/awgbar-$state-symbolic.svg"
done
install -Dm644 "$pkg/copyright" "$root/usr/share/doc/awgbar/copyright"
for license in /src/linux/build/licenses/*.txt; do
    install -Dm644 "$license" "$root/usr/share/doc/awgbar/licenses/$(basename "$license")"
done

# Library dependencies of the app, as this distribution's Qt names them.
mkdir -p "$build/shlibs/debian"
echo "Source: awgbar" > "$build/shlibs/debian/control"
depends=$(cd "$build/shlibs" && dpkg-shlibdeps -O "$root/usr/bin/awgbar" 2>/dev/null | sed -n 's/^shlibs:Depends=//p')

mkdir -p "$root/DEBIAN"
for script in postinst prerm postrm; do
    install -m755 "$pkg/debian/$script" "$root/DEBIAN/$script"
done
echo "/etc/xdg/autostart/awgbar.desktop" > "$root/DEBIAN/conffiles"
size=$(du -sk --exclude=DEBIAN "$root" | cut -f1)
sed -e "s/@VERSION@/$version/" -e "s/@ARCH@/$arch/" -e "s/@DEPENDS@/$depends/" -e "s/@SIZE@/$size/" \
    "$pkg/debian/control" > "$root/DEBIAN/control"

mkdir -p "$out"
deb="$out/awgbar_${version}_${flavor}_${arch}.deb"
dpkg-deb --root-owner-group --build "$root" "$deb" >/dev/null
echo "built $deb"
grep '^Depends' "$root/DEBIAN/control"
