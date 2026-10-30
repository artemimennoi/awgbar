#!/bin/bash
# Installs the AWGBar root helper as a LaunchDaemon. Run as root:
#   install-helper.sh <AWGBar.app/Contents/Resources> <uid allowed to control tunnels>
# Everything that runs as root is copied into root-owned locations, never run from the app bundle.
set -euo pipefail

RES="$1"
ALLOWED_UID="$2"
[[ $EUID -eq 0 ]] || { echo "must run as root" >&2; exit 1; }
[[ $ALLOWED_UID =~ ^[0-9]+$ ]] || { echo "bad uid: $ALLOWED_UID" >&2; exit 1; }

LABEL=local.awgbar.helper
SUPPORT="/Library/Application Support/AWGBar"
HELPER="/Library/PrivilegedHelperTools/$LABEL"
PLIST="/Library/LaunchDaemons/$LABEL.plist"

launchctl bootout "system/$LABEL" 2>/dev/null || true

install -d -o root -g wheel -m 0755 /Library/PrivilegedHelperTools "$SUPPORT" "$SUPPORT/bin"
install -d -o root -g wheel -m 0700 "$SUPPORT/tunnels"
# The configs hold private keys in the clear (root only): keep them out of Time Machine.
tmutil addexclusion "$SUPPORT" 2>/dev/null || true
install -o root -g wheel -m 0755 "$RES/helper/$LABEL" "$HELPER"
for f in awg-quick wg amneziawg-go bash; do
	install -o root -g wheel -m 0755 "$RES/bin/$f" "$SUPPORT/bin/$f"
done

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>$LABEL</string>
	<key>ProgramArguments</key>
	<array>
		<string>$HELPER</string>
		<string>--uid</string>
		<string>$ALLOWED_UID</string>
	</array>
	<key>RunAtLoad</key>
	<true/>
	<key>KeepAlive</key>
	<true/>
	<!-- amneziawg-go and the awg-quick route monitor must survive a helper restart. -->
	<key>AbandonProcessGroup</key>
	<true/>
	<!-- No StandardOutPath/StandardErrorPath: nothing is logged unless the debug log is turned on. -->
</dict>
</plist>
EOF
chown root:wheel "$PLIST"
chmod 0644 "$PLIST"

# Helper v4 and older logged everything to launchd's stdout file.
[[ -e "$SUPPORT/debug-log" ]] || rm -f /var/log/awgbar-helper.log

launchctl bootstrap system "$PLIST"
echo "installed $LABEL for uid $ALLOWED_UID"
