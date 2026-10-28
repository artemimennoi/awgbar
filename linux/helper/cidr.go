package main

import (
	"net/netip"
	"strconv"
	"strings"
)

// parseNetwork accepts "10.0.0.0/8", "10.1.2.3" (/32), "10.*" or "192.168.*.*" (octet wildcards),
// "fd00::/8" and "fd00::1", like CIDR in the macOS app. Host bits are cleared.
func parseNetwork(text string) (netip.Prefix, bool) {
	s := strings.TrimSpace(text)
	if strings.Contains(s, "*") {
		parts := strings.Split(s, ".")
		if len(parts) > 4 {
			return netip.Prefix{}, false
		}
		var octets [4]byte
		n := 0
		for _, part := range parts {
			if part == "*" {
				break
			}
			v, err := strconv.ParseUint(part, 10, 8)
			if err != nil {
				return netip.Prefix{}, false
			}
			octets[n] = byte(v)
			n++
		}
		// Everything after the first "*" must be "*" too: "10.*.3" is not a network.
		for _, part := range parts[n:] {
			if part != "*" {
				return netip.Prefix{}, false
			}
		}
		return netip.PrefixFrom(netip.AddrFrom4(octets), n*8), true
	}
	if !strings.Contains(s, "/") {
		addr, err := netip.ParseAddr(s)
		if err != nil || addr.Zone() != "" {
			return netip.Prefix{}, false
		}
		return netip.PrefixFrom(addr.Unmap(), addr.Unmap().BitLen()), true
	}
	prefix, err := netip.ParsePrefix(s)
	if err != nil {
		return netip.Prefix{}, false
	}
	return netip.PrefixFrom(prefix.Addr().Unmap(), prefix.Bits()).Masked(), true
}

// parseNetworkList splits input like "10.*, 192.168.0.0/16 fd00::/8" into unique networks
// and the entries that failed to parse.
func parseNetworkList(text string) (networks []netip.Prefix, invalid []string) {
	entries := strings.FieldsFunc(text, func(r rune) bool { return strings.ContainsRune(",; \n\t", r) })
	seen := map[netip.Prefix]bool{}
	for _, entry := range entries {
		net, ok := parseNetwork(entry)
		if !ok {
			invalid = append(invalid, entry)
			continue
		}
		if !seen[net] {
			seen[net] = true
			networks = append(networks, net)
		}
	}
	return networks, invalid
}
