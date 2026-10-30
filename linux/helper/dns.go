package main

import (
	"bytes"
	"context"
	"fmt"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/godbus/dbus/v5"
	"golang.org/x/sys/unix"
)

// DNS while connected. With systemd-resolved (Ubuntu, and Debian with the systemd-resolved
// package) the servers go on awgbar0 itself with the "~." routing domain, and other links stop
// being DNS default routes, so queries don't leak to the router. NetworkManager never touches
// awgbar0; it may re-enable another link's default route after a reconnect, which the watchdog
// undoes. Without resolved, /etc/resolv.conf is replaced and put back on disconnect; if
// NetworkManager rewrites it meanwhile, the watchdog takes that as the new original and writes
// ours again.
type dnsState struct {
	servers []netip.Addr
	domains []string
	// resolved mode: links whose DefaultRoute we turned off, to turn back on.
	mutedLinks map[int32]bool
	// Links resolved won't let us change, reported once.
	refusedLinks map[int32]bool
	resolved     bool
}

const (
	resolvConf     = "/etc/resolv.conf"
	resolvedName   = "org.freedesktop.resolve1"
	resolvedPath   = "/org/freedesktop/resolve1"
	resolvedIface  = "org.freedesktop.resolve1.Manager"
	ourResolvMark  = "# Written by AWG Bar while the VPN is connected"
	resolvedStubNS = "127.0.0.53"
)

var (
	resolvBackup     = runtimeDir + "/resolv.conf.orig"
	resolvLinkBackup = runtimeDir + "/resolv.conf.link"
)

func systemBus() (*dbus.Conn, error) { return dbus.SystemBus() }

// usingResolved: resolved is running and glibc actually asks it (the stub in resolv.conf).
func usingResolved() bool {
	conn, err := systemBus()
	if err != nil {
		return false
	}
	var owned bool
	if err := conn.BusObject().Call("org.freedesktop.DBus.NameHasOwner", 0, resolvedName).Store(&owned); err != nil || !owned {
		return false
	}
	data, err := os.ReadFile(resolvConf)
	return err == nil && bytes.Contains(data, []byte(resolvedStubNS))
}

func resolvedManager() (dbus.BusObject, error) {
	conn, err := systemBus()
	if err != nil {
		return nil, err
	}
	return conn.Object(resolvedName, resolvedPath), nil
}

type resolvedAddr struct {
	Family  int32
	Address []byte
}

type resolvedDomain struct {
	Domain      string
	RoutingOnly bool
}

func applyDNS(c *TunnelConfig, ifindex int) (*dnsState, error) {
	servers, domains := c.tunnelDNS()
	// Links a previous helper muted before an upgrade or a crash are ours to turn back on.
	st := &dnsState{servers: servers, domains: domains, mutedLinks: loadMutedLinks(), refusedLinks: map[int32]bool{}}
	if len(servers) == 0 {
		return st, nil
	}
	if usingResolved() {
		st.resolved = true
		return st, st.applyResolved(ifindex)
	}
	return st, st.writeResolvConf()
}

func (st *dnsState) applyResolved(ifindex int) error {
	m, err := resolvedManager()
	if err != nil {
		return err
	}
	var addrs []resolvedAddr
	for _, s := range st.servers {
		fam := int32(unix.AF_INET)
		if s.Is6() {
			fam = unix.AF_INET6
		}
		addrs = append(addrs, resolvedAddr{fam, s.AsSlice()})
	}
	// "." with the routing-only flag is what resolvectl shows as "~.".
	doms := []resolvedDomain{{".", true}}
	for _, d := range st.domains {
		doms = append(doms, resolvedDomain{d, false})
	}
	idx := int32(ifindex)
	if err := m.Call(resolvedIface+".SetLinkDNS", 0, idx, addrs).Err; err != nil {
		return fmt.Errorf("resolved SetLinkDNS: %w", err)
	}
	if err := m.Call(resolvedIface+".SetLinkDomains", 0, idx, doms).Err; err != nil {
		return fmt.Errorf("resolved SetLinkDomains: %w", err)
	}
	if err := m.Call(resolvedIface+".SetLinkDefaultRoute", 0, idx, true).Err; err != nil {
		return fmt.Errorf("resolved SetLinkDefaultRoute: %w", err)
	}
	_ = m.Call(resolvedIface+".FlushCaches", 0).Err
	st.muteOtherLinks(ifindex)
	return nil
}

// muteOtherLinks turns off DNS default route on every other link that has DNS servers.
// Returns whether it had to change anything, which after the first time means someone
// (NetworkManager after a reconnect) turned one back on.
func (st *dnsState) muteOtherLinks(ifindex int) bool {
	conn, err := systemBus()
	if err != nil {
		return false
	}
	m := conn.Object(resolvedName, resolvedPath)
	ifaces, _ := net.Interfaces()
	changed := false
	for _, ifc := range ifaces {
		if ifc.Index == ifindex || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		var path dbus.ObjectPath
		if err := m.Call(resolvedIface+".GetLink", 0, int32(ifc.Index)).Store(&path); err != nil {
			continue
		}
		link := conn.Object(resolvedName, path)
		dr, err := link.GetProperty("org.freedesktop.resolve1.Link.DefaultRoute")
		if err != nil {
			continue
		}
		dns, err := link.GetProperty("org.freedesktop.resolve1.Link.DNS")
		if err != nil {
			continue
		}
		if on, _ := dr.Value().(bool); !on || reflect.ValueOf(dns.Value()).Kind() != reflect.Slice || reflect.ValueOf(dns.Value()).Len() == 0 {
			continue
		}
		if err := m.Call(resolvedIface+".SetLinkDefaultRoute", 0, int32(ifc.Index), false).Err; err != nil {
			// resolved refuses for links systemd-networkd manages; NetworkManager's links are fine.
			if !st.refusedLinks[int32(ifc.Index)] {
				st.refusedLinks[int32(ifc.Index)] = true
				log("dns: cannot mute %s: %v", ifc.Name, err)
			}
			continue
		}
		st.mutedLinks[int32(ifc.Index)] = true
		changed = true
		log("dns: muted default route on %s", ifc.Name)
	}
	if changed {
		saveMutedLinks(st.mutedLinks)
	}
	return changed
}

// The muted links are kept in /run/awgbar too: after an upgrade or a crash the next helper
// must still know which links to turn back on.
var mutedLinksPath = runtimeDir + "/dns-muted"

func saveMutedLinks(links map[int32]bool) {
	var b strings.Builder
	for idx := range links {
		fmt.Fprintf(&b, "%d\n", idx)
	}
	_ = writeAtomic(mutedLinksPath, []byte(b.String()), 0o600)
}

func loadMutedLinks() map[int32]bool {
	links := map[int32]bool{}
	data, _ := os.ReadFile(mutedLinksPath)
	for _, f := range strings.Fields(string(data)) {
		if idx, err := strconv.Atoi(f); err == nil {
			links[int32(idx)] = true
		}
	}
	return links
}

// unmuteLinks turns DNS default route back on for the links we turned it off on.
func unmuteLinks(links map[int32]bool) {
	if len(links) > 0 {
		if m, err := resolvedManager(); err == nil {
			for idx := range links {
				_ = m.Call(resolvedIface+".SetLinkDefaultRoute", 0, idx, true).Err
			}
			_ = m.Call(resolvedIface+".FlushCaches", 0).Err
		}
	}
	_ = os.Remove(mutedLinksPath)
}

func (st *dnsState) writeResolvConf() error {
	if err := backupResolvConf(); err != nil {
		return err
	}
	return writeAtomic(resolvConf, []byte(st.resolvConfText()), 0o644)
}

func (st *dnsState) resolvConfText() string {
	var b strings.Builder
	b.WriteString(ourResolvMark + "\n")
	for _, s := range st.servers {
		fmt.Fprintf(&b, "nameserver %s\n", s)
	}
	if len(st.domains) > 0 {
		fmt.Fprintf(&b, "search %s\n", strings.Join(st.domains, " "))
	}
	return b.String()
}

// backupResolvConf saves the current resolv.conf (a file or NetworkManager's symlink) unless
// it is ours.
func backupResolvConf() error {
	if target, err := os.Readlink(resolvConf); err == nil {
		_ = os.Remove(resolvBackup)
		return writeAtomic(resolvLinkBackup, []byte(target), 0o600)
	}
	data, err := os.ReadFile(resolvConf)
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	if bytes.HasPrefix(data, []byte(ourResolvMark)) {
		return nil
	}
	_ = os.Remove(resolvLinkBackup)
	return writeAtomic(resolvBackup, data, 0o600)
}

// restoreResolvConf puts back whatever backupResolvConf saved; also run at startup after a crash.
func restoreResolvConf() {
	if target, err := os.ReadFile(resolvLinkBackup); err == nil {
		tmp := resolvConf + ".awgbar"
		_ = os.Remove(tmp)
		if os.Symlink(string(target), tmp) == nil {
			_ = os.Rename(tmp, resolvConf)
		}
		_ = os.Remove(resolvLinkBackup)
		log("dns: restored resolv.conf link")
	}
	if data, err := os.ReadFile(resolvBackup); err == nil {
		_ = writeAtomic(resolvConf, data, 0o644)
		_ = os.Remove(resolvBackup)
		log("dns: restored resolv.conf")
	}
}

func (st *dnsState) revert(ifindex int) {
	if st == nil || len(st.servers) == 0 {
		return
	}
	if !st.resolved {
		restoreResolvConf()
		return
	}
	m, err := resolvedManager()
	if err != nil {
		return
	}
	if ifindex > 0 {
		_ = m.Call(resolvedIface+".RevertLink", 0, int32(ifindex)).Err
	}
	unmuteLinks(st.mutedLinks)
	st.mutedLinks = map[int32]bool{}
}

// check re-applies DNS that someone else changed; returns whether it had to.
func (st *dnsState) check(ifindex int) bool {
	if st == nil || len(st.servers) == 0 {
		return false
	}
	if st.resolved {
		return st.muteOtherLinks(ifindex)
	}
	data, _ := os.ReadFile(resolvConf)
	if string(data) == st.resolvConfText() {
		return false
	}
	log("dns: resolv.conf was rewritten, applying ours again")
	_ = st.writeResolvConf()
	return true
}

func writeAtomic(path string, data []byte, mode os.FileMode) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), ".awgbar-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Chmod(mode); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}

// resolveOutside looks up the server's host name without the tunnel: while connected, normal
// DNS goes into the tunnel, which is exactly what is broken when the server's address changed.
// It asks the name servers of the physical network directly, over a socket carrying our fwmark,
// so the query goes out the way the tunnel's own packets do.
func resolveOutside(host string) (netip.Addr, error) {
	if addr, err := netip.ParseAddr(host); err == nil {
		return addr.Unmap(), nil
	}
	servers := originalNameservers()
	if usingResolved() {
		servers = resolvedUplinkServers()
	}
	if len(servers) == 0 {
		return netip.Addr{}, fmt.Errorf("no name servers to resolve %s", host)
	}
	// One server at a time: a stale one (a network we just left) must not hide a working one.
	var lastErr error
	for _, server := range servers {
		resolver := &net.Resolver{PreferGo: true, Dial: markedDial(server)}
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		addrs, err := resolver.LookupNetIP(ctx, "ip", host)
		cancel()
		if err != nil || len(addrs) == 0 {
			lastErr = err
			continue
		}
		for _, a := range addrs {
			if a.Unmap().Is4() {
				return a.Unmap(), nil
			}
		}
		return addrs[0], nil
	}
	return netip.Addr{}, lastErr
}

// resolvedUplinkServers lists the name servers resolved knows, global and per link, except ours.
func resolvedUplinkServers() []string {
	m, err := resolvedManager()
	if err != nil {
		return nil
	}
	v, err := m.GetProperty(resolvedIface + ".DNS")
	if err != nil {
		return nil
	}
	var entries []struct {
		Ifindex int32
		Family  int32
		Address []byte
	}
	if err := dbus.Store([]any{v.Value()}, &entries); err != nil {
		return nil
	}
	ours := int32(-1)
	if ifc, err := net.InterfaceByName(ifaceName); err == nil {
		ours = int32(ifc.Index)
	}
	uplink := int32(-1)
	if r := defaultRoute(false); r != nil {
		uplink = int32(r.LinkIndex)
	}
	// The default route's link first: that's the network we are actually on.
	var first, rest []string
	for _, e := range entries {
		addr, ok := netip.AddrFromSlice(e.Address)
		if !ok || e.Ifindex == ours || addr.IsLoopback() {
			continue
		}
		server := net.JoinHostPort(addr.Unmap().String(), "53")
		if e.Ifindex == uplink {
			first = append(first, server)
		} else {
			rest = append(rest, server)
		}
	}
	return append(first, rest...)
}

// originalNameservers: the ones from before we replaced resolv.conf, or the current ones.
func originalNameservers() []string {
	data, err := os.ReadFile(resolvBackup)
	if err != nil {
		data, _ = os.ReadFile(resolvConf)
	}
	var out []string
	for _, line := range strings.Split(string(data), "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 2 && fields[0] == "nameserver" {
			if addr, err := netip.ParseAddr(fields[1]); err == nil {
				out = append(out, net.JoinHostPort(addr.String(), "53"))
			}
		}
	}
	return out
}

// markedDial sends the resolver's queries to one server, bypassing the tunnel.
func markedDial(server string) func(ctx context.Context, network, address string) (net.Conn, error) {
	return func(ctx context.Context, network, _ string) (net.Conn, error) {
		d := net.Dialer{Control: func(_, _ string, c syscall.RawConn) error {
			var serr error
			err := c.Control(func(fd uintptr) { serr = unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_MARK, fwmark) })
			if err != nil {
				return err
			}
			return serr
		}}
		return d.DialContext(ctx, network, server)
	}
}
