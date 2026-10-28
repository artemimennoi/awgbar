package main

import (
	"encoding/base64"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/ipc"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
	"github.com/vishvananda/netlink"
)

// Tunnel is the one tunnel that is up: amneziawg-go running inside the helper on awgbar0,
// plus the rules, routes and DNS around it.
type Tunnel struct {
	name     string
	config   *TunnelConfig
	excluded []netip.Prefix

	dev     *device.Device
	uapi    net.Listener
	ifindex int
	dns     *dnsState
	// Config Endpoint value ("vpn.example.com:51820") → resolved "ip:port".
	endpoints map[string]string

	// Watchdog bookkeeping, see health().
	startedAt   time.Time
	kickedAt    time.Time
	lastKick    time.Time
	lastRebuild time.Time
	prevTx      int64
	prevRx      int64
}

func startTunnel(name string, c *TunnelConfig, excluded []netip.Prefix) (*Tunnel, error) {
	t := &Tunnel{name: name, config: c, excluded: excluded, endpoints: map[string]string{}, startedAt: time.Now()}
	if err := t.resolveEndpoints(true); err != nil {
		return nil, err
	}
	// Leftovers of a crashed helper or another awgbar0 would make the steps below fail.
	deleteLink()
	removeRules()
	enableSrcValidMark()
	if err := addRules(c, excluded); err != nil {
		removeRules()
		return nil, fail("Cannot add routing rules: %v", err)
	}
	if err := t.startDevice(); err != nil {
		removeRules()
		return nil, err
	}
	dns, err := applyDNS(c, t.ifindex)
	t.dns = dns
	if err != nil {
		t.stop()
		return nil, fail("Cannot set DNS: %v", err)
	}
	log("%s up on %s, endpoints %v", name, ifaceName, t.endpoints)
	return t, nil
}

// resolveEndpoints turns host names into addresses. At connect time a failure is an error;
// on a later re-resolve the previous address is kept.
func (t *Tunnel) resolveEndpoints(strict bool) error {
	for _, p := range t.config.Peers {
		value := peerValue(p, "Endpoint")
		if value == "" {
			continue
		}
		host, port, _ := net.SplitHostPort(value)
		addr, err := resolveOutside(host)
		if err != nil {
			if strict {
				return fail("Cannot resolve %s: %v", host, err)
			}
			log("re-resolve %s: %v, keeping %s", host, err, t.endpoints[value])
			continue
		}
		p, _ := strconv.ParseUint(port, 10, 16)
		t.endpoints[value] = netip.AddrPortFrom(addr, uint16(p)).String()
	}
	return nil
}

func (t *Tunnel) startDevice() error {
	tdev, err := tun.CreateTUN(ifaceName, t.config.mtu())
	if err != nil {
		return fail("Cannot create %s: %v", ifaceName, err)
	}
	logger := &device.Logger{
		Verbosef: func(format string, args ...any) { log("awg: "+format, args...) },
		Errorf:   func(format string, args ...any) { log("awg error: "+format, args...) },
	}
	dev := device.NewDevice(tdev, conn.NewDefaultBind(), logger)
	uapi, err := t.config.uapi(fwmark, t.endpoints)
	if err == nil {
		err = dev.IpcSet(uapi)
	}
	if err != nil {
		dev.Close()
		return fail("amneziawg-go rejected the config: %v", err)
	}
	link, err := configureLink(t.config)
	if err == nil {
		err = addTunnelRoutes(t.config, link)
	}
	if err != nil {
		dev.Close()
		return fail("Cannot configure %s: %v", ifaceName, err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return fail("Cannot start the tunnel: %v", err)
	}
	t.dev = dev
	t.ifindex = link.Attrs().Index
	t.listenUAPI()
	return nil
}

// listenUAPI exposes the usual /var/run/amneziawg/awgbar0.sock, so `awg show awgbar0` works for
// debugging. Best effort.
func (t *Tunnel) listenUAPI() {
	file, err := ipc.UAPIOpen(ifaceName)
	if err != nil {
		return
	}
	l, err := ipc.UAPIListen(ifaceName, file)
	if err != nil {
		return
	}
	t.uapi = l
	dev := t.dev
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go dev.IpcHandle(c)
		}
	}()
}

func (t *Tunnel) stopDevice() {
	if t.uapi != nil {
		t.uapi.Close()
		t.uapi = nil
	}
	if t.dev != nil {
		// Closing the TUN deletes awgbar0 and with it the routes via it.
		t.dev.Close()
		t.dev = nil
	}
	deleteLink()
}

func (t *Tunnel) stop() {
	// resolved forgets a link's settings when it goes away, but needs the index to revert cleanly.
	t.dns.revert(t.ifindex)
	t.stopDevice()
	removeRules()
	log("%s down", t.name)
}

// rebuild recreates the device and interface while the rules and blackholes stay in place,
// so nothing leaks around the VPN in between. The last resort when a kick didn't help.
func (t *Tunnel) rebuild() error {
	log("%s: rebuilding the device", t.name)
	t.lastRebuild = time.Now()
	t.stopDevice()
	_ = t.resolveEndpoints(false)
	if err := t.startDevice(); err != nil {
		return err
	}
	t.dns.reapply(t.ifindex)
	return nil
}

func (st *dnsState) reapply(ifindex int) {
	if st == nil || len(st.servers) == 0 {
		return
	}
	if st.resolved {
		_ = st.applyResolved(ifindex)
	} else {
		_ = st.writeResolvConf()
	}
}

// kick is what a user would do by hand after sleep: look the server up again, rebind the UDP
// socket to the new network (dropping a cached source address that no longer exists) and start a
// fresh handshake. Cheap, and harmless when nothing was wrong.
func (t *Tunnel) kick(reason string) {
	if t.dev == nil {
		return
	}
	if defaultRoute(false) == nil && defaultRoute(true) == nil {
		log("kick (%s): no default route yet, waiting for the network", reason)
		return
	}
	log("kick (%s)", reason)
	t.lastKick = time.Now()
	t.kickedAt = t.lastKick
	_ = t.resolveEndpoints(false)

	var b strings.Builder
	for _, p := range t.config.Peers {
		pub, _ := keyHex(peerValue(p, "PublicKey"))
		b.WriteString("public_key=" + pub + "\nupdate_only=true\n")
		if ep := t.endpoints[peerValue(p, "Endpoint")]; ep != "" {
			// A new endpoint object also drops the sticky source address.
			b.WriteString("endpoint=" + ep + "\n")
		}
	}
	if err := t.dev.IpcSet(b.String()); err != nil {
		log("kick: set endpoints: %v", err)
	}
	if err := t.dev.BindUpdate(); err != nil {
		log("kick: rebind: %v", err)
	}
	for _, p := range t.config.Peers {
		raw, err := base64.StdEncoding.DecodeString(peerValue(p, "PublicKey"))
		if err != nil || len(raw) != 32 {
			continue
		}
		var pk device.NoisePublicKey
		copy(pk[:], raw)
		if peer := t.dev.LookupPeer(pk); peer != nil {
			_ = peer.SendHandshakeInitiation(false)
		}
	}
}

// health runs every few seconds. It repairs what others may have changed (rules, DNS) and
// escalates from kick to rebuild when the tunnel doesn't come back.
func (t *Tunnel) health() {
	if t.dev == nil {
		return
	}
	if !rulesPresent(t.config) {
		log("rules were removed, adding them again")
		_ = addRules(t.config, t.excluded)
	}
	if link, err := netlink.LinkByName(ifaceName); err != nil || link.Attrs().Index != t.ifindex {
		log("%s disappeared", ifaceName)
		if err := t.rebuild(); err != nil {
			log("rebuild: %v", err)
		}
		return
	}
	t.dns.check(t.ifindex)

	stats := t.stats()
	now := time.Now()
	handshake := time.Time{}
	if stats.LastHandshake != nil {
		handshake = time.Unix(*stats.LastHandshake, 0)
	}
	// No handshake yet counts from the connect, so a fresh tunnel gets time to make its first one.
	session := handshake
	if session.IsZero() {
		session = t.startedAt
	}

	if !t.kickedAt.IsZero() {
		switch {
		// Handshake times have whole seconds.
		case !handshake.IsZero() && !handshake.Before(t.kickedAt.Truncate(time.Second)):
			log("handshake %.1fs after the kick", handshake.Sub(t.kickedAt).Seconds())
			t.kickedAt = time.Time{}
		case now.Sub(t.kickedAt) > 20*time.Second && now.Sub(t.lastRebuild) > time.Minute:
			if defaultRoute(false) != nil || defaultRoute(true) != nil {
				if err := t.rebuild(); err != nil {
					log("rebuild: %v", err)
				}
				t.kick("after rebuild")
			}
		}
	} else if stats.TxBytes > t.prevTx && stats.RxBytes == t.prevRx &&
		now.Sub(session) > 3*time.Minute && now.Sub(t.lastKick) > 30*time.Second {
		// Sending into the tunnel, nothing comes back, and the session is older than WireGuard's
		// own rekey: the events that should have told us about a network change were missed.
		t.kick("traffic without replies")
	}
	t.prevTx, t.prevRx = stats.TxBytes, stats.RxBytes
}

func (t *Tunnel) stats() TunnelStats {
	var s TunnelStats
	if t.dev == nil {
		return s
	}
	text, err := t.dev.IpcGet()
	if err != nil {
		return s
	}
	var latest int64
	for _, line := range strings.Split(text, "\n") {
		key, value, _ := strings.Cut(line, "=")
		switch key {
		case "endpoint":
			if s.Endpoint == "" {
				s.Endpoint = value
			}
		case "last_handshake_time_sec":
			if v, _ := strconv.ParseInt(value, 10, 64); v > latest {
				latest = v
			}
		case "rx_bytes":
			v, _ := strconv.ParseInt(value, 10, 64)
			s.RxBytes += v
		case "tx_bytes":
			v, _ := strconv.ParseInt(value, 10, 64)
			s.TxBytes += v
		}
	}
	if latest > 0 {
		s.LastHandshake = &latest
	}
	return s
}
