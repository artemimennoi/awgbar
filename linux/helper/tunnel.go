package main

import (
	"encoding/base64"
	"errors"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"time"

	"github.com/vishvananda/netlink"
)

// Tunnel is the one tunnel that is up: the userspace tunnel on awgbar0 (awgcore as a process of
// its own, or amneziawg-go inside the helper, see engine.go), plus the rules, routes and DNS
// around it.
type Tunnel struct {
	name     string
	config   *TunnelConfig
	excluded []netip.Prefix

	eng     engine
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
	// awgcore restarts after it exited on its own, for the back-off.
	coreRestarts []time.Time
	// UAPI reads that failed in a row: a core that stopped answering is rebuilt.
	getFailures int
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
	eng, err := startEngine(t.config.mtu())
	if err != nil {
		var he *HelperError
		if errors.As(err, &he) {
			return err
		}
		return fail("Cannot create %s: %v", ifaceName, err)
	}
	uapi, err := t.config.uapi(fwmark, t.endpoints)
	if err == nil {
		err = eng.set(uapi)
	}
	if err != nil {
		eng.close()
		return fail("%s rejected the config: %v", eng.name(), err)
	}
	link, err := configureLink(t.config)
	if err == nil {
		err = addTunnelRoutes(t.config, link)
	}
	if err != nil {
		eng.close()
		return fail("Cannot configure %s: %v", ifaceName, err)
	}
	if err := eng.up(); err != nil {
		eng.close()
		return fail("Cannot start the tunnel: %v", err)
	}
	t.eng = eng
	t.ifindex = link.Attrs().Index
	if c, ok := eng.(*coreEngine); ok {
		go t.watchCore(c)
	}
	return nil
}

// watchCore brings awgcore back if it exits on its own (a crash): the same rebuild as the
// watchdog's, so the rules, the kill switch and DNS stay in place and nothing leaks meanwhile.
// Restarts back off from 1 s to 30 s while it keeps exiting.
func (t *Tunnel) watchCore(c *coreEngine) {
	<-c.done
	if c.stopping.Load() {
		return
	}
	mu.Lock()
	if current != t || t.eng != c {
		mu.Unlock()
		return
	}
	log("%s: awgcore exited (%v), restarting it", t.name, c.err)
	t.eng = nil
	deleteLink()
	delay := t.coreBackoff(time.Now())
	mu.Unlock()
	time.AfterFunc(delay, func() {
		mu.Lock()
		defer mu.Unlock()
		if current != t || t.eng != nil {
			return
		}
		if err := t.rebuild(); err != nil {
			log("restart awgcore: %v", err)
		}
	})
}

// coreBackoff counts a restart at now and says how long to wait before it: 1 s, doubling for
// every other restart in the last minute, at most 30 s.
func (t *Tunnel) coreBackoff(now time.Time) time.Duration {
	recent := t.coreRestarts[:0]
	for _, at := range t.coreRestarts {
		if now.Sub(at) < time.Minute {
			recent = append(recent, at)
		}
	}
	t.coreRestarts = append(recent, now)
	return min(time.Second<<min(len(recent), 5), 30*time.Second)
}

func (t *Tunnel) stopDevice() {
	if t.eng != nil {
		t.eng.close()
		t.eng = nil
	}
	deleteLink()
}

// detach stops the device but leaves the rules, the firewall and DNS as they are, for the
// helper that replaces this one to pick up.
func (t *Tunnel) detach() {
	t.stopDevice()
	log("%s detached for a restart", t.name)
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
	t.getFailures = 0
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
	if t.eng == nil {
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
	if err := t.eng.set(b.String()); err != nil {
		log("kick: set endpoints: %v", err)
	}
	var peers []noiseKey
	for _, p := range t.config.Peers {
		raw, err := base64.StdEncoding.DecodeString(peerValue(p, "PublicKey"))
		if err != nil || len(raw) != 32 {
			continue
		}
		var pk noiseKey
		copy(pk[:], raw)
		peers = append(peers, pk)
	}
	t.eng.kick(peers)
}

// health runs every few seconds. It repairs what others may have changed (rules, DNS) and
// escalates from kick to rebuild when the tunnel doesn't come back.
func (t *Tunnel) health() {
	if t.eng == nil {
		// A rebuild that failed, or awgcore waiting for its restart: try again, at most once
		// every 30 s, so a core that cannot start does not leave the tunnel down for good.
		if time.Since(t.lastRebuild) > 30*time.Second && (defaultRoute(false) != nil || defaultRoute(true) != nil) {
			if err := t.rebuild(); err != nil {
				log("rebuild: %v", err)
			}
		}
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
	if t.getFailures >= 3 && time.Since(t.lastRebuild) > time.Minute {
		log("%s stopped answering", t.eng.name())
		if err := t.rebuild(); err != nil {
			log("rebuild: %v", err)
		}
		return
	}
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
	if t.eng == nil {
		return s
	}
	text, err := t.eng.get()
	if err != nil {
		t.getFailures++
		return s
	}
	t.getFailures = 0
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
