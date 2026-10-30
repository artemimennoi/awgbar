package main

import (
	"errors"
	"net"
	"net/netip"
	"os"

	"github.com/vishvananda/netlink"
	"golang.org/x/sys/unix"
)

// Routing, the same idea as wg-quick on Linux and unlike a route to the server via the current
// gateway: the tunnel's own UDP packets carry fwmark and skip our table, so they follow whatever
// the main table says. NetworkManager can replace the gateway, Wi-Fi can drop during sleep, and
// nothing of ours goes stale, because nothing of ours depends on the gateway.
//
//	prio 16698  to <tunnel DNS> fwmark 0x4157 lookup main  (our own look-ups of the server name)
//	prio 16699  to <tunnel DNS>            lookup 16727    name servers stay in the tunnel
//	prio 16700  to <excluded>              lookup main     split-tunnel exclusions
//	prio 16701  lookup main suppress_prefixlength 0        LAN and other specific routes still work
//	prio 16702  not fwmark 0x4157          lookup 16727    everything else into the tunnel
//
// Table 16727 holds the AllowedIPs routes via awgbar0 plus a blackhole default with a high metric:
// if the interface disappears (helper crash) traffic stops instead of leaking around the VPN.
// The firewall (see firewall.go) is installed and removed together with the rules.
const (
	fwmark          = 0x4157 // "AW"
	routeTable      = 16727
	prioDNSMarked   = 16698
	prioDNS         = 16699
	prioExcluded    = 16700
	prioSuppress    = 16701
	prioTunnel      = 16702
	blackholeMetric = 4096
)

var ourPriorities = map[int]bool{prioDNSMarked: true, prioDNS: true, prioExcluded: true, prioSuppress: true, prioTunnel: true}

func family(v6 bool) int {
	if v6 {
		return netlink.FAMILY_V6
	}
	return netlink.FAMILY_V4
}

func ipNet(p netip.Prefix) *net.IPNet {
	return &net.IPNet{IP: p.Addr().AsSlice(), Mask: net.CIDRMask(p.Bits(), p.Addr().BitLen())}
}

func defaultNet(v6 bool) *net.IPNet {
	if v6 {
		return &net.IPNet{IP: net.IPv6zero, Mask: net.CIDRMask(0, 128)}
	}
	return &net.IPNet{IP: net.IPv4zero.To4(), Mask: net.CIDRMask(0, 32)}
}

// configureLink gives the fresh TUN interface its addresses and MTU and brings it up.
func configureLink(c *TunnelConfig) (netlink.Link, error) {
	link, err := netlink.LinkByName(ifaceName)
	if err != nil {
		return nil, err
	}
	if err := netlink.LinkSetMTU(link, c.mtu()); err != nil {
		return nil, err
	}
	// No automatic link-local address: the server would only drop what the kernel sends from it.
	_ = os.WriteFile("/proc/sys/net/ipv6/conf/"+ifaceName+"/addr_gen_mode", []byte("1"), 0o644)
	for _, a := range c.addresses() {
		addr := &netlink.Addr{IPNet: &net.IPNet{IP: a.Addr().AsSlice(), Mask: net.CIDRMask(a.Bits(), a.Addr().BitLen())}}
		if a.Addr().Is6() {
			// No duplicate address detection: nothing else is on a point-to-point tunnel.
			addr.Flags = unix.IFA_F_NODAD
		}
		if err := netlink.AddrAdd(link, addr); err != nil && !errors.Is(err, unix.EEXIST) {
			return nil, err
		}
	}
	return link, netlink.LinkSetUp(link)
}

// families lists the address families the tunnel routes anything for.
func families(c *TunnelConfig) []bool {
	var v4, v6 bool
	for _, p := range c.allowedIPs() {
		if p.Addr().Is6() {
			v6 = true
		} else {
			v4 = true
		}
	}
	var out []bool
	if v4 {
		out = append(out, false)
	}
	if v6 {
		out = append(out, true)
	}
	return out
}

// addTunnelRoutes fills our table with the AllowedIPs routes via the interface. They vanish
// with the interface, so a rebuild calls this again; the blackholes and rules stay put.
func addTunnelRoutes(c *TunnelConfig, link netlink.Link) error {
	for _, p := range c.allowedIPs() {
		if p.Addr().Is6() && !c.hasAddress(true) {
			continue // blackholed instead, see leaksIPv6
		}
		if !p.Addr().Is6() && !c.hasAddress(false) {
			continue
		}
		route := &netlink.Route{LinkIndex: link.Attrs().Index, Dst: ipNet(p), Table: routeTable, Family: family(p.Addr().Is6())}
		if err := netlink.RouteReplace(route); err != nil {
			return err
		}
	}
	return nil
}

// addRules installs the policy rules, blackholes and the firewall. Exclusions apply to both families.
func addRules(c *TunnelConfig, excluded []netip.Prefix) error {
	servers, _ := c.tunnelDNS()
	for _, v6 := range families(c) {
		if c.fullTunnel(v6) {
			// A bypassed or local network must not take the tunnel's name servers with it. Our own
			// marked look-ups may be sent to the same server, and those go around the tunnel.
			// (A rule's "not" inverts all of its selectors, so this takes two rules.)
			for _, s := range servers {
				if s.Is6() != v6 {
					continue
				}
				dst := ipNet(netip.PrefixFrom(s, s.BitLen()))
				marked := netlink.NewRule()
				marked.Family, marked.Priority, marked.Table, marked.Dst = family(v6), prioDNSMarked, unix.RT_TABLE_MAIN, dst
				marked.Mark = fwmark
				mask := uint32(0xffffffff)
				marked.Mask = &mask
				if err := ruleAdd(marked); err != nil {
					return err
				}
				rule := netlink.NewRule()
				rule.Family, rule.Priority, rule.Table, rule.Dst = family(v6), prioDNS, routeTable, dst
				if err := ruleAdd(rule); err != nil {
					return err
				}
			}
			blackhole := &netlink.Route{Dst: defaultNet(v6), Table: routeTable, Type: unix.RTN_BLACKHOLE,
				Priority: blackholeMetric, Family: family(v6)}
			if err := netlink.RouteReplace(blackhole); err != nil {
				return err
			}
			suppress := netlink.NewRule()
			suppress.Family, suppress.Priority, suppress.Table, suppress.SuppressPrefixlen = family(v6), prioSuppress, unix.RT_TABLE_MAIN, 0
			if err := ruleAdd(suppress); err != nil {
				return err
			}
		}
		tunnel := netlink.NewRule()
		tunnel.Family, tunnel.Priority, tunnel.Table, tunnel.Mark, tunnel.Invert = family(v6), prioTunnel, routeTable, fwmark, true
		mask := uint32(0xffffffff)
		tunnel.Mask = &mask
		if err := ruleAdd(tunnel); err != nil {
			return err
		}
	}
	if err := addExcludedRules(excluded); err != nil {
		return err
	}
	// A kernel without nf_tables still gets the VPN, with the routing blackholes alone; the
	// watchdog keeps trying to add the firewall.
	if err := installFirewall(c, excluded); err != nil {
		log("firewall: %v", err)
	}
	return nil
}

func addExcludedRules(excluded []netip.Prefix) error {
	for _, p := range excluded {
		rule := netlink.NewRule()
		rule.Family, rule.Priority, rule.Table, rule.Dst = family(p.Addr().Is6()), prioExcluded, unix.RT_TABLE_MAIN, ipNet(p)
		if err := ruleAdd(rule); err != nil {
			return err
		}
	}
	return nil
}

// setExcludedRules swaps the exclusions of a running tunnel without touching the other rules.
func setExcludedRules(c *TunnelConfig, excluded []netip.Prefix) error {
	for _, fam := range []int{netlink.FAMILY_V4, netlink.FAMILY_V6} {
		rules, _ := netlink.RuleList(fam)
		for i := range rules {
			if rules[i].Priority == prioExcluded {
				_ = netlink.RuleDel(&rules[i])
			}
		}
	}
	if err := addExcludedRules(excluded); err != nil {
		return err
	}
	if err := installFirewall(c, excluded); err != nil {
		log("firewall: %v", err)
	}
	return nil
}

func ruleAdd(rule *netlink.Rule) error {
	if err := netlink.RuleAdd(rule); err != nil && !errors.Is(err, unix.EEXIST) {
		return err
	}
	return nil
}

// removeRules deletes every rule at our priorities, flushes our table and drops the firewall;
// safe to call when nothing is installed, and the way stale state from a crashed helper is cleaned up.
func removeRules() {
	removeFirewall()
	for _, fam := range []int{netlink.FAMILY_V4, netlink.FAMILY_V6} {
		rules, _ := netlink.RuleList(fam)
		for i := range rules {
			if ourPriorities[rules[i].Priority] {
				_ = netlink.RuleDel(&rules[i])
			}
		}
		routes, _ := netlink.RouteListFiltered(fam, &netlink.Route{Table: routeTable}, netlink.RT_FILTER_TABLE)
		for i := range routes {
			_ = netlink.RouteDel(&routes[i])
		}
	}
}

// rulesPresent checks that someone (a firewall script, `ip rule flush`, `nft flush ruleset`)
// hasn't removed ours.
func rulesPresent(c *TunnelConfig) bool {
	if wantsFirewall(c) && !firewallPresent() {
		return false
	}
	for _, v6 := range families(c) {
		rules, err := netlink.RuleList(family(v6))
		if err != nil {
			return false
		}
		found := false
		for _, r := range rules {
			if r.Priority == prioTunnel && r.Table == routeTable {
				found = true
			}
		}
		if !found {
			return false
		}
	}
	return true
}

// Without it the kernel's reverse path filter drops replies to marked packets.
func enableSrcValidMark() {
	_ = os.WriteFile("/proc/sys/net/ipv4/conf/all/src_valid_mark", []byte("1"), 0o644)
}

// defaultRoute returns the main table's default route for a family, if the machine has one:
// no default route means no network yet (still waking up, Wi-Fi reconnecting).
func defaultRoute(v6 bool) *netlink.Route {
	routes, err := netlink.RouteListFiltered(family(v6), &netlink.Route{Table: unix.RT_TABLE_MAIN}, netlink.RT_FILTER_TABLE)
	if err != nil {
		return nil
	}
	var best *netlink.Route
	for i := range routes {
		r := &routes[i]
		if (r.Dst == nil || isDefault(r.Dst)) && r.Type == unix.RTN_UNICAST && (best == nil || r.Priority < best.Priority) {
			best = r
		}
	}
	return best
}

func isDefault(n *net.IPNet) bool {
	ones, _ := n.Mask.Size()
	return ones == 0
}

func deleteLink() {
	if link, err := netlink.LinkByName(ifaceName); err == nil {
		_ = netlink.LinkDel(link)
	}
}
