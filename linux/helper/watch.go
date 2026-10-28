package main

import (
	"time"

	"github.com/godbus/dbus/v5"
	"github.com/vishvananda/netlink"
	"golang.org/x/sys/unix"
)

// watch turns system events into kicks for the active tunnel:
//   - logind's PrepareForSleep(false): the machine woke up;
//   - a default route or an address appearing or changing (Wi-Fi reconnected, DHCP renewed);
//   - a network interface going up or down.
//
// Events are debounced: waking up produces a burst of them, and the last one is the one that
// counts. Every few seconds health() also checks the tunnel, in case an event was missed.
func watch() {
	events := make(chan string, 64)
	go watchSleep(events)
	go watchNetwork(events)

	debounce := time.NewTimer(time.Hour)
	debounce.Stop()
	pending := ""
	ticker := time.NewTicker(5 * time.Second)
	for {
		select {
		case reason := <-events:
			if reason == "sleep" {
				log("going to sleep")
				continue
			}
			if pending == "" || reason == "resume" {
				pending = reason
			}
			debounce.Reset(2 * time.Second)
		case <-debounce.C:
			withTunnel(func(t *Tunnel) { t.kick(pending) })
			pending = ""
		case <-ticker.C:
			withTunnel(func(t *Tunnel) { t.health() })
		}
	}
}

func send(events chan<- string, reason string) {
	select {
	case events <- reason:
	default:
	}
}

func watchSleep(events chan<- string) {
	for {
		conn, err := dbus.ConnectSystemBus()
		if err == nil {
			err = conn.AddMatchSignal(
				dbus.WithMatchSender("org.freedesktop.login1"),
				dbus.WithMatchObjectPath("/org/freedesktop/login1"),
				dbus.WithMatchInterface("org.freedesktop.login1.Manager"),
				dbus.WithMatchMember("PrepareForSleep"),
			)
		}
		if err != nil {
			log("logind: %v", err)
			time.Sleep(30 * time.Second)
			continue
		}
		signals := make(chan *dbus.Signal, 8)
		conn.Signal(signals)
		for s := range signals {
			if len(s.Body) == 1 {
				if sleeping, ok := s.Body[0].(bool); ok {
					if sleeping {
						send(events, "sleep")
					} else {
						send(events, "resume")
					}
				}
			}
		}
		// The channel closes when the bus connection drops (dbus restarted).
		conn.Close()
	}
}

func watchNetwork(events chan<- string) {
	routes := make(chan netlink.RouteUpdate, 64)
	addrs := make(chan netlink.AddrUpdate, 64)
	links := make(chan netlink.LinkUpdate, 64)
	if err := netlink.RouteSubscribe(routes, nil); err != nil {
		log("route subscribe: %v", err)
	}
	if err := netlink.AddrSubscribe(addrs, nil); err != nil {
		log("addr subscribe: %v", err)
	}
	if err := netlink.LinkSubscribe(links, nil); err != nil {
		log("link subscribe: %v", err)
	}
	operState := map[int]netlink.LinkOperState{}
	for {
		select {
		case u := <-routes:
			if u.Table == unix.RT_TABLE_MAIN && (u.Dst == nil || isDefault(u.Dst)) {
				send(events, "default route changed")
			}
		case u := <-addrs:
			if !u.LinkAddress.IP.IsLinkLocalUnicast() && !isOurLink(u.LinkIndex) {
				send(events, "address changed")
			}
		case u := <-links:
			attrs := u.Attrs()
			if attrs.Name == ifaceName {
				continue
			}
			if prev, seen := operState[attrs.Index]; seen && prev != attrs.OperState {
				send(events, "link "+attrs.Name+" "+attrs.OperState.String())
			}
			operState[attrs.Index] = attrs.OperState
		}
	}
}

func isOurLink(index int) bool {
	link, err := netlink.LinkByIndex(index)
	return err == nil && link.Attrs().Name == ifaceName
}
