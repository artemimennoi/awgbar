package main

import (
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net"
	"net/netip"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode"
)

// TunnelConfig is an AmneziaWG config reduced to known keys with validated values, stored and
// shown in canonical "Key = value" form. Only the helper interprets it: there is no awg-quick and
// no shell here, so hooks are dropped simply because nothing would run them.
type TunnelConfig struct {
	Title     string
	Interface []entry
	Peers     [][]entry
	// Keys that were present but not allowed; reported, never written.
	Dropped []string
}

type entry struct{ key, value string }

// Display name stored as a comment, the same line the macOS app writes.
const titlePrefix = "# awgbar-name = "

var (
	// Keys the helper consumes itself.
	ownInterfaceKeys = []string{"Address", "DNS", "MTU"}
	// Keys amneziawg-go takes over UAPI, from amneziawg-tools src/config.c. FwMark is left out:
	// the helper marks the tunnel's own packets to route them around it.
	uapiInterfaceKeys = []string{
		"PrivateKey", "ListenPort", "Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4",
		"H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5", "HeaderProtectionKey",
		"ContentPaddingAddition", "RekeyAfterTime", "RekeyTimeout", "RejectAfterTime",
		"KeepaliveTimeout", "MaxHandshakeAttempts", "RandomTrailers", "DisableCookies",
	}
	// AdvancedSecurity is kernel-module only; amneziawg-go rejects it.
	peerKeys = []string{"PublicKey", "PresharedKey", "AllowedIPs", "Endpoint", "PersistentKeepalive"}

	interfaceKeyMap = canonicalKeys(append(append([]string{}, ownInterfaceKeys...), uapiInterfaceKeys...))
	peerKeyMap      = canonicalKeys(peerKeys)

	uint16Pattern = regexp.MustCompile(`^[0-9]{1,5}$`)
	rangePattern  = regexp.MustCompile(`^([0-9]{1,10})(?:-([0-9]{1,10}))?$`)
	dnsPattern    = regexp.MustCompile(`^[A-Za-z0-9.:_-]+$`)
)

func canonicalKeys(keys []string) map[string]string {
	m := make(map[string]string, len(keys))
	for _, k := range keys {
		m[strings.ToLower(k)] = k
	}
	return m
}

// Every line terminator Go, Swift or awg might honour, not just "\n".
func splitLines(text string) []string {
	return strings.FieldsFunc(text, func(r rune) bool {
		switch r {
		case '\n', '\r', '\v', '\f', 0x85, 0x2028, 0x2029:
			return true
		}
		return false
	})
}

func parseConfig(text string) (*TunnelConfig, error) {
	c := &TunnelConfig{}
	const (
		none = iota
		iface
		peer
	)
	section := none
	sawInterface := false

	for _, raw := range splitLines(text) {
		if strings.HasPrefix(raw, titlePrefix) {
			c.Title = cleanTitle(raw[len(titlePrefix):])
			continue
		}
		line, _, _ := strings.Cut(raw, "#")
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") {
			switch strings.ToLower(line) {
			case "[interface]":
				if sawInterface {
					return nil, fail("More than one [Interface] section")
				}
				sawInterface = true
				section = iface
			case "[peer]":
				c.Peers = append(c.Peers, nil)
				section = peer
			default:
				return nil, fail("Unknown section %s", line)
			}
			continue
		}

		rawKey, rawValue, found := strings.Cut(line, "=")
		key, value := strings.TrimSpace(rawKey), strings.TrimSpace(rawValue)
		if !found || key == "" {
			return nil, fail("Not a key = value line: %s", line)
		}
		if strings.IndexFunc(value, unicode.IsControl) >= 0 {
			return nil, fail("Control characters in %s", key)
		}

		switch section {
		case none:
			return nil, fail("%s outside of a section", key)
		case iface:
			canonical, ok := interfaceKeyMap[strings.ToLower(key)]
			if !ok {
				c.Dropped = append(c.Dropped, key)
				continue
			}
			if err := validate(canonical, value); err != nil {
				return nil, err
			}
			c.Interface = append(c.Interface, entry{canonical, value})
		case peer:
			canonical, ok := peerKeyMap[strings.ToLower(key)]
			if !ok {
				c.Dropped = append(c.Dropped, key)
				continue
			}
			if err := validate(canonical, value); err != nil {
				return nil, err
			}
			c.Peers[len(c.Peers)-1] = append(c.Peers[len(c.Peers)-1], entry{canonical, value})
		}
	}
	if !sawInterface || len(c.Peers) == 0 {
		return nil, fail("Not an AmneziaWG config: [Interface] or [Peer] section is missing")
	}
	if c.get("PrivateKey") == "" {
		return nil, fail("PrivateKey is missing")
	}
	for _, p := range c.Peers {
		if peerValue(p, "PublicKey") == "" {
			return nil, fail("PublicKey is missing in a [Peer] section")
		}
	}
	return c, nil
}

func splitItems(value string) []string {
	var items []string
	for _, item := range strings.Split(value, ",") {
		if item = strings.TrimSpace(item); item != "" {
			items = append(items, item)
		}
	}
	return items
}

func validate(key, value string) error {
	ok := true
	switch key {
	case "PrivateKey", "PublicKey", "PresharedKey", "HeaderProtectionKey":
		_, err := keyHex(value)
		ok = err == nil
	case "Address":
		for _, item := range splitItems(value) {
			if _, err := parseAddress(item); err != nil {
				ok = false
			}
		}
	case "AllowedIPs":
		for _, item := range splitItems(value) {
			if _, err := netip.ParsePrefix(item); err != nil {
				if _, err := netip.ParseAddr(item); err != nil {
					ok = false
				}
			}
		}
	case "DNS":
		for _, item := range splitItems(value) {
			ok = ok && dnsPattern.MatchString(item)
		}
	case "MTU", "ListenPort", "Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4":
		ok = uint16Pattern.MatchString(value) && atoi(value) <= 65535
	case "H1", "H2", "H3", "H4":
		ok = validRange(value, 1<<32-1)
	case "PersistentKeepalive", "ContentPaddingAddition", "RekeyAfterTime", "RekeyTimeout",
		"RejectAfterTime", "KeepaliveTimeout", "MaxHandshakeAttempts":
		// AmneziaWG randomizes these within "lo-hi" (u16_range_from_string in amneziawg-tools).
		ok = validRange(value, 65535)
	case "RandomTrailers", "DisableCookies":
		_, ok = parseBool(value)
	case "Endpoint":
		host, port, err := net.SplitHostPort(value)
		ok = err == nil && host != "" && dnsPattern.MatchString(host) && uint16Pattern.MatchString(port) && atoi(port) <= 65535
	case "I1", "I2", "I3", "I4", "I5":
		// Obfuscation templates, passed through as written; control characters are already refused.
	}
	if !ok {
		return fail("Invalid %s: %s", key, value)
	}
	return nil
}

func atoi(s string) int {
	n, _ := strconv.Atoi(s)
	return n
}

func validRange(value string, max uint64) bool {
	m := rangePattern.FindStringSubmatch(value)
	if m == nil {
		return false
	}
	lo, err := strconv.ParseUint(m[1], 10, 64)
	if err != nil || lo > max {
		return false
	}
	if m[2] == "" {
		return true
	}
	hi, err := strconv.ParseUint(m[2], 10, 64)
	return err == nil && hi <= max && hi >= lo
}

// amneziawg-tools takes on/off; Go's ParseBool on the UAPI side takes true/false/1/0.
func parseBool(value string) (bool, bool) {
	switch strings.ToLower(value) {
	case "on", "true", "yes", "1":
		return true, true
	case "off", "false", "no", "0":
		return false, true
	}
	return false, false
}

func keyHex(b64 string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(b64)
	if err != nil || len(raw) != 32 {
		return "", fmt.Errorf("not a 32-byte base64 key")
	}
	return hex.EncodeToString(raw), nil
}

// An interface address keeps its host bits: "10.8.0.2/32", or "10.8.0.2" meaning the same.
func parseAddress(s string) (netip.Prefix, error) {
	if !strings.Contains(s, "/") {
		addr, err := netip.ParseAddr(s)
		if err != nil {
			return netip.Prefix{}, err
		}
		return netip.PrefixFrom(addr, addr.BitLen()), nil
	}
	return netip.ParsePrefix(s)
}

// One line, no control characters, sane length: it is written into the config file.
func cleanTitle(raw string) string {
	title := strings.TrimSpace(strings.Map(func(r rune) rune {
		if unicode.IsControl(r) || r == 0x2028 || r == 0x2029 {
			return ' '
		}
		return r
	}, raw))
	if r := []rune(title); len(r) > 64 {
		title = string(r[:64])
	}
	return title
}

func (c *TunnelConfig) get(key string) string {
	for _, e := range c.Interface {
		if e.key == key {
			return e.value
		}
	}
	return ""
}

func (c *TunnelConfig) all(key string) []string {
	var items []string
	for _, e := range c.Interface {
		if e.key == key {
			items = append(items, splitItems(e.value)...)
		}
	}
	return items
}

func peerValue(p []entry, key string) string {
	for _, e := range p {
		if e.key == key {
			return e.value
		}
	}
	return ""
}

// Same client key talking to the same peers is the same tunnel, whatever its title or endpoint.
func (c *TunnelConfig) identity() string {
	var keys []string
	for _, p := range c.Peers {
		keys = append(keys, peerValue(p, "PublicKey"))
	}
	sort.Strings(keys)
	return strings.Join(append([]string{c.get("PrivateKey")}, keys...), "|")
}

func (c *TunnelConfig) endpoint() string {
	for _, p := range c.Peers {
		if v := peerValue(p, "Endpoint"); v != "" {
			return v
		}
	}
	return ""
}

func (c *TunnelConfig) addresses() []netip.Prefix {
	var out []netip.Prefix
	for _, item := range c.all("Address") {
		if p, err := parseAddress(item); err == nil {
			out = append(out, p)
		}
	}
	return out
}

// allowedIPs are the networks routed into the tunnel, host bits cleared.
func (c *TunnelConfig) allowedIPs() []netip.Prefix {
	var out []netip.Prefix
	seen := map[netip.Prefix]bool{}
	for _, p := range c.Peers {
		for _, e := range p {
			if e.key != "AllowedIPs" {
				continue
			}
			for _, item := range splitItems(e.value) {
				if net, ok := parseNetwork(item); ok && !seen[net] {
					seen[net] = true
					out = append(out, net)
				}
			}
		}
	}
	return out
}

func (c *TunnelConfig) hasAddress(v6 bool) bool {
	for _, a := range c.addresses() {
		if a.Addr().Is6() == v6 {
			return true
		}
	}
	return false
}

// fullTunnel reports whether the config sends all traffic of a family into the tunnel.
func (c *TunnelConfig) fullTunnel(v6 bool) bool {
	for _, p := range c.allowedIPs() {
		if p.Bits() == 0 && p.Addr().Is6() == v6 {
			return true
		}
	}
	return false
}

// leaksIPv6: all IPv6 should go into the tunnel, but the tunnel has no IPv6 address to send it
// from, so instead of silently bypassing the VPN it is blackholed.
func (c *TunnelConfig) leaksIPv6() bool {
	return c.fullTunnel(true) && !c.hasAddress(true)
}

func (c *TunnelConfig) mtu() int {
	if v := c.get("MTU"); v != "" && atoi(v) >= 576 {
		return atoi(v)
	}
	return 1420
}

// dns splits DNS = into server addresses and search domains.
func (c *TunnelConfig) dns() (servers []netip.Addr, domains []string) {
	for _, item := range c.all("DNS") {
		if addr, err := netip.ParseAddr(item); err == nil {
			servers = append(servers, addr.Unmap())
		} else {
			domains = append(domains, item)
		}
	}
	return servers, domains
}

func (c *TunnelConfig) render(includeTitle bool) string {
	var b strings.Builder
	if includeTitle && c.Title != "" {
		b.WriteString(titlePrefix + c.Title + "\n")
	}
	b.WriteString("[Interface]\n")
	for _, e := range c.Interface {
		fmt.Fprintf(&b, "%s = %s\n", e.key, e.value)
	}
	for _, p := range c.Peers {
		b.WriteString("\n[Peer]\n")
		for _, e := range p {
			fmt.Fprintf(&b, "%s = %s\n", e.key, e.value)
		}
	}
	return b.String()
}

// uapi renders the device configuration for amneziawg-go. Endpoints must already be resolved
// to addresses: endpoints maps each config Endpoint value to "ip:port".
func (c *TunnelConfig) uapi(fwmark uint32, endpoints map[string]string) (string, error) {
	var b strings.Builder
	line := func(k, v string) { fmt.Fprintf(&b, "%s=%s\n", k, v) }

	for _, e := range c.Interface {
		switch e.key {
		case "PrivateKey", "HeaderProtectionKey":
			h, err := keyHex(e.value)
			if err != nil {
				return "", err
			}
			line(uapiName(e.key), h)
		case "RandomTrailers", "DisableCookies":
			v, _ := parseBool(e.value)
			line(uapiName(e.key), strconv.FormatBool(v))
		case "Address", "DNS", "MTU":
		default:
			line(uapiName(e.key), e.value)
		}
	}
	line("fwmark", strconv.FormatUint(uint64(fwmark), 10))
	line("replace_peers", "true")

	for _, p := range c.Peers {
		pub, err := keyHex(peerValue(p, "PublicKey"))
		if err != nil {
			return "", err
		}
		line("public_key", pub)
		for _, e := range p {
			switch e.key {
			case "PresharedKey":
				h, err := keyHex(e.value)
				if err != nil {
					return "", err
				}
				line("preshared_key", h)
			case "Endpoint":
				addr, ok := endpoints[e.value]
				if !ok {
					return "", fail("Endpoint %s is not resolved", e.value)
				}
				line("endpoint", addr)
			case "PersistentKeepalive":
				line("persistent_keepalive_interval", e.value)
			}
		}
		line("replace_allowed_ips", "true")
		for _, e := range p {
			if e.key != "AllowedIPs" {
				continue
			}
			for _, item := range splitItems(e.value) {
				if net, ok := parseNetwork(item); ok {
					line("allowed_ip", net.String())
				}
			}
		}
	}
	return b.String(), nil
}

var uapiNames = map[string]string{
	"PrivateKey": "private_key", "ListenPort": "listen_port", "HeaderProtectionKey": "header_protection_key",
	"ContentPaddingAddition": "content_padding_addition", "RekeyAfterTime": "rekey_after_time",
	"RekeyTimeout": "rekey_timeout", "RejectAfterTime": "reject_after_time", "KeepaliveTimeout": "keepalive_timeout",
	"MaxHandshakeAttempts": "max_handshake_attempts", "RandomTrailers": "random_trailers", "DisableCookies": "disable_cookies",
}

// Jc → jc, I1 → i1 and so on; the rest are spelled out above.
func uapiName(key string) string {
	if n, ok := uapiNames[key]; ok {
		return n
	}
	return strings.ToLower(key)
}
