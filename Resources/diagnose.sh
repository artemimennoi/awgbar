#!/bin/bash
# A snapshot of AWG Bar's tunnel, kill switch, routes and DNS, for when the connection misbehaves.
# Run it while the problem is happening, before reconnecting:
#   sudo bash "/Applications/AWG Bar.app/Contents/Resources/diagnose.sh"
# It writes ~/Desktop/awgbar-diagnose-<time>.txt. Private and preshared keys are left out; server
# addresses, routes and DNS are in it.
set -u
[[ $EUID -eq 0 ]] || { echo "run it with sudo" >&2; exit 1; }

SUPPORT="/Library/Application Support/AWGBar"
HELPER=/Library/PrivilegedHelperTools/local.awgbar.helper
ANCHOR=com.apple/AWGBar
user="${SUDO_USER:-$(stat -f %Su /dev/console)}"
out="$(eval echo "~$user")/Desktop/awgbar-diagnose-$(date +%Y%m%d-%H%M%S).txt"

section() { printf '\n===== %s\n' "$1"; }
redact() { grep -v -i -E '^(PrivateKey|PresharedKey)|private key|preshared key'; }

{
	section "time"
	date
	section "versions"
	/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' "/Applications/AWG Bar.app/Contents/Info.plist" 2>&1
	sw_vers
	section "tunnels up"
	for f in /var/run/amneziawg/*.name; do
		[[ -e $f ]] || continue
		name="$(basename "$f" .name)"
		echo "$name -> $(<"$f")"
		"$HELPER" --print-config "$name" 2>&1 | redact
	done
	ls -la "$SUPPORT/run" 2>&1
	section "awg show"
	"$SUPPORT/bin/wg" show all 2>&1 | redact
	section "kill switch rules with counters"
	pfctl -s info 2>&1 | head -5
	pfctl -a "$ANCHOR" -s rules -v 2>&1
	section "route monitors"
	pgrep -fl 'awg-quick up' 2>&1
	section "routes"
	netstat -rn -f inet 2>&1
	netstat -rn -f inet6 2>&1 | head -40
	section "interfaces"
	ifconfig 2>&1
	section "network services and DNS"
	scutil --nwi 2>&1
	scutil --dns 2>&1 | head -60
	networksetup -listallnetworkservices 2>/dev/null | tail -n +2 | while read -r service; do
		echo "$service: $(networksetup -getdnsservers "$service" | tr '\n' ' ')"
	done
	section "checks"
	route -n get 1.1.1.1 2>&1 | grep -E 'interface|gateway'
	ping -c 3 -t 5 1.1.1.1 2>&1 | tail -2
	echo "dns: $(dscacheutil -q host -a name example.com 2>&1 | grep -m1 ip_address)"
	curl -sS -m 8 -o /dev/null -w 'https by name: %{http_code}\n' https://example.com 2>&1
	curl -sS -m 8 -o /dev/null -w 'https by address: %{http_code}\n' https://1.1.1.1 2>&1
	section "blocked by the kill switch, 15 s"
	# The kill switch's final rule logs to pflog0 once the interface exists.
	ifconfig pflog0 create 2>/dev/null
	tcpdump -n -e -ttt -i pflog0 2>&1 &
	dump=$!
	sleep 1
	curl -sS -m 5 -o /dev/null https://example.com 2>/dev/null
	sleep 14
	kill "$dump" 2>/dev/null
	wait "$dump" 2>/dev/null
	section "helper debug log (if Debug Log is on)"
	tail -n 300 /var/log/awgbar-helper.log 2>&1
} > "$out" 2>&1

chown "$user" "$out"
echo "wrote $out"
