#!/bin/bash
# Builds the GNOME Shell extension into <out dir>/awgbar@artemimennoi.github.io, ready to go under
# /usr/share/gnome-shell/extensions/ (the RPM does that) or ~/.local/share/gnome-shell/extensions/:
#   build.sh <out dir> <version>
# Needs gettext (msgfmt, xgettext, msgcmp) and glib-compile-schemas.
set -euo pipefail
# The sources and the catalog are UTF-8 ("…", Cyrillic).
export LC_ALL=C.UTF-8

here=$(cd "$(dirname "$0")" && pwd)
out=${1:?out dir}
version=${2:?version}
uuid=awgbar@artemimennoi.github.io
src=$here/extension
dest=$out/$uuid
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

rm -rf "$dest"
mkdir -p "$dest/lib" "$dest/icons" "$dest/schemas" "$dest/locale/ru/LC_MESSAGES"
sed "s/@VERSION@/$version/" "$src/metadata.json" > "$dest/metadata.json"
install -m644 "$src/extension.js" "$src/prefs.js" "$dest/"
install -m644 "$src"/lib/*.js "$dest/lib/"

# The Shell loads stylesheet-dark.css or stylesheet-light.css for its current style.
cat "$src/stylesheet.css" "$src/colors-dark.css" > "$dest/stylesheet-dark.css"
cat "$src/stylesheet.css" "$src/colors-light.css" > "$dest/stylesheet-light.css"
cp "$dest/stylesheet-dark.css" "$dest/stylesheet.css"

# The panel icons the Qt app exports for the icon theme (linux/packaging/icons).
for state in on off busy; do
    install -m644 "$here/../packaging/icons/awgbar-$state-symbolic.svg" "$dest/icons/"
done

install -m644 "$src"/schemas/*.gschema.xml "$dest/schemas/"
glib-compile-schemas --strict "$dest/schemas"

# Every string the code translates has a Russian translation, and every translation is used.
xgettext -L JavaScript --from-code=UTF-8 -k_ --no-location -o "$work/awgbar.pot" "$src"/*.js "$src"/lib/*.js
msgcmp "$here/po/ru.po" "$work/awgbar.pot"
unused=$(msgmerge -q --no-fuzzy-matching "$here/po/ru.po" "$work/awgbar.pot" | msgattrib --only-obsolete)
if [ -n "$unused" ]; then
    echo "po/ru.po has translations nothing uses:" >&2
    echo "$unused" >&2
    exit 1
fi
msgfmt --check -o "$dest/locale/ru/LC_MESSAGES/awgbar.mo" "$here/po/ru.po"
echo "built $dest"
