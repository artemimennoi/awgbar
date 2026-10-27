#!/bin/bash
# Removes the AWGBar helper. Tunnel configs stay in /Library/Application Support/AWGBar/tunnels
# unless --purge is given. Run as root.
set -uo pipefail

LABEL=local.awgbar.helper
SUPPORT="/Library/Application Support/AWGBar"
[[ $EUID -eq 0 ]] || { echo "must run as root" >&2; exit 1; }

# Bring tunnels down first so routes and DNS are restored.
for conf in "$SUPPORT"/tunnels/*.conf; do
	[[ -e $conf ]] || continue
	name="$(basename "$conf" .conf)"
	[[ -f /var/run/amneziawg/$name.name ]] || continue
	[[ -e $SUPPORT/run/$name.conf ]] && conf="$SUPPORT/run/$name.conf"
	"$SUPPORT/bin/bash" "$SUPPORT/bin/awg-quick" down "$conf"
	[[ -e $SUPPORT/run/$name.v6block ]] && route -q -n delete -inet6 ::/1 ::1 && route -q -n delete -inet6 8000::/1 ::1
done

launchctl bootout "system/$LABEL" 2>/dev/null
rm -f "/Library/LaunchDaemons/$LABEL.plist" "/Library/PrivilegedHelperTools/$LABEL" /var/run/awgbar.sock /var/log/awgbar-helper.log
# run/ holds sanitized copies of the configs, private keys included.
rm -rf "$SUPPORT/bin" "$SUPPORT/run" "$SUPPORT/debug-log"
if [[ ${1:-} == --purge ]]; then
	rm -rf "$SUPPORT"
else
	echo "configs kept in $SUPPORT/tunnels (use --purge to delete them)"
fi
