package main

import (
	"net/netip"

	"github.com/google/nftables"
	"github.com/google/nftables/binaryutil"
	"github.com/google/nftables/expr"
	"golang.org/x/sys/unix"
)

// The kill switch. Policy routing alone can be talked around: a DHCP server on the local network
// can push routes more specific than ours (option 121, "TunnelVision"), and a v4-only config
// leaves IPv6 alone. While a full-tunnel config is up (or the helper is bringing it back), an
// nftables output chain lets out only:
//
//	loopback and awgbar0
//	packets with our fwmark: the tunnel's own UDP and the server look-ups around it
//	local networks, multicast and the "Bypass VPN" subnets
//	DHCP
//
// and drops the rest, including the tunnel's DNS servers on any other interface. The marked
// packets are what keeps this from blocking the reconnect.
const firewallTable = "awgbar"

// Local networks stay reachable around the VPN, as they do through the routing rules.
var localNetworks = []netip.Prefix{
	netip.MustParsePrefix("10.0.0.0/8"),
	netip.MustParsePrefix("172.16.0.0/12"),
	netip.MustParsePrefix("192.168.0.0/16"),
	netip.MustParsePrefix("169.254.0.0/16"),
	netip.MustParsePrefix("224.0.0.0/4"),
	netip.MustParsePrefix("255.255.255.255/32"),
	netip.MustParsePrefix("fc00::/7"),
	netip.MustParsePrefix("fe80::/10"),
	netip.MustParsePrefix("ff00::/8"),
}

// wantsFirewall: only a config that sends everything into the tunnel gets a kill switch;
// a split-tunnel config routes just its networks and leaves the rest alone.
func wantsFirewall(c *TunnelConfig) bool {
	return c.fullTunnel(false) || c.fullTunnel(true)
}

func installFirewall(c *TunnelConfig, excluded []netip.Prefix) error {
	if !wantsFirewall(c) {
		removeFirewall()
		return nil
	}
	conn, err := nftables.New()
	if err != nil {
		return err
	}
	table := &nftables.Table{Family: nftables.TableFamilyINet, Name: firewallTable}
	// Adding an existing table is a no-op, and deleting a missing one would fail the batch,
	// so make sure it exists first and then replace it in one transaction.
	conn.AddTable(table)
	if err := conn.Flush(); err != nil {
		return err
	}
	conn.DelTable(table)
	conn.AddTable(table)
	accept := nftables.ChainPolicyAccept
	chain := conn.AddChain(&nftables.Chain{
		Name: "output", Table: table, Type: nftables.ChainTypeFilter,
		Hooknum: nftables.ChainHookOutput, Priority: nftables.ChainPriorityFilter, Policy: &accept,
	})
	rule := func(verdict expr.VerdictKind, match ...expr.Any) {
		conn.AddRule(&nftables.Rule{Table: table, Chain: chain, Exprs: append(match, &expr.Verdict{Kind: verdict})})
	}

	rule(expr.VerdictAccept, matchOIF("lo")...)
	rule(expr.VerdictAccept, matchOIF(ifaceName)...)
	rule(expr.VerdictAccept, &expr.Meta{Key: expr.MetaKeyMARK, Register: 1},
		&expr.Cmp{Op: expr.CmpOpEq, Register: 1, Data: binaryutil.NativeEndian.PutUint32(fwmark)})
	// The tunnel's name servers only through the tunnel, even if a local or bypassed network covers them.
	servers, _ := c.tunnelDNS()
	for _, s := range servers {
		rule(expr.VerdictDrop, matchDst(netip.PrefixFrom(s, s.BitLen()))...)
	}
	for _, p := range append(append([]netip.Prefix{}, localNetworks...), excluded...) {
		rule(expr.VerdictAccept, matchDst(p)...)
	}
	rule(expr.VerdictAccept,
		&expr.Meta{Key: expr.MetaKeyL4PROTO, Register: 1},
		&expr.Cmp{Op: expr.CmpOpEq, Register: 1, Data: []byte{unix.IPPROTO_UDP}},
		&expr.Payload{DestRegister: 1, Base: expr.PayloadBaseTransportHeader, Offset: 2, Len: 2},
		&expr.Cmp{Op: expr.CmpOpEq, Register: 1, Data: binaryutil.BigEndian.PutUint16(67)})
	rule(expr.VerdictDrop)
	return conn.Flush()
}

func removeFirewall() {
	conn, err := nftables.New()
	if err != nil {
		return
	}
	if _, err := conn.ListTableOfFamily(firewallTable, nftables.TableFamilyINet); err != nil {
		return
	}
	conn.DelTable(&nftables.Table{Family: nftables.TableFamilyINet, Name: firewallTable})
	_ = conn.Flush()
}

func firewallPresent() bool {
	conn, err := nftables.New()
	if err != nil {
		return false
	}
	_, err = conn.ListTableOfFamily(firewallTable, nftables.TableFamilyINet)
	return err == nil
}

func matchOIF(name string) []expr.Any {
	data := make([]byte, unix.IFNAMSIZ)
	copy(data, name)
	return []expr.Any{
		&expr.Meta{Key: expr.MetaKeyOIFNAME, Register: 1},
		&expr.Cmp{Op: expr.CmpOpEq, Register: 1, Data: data},
	}
}

// matchDst: the packet's family and its destination address within p.
func matchDst(p netip.Prefix) []expr.Any {
	p = p.Masked()
	proto, offset := byte(unix.NFPROTO_IPV4), uint32(16)
	if p.Addr().Is6() {
		proto, offset = unix.NFPROTO_IPV6, 24
	}
	addr := p.Addr().AsSlice()
	out := []expr.Any{
		&expr.Meta{Key: expr.MetaKeyNFPROTO, Register: 1},
		&expr.Cmp{Op: expr.CmpOpEq, Register: 1, Data: []byte{proto}},
		&expr.Payload{DestRegister: 1, Base: expr.PayloadBaseNetworkHeader, Offset: offset, Len: uint32(len(addr))},
	}
	if p.Bits() < len(addr)*8 {
		mask := ipNet(p).Mask
		out = append(out, &expr.Bitwise{SourceRegister: 1, DestRegister: 1, Len: uint32(len(addr)), Mask: mask, Xor: make([]byte, len(addr))})
	}
	return append(out, &expr.Cmp{Op: expr.CmpOpEq, Register: 1, Data: addr})
}
