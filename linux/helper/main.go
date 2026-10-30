// awgbar-helper is the root service behind AWG Bar on Linux. It runs amneziawg-go in-process,
// owns the routing rules and DNS while a tunnel is up, and watches sleep and network changes so
// the tunnel comes back on its own. The app talks to it over /run/awgbar/helper.sock.
package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

func main() {
	if len(os.Args) >= 2 {
		switch os.Args[1] {
		case "--print-config":
			// Debugging aid: the canonical config as the helper reads it.
			if len(os.Args) != 3 {
				usage()
			}
			os.Exit(printConfig(os.Args[2]))
		case "--kick":
			// Debugging aid: do what happens after waking up, for the running helper.
			os.Exit(sendCommand("kick"))
		case "--prepare-restart":
			// For the package's postinst: the restart that follows keeps the tunnel's rules.
			os.Exit(sendCommand("prepareRestart"))
		case "--cleanup":
			// For package removal: undo anything a crashed helper left behind.
			cleanUpStaleState(false)
			_ = os.Remove(activePath)
			return
		default:
			usage()
		}
	}

	_, err := os.Stat(activePath)
	// After a crash or an upgrade the rules, the firewall and DNS stay until the tunnel is back:
	// nothing leaks around the VPN while the helper reconnects.
	cleanUpStaleState(err == nil)
	go reconnect()
	go watch()
	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
		<-sig
		mu.Lock()
		// Keep the active marker: systemd keeps /run/awgbar across a restart (upgrade, crash),
		// and the next start reconnects. A stop or reboot clears the directory.
		if current != nil && time.Now().Before(keepUntil) {
			current.detach()
		} else {
			down(false)
		}
		os.Exit(0)
	}()
	if err := serve(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: awgbar-helper [--print-config <tunnel> | --kick | --cleanup]")
	os.Exit(2)
}

// cleanUpStaleState removes what a helper that died or restarted left behind. keep leaves the
// rules, the firewall and DNS for the reconnect; reconnect() or a disconnect cleans them up.
func cleanUpStaleState(keep bool) {
	deleteLink()
	if keep {
		return
	}
	removeRules()
	unmuteLinks(loadMutedLinks())
	restoreResolvConf()
}

func reconnect() {
	data, err := os.ReadFile(activePath)
	if err != nil {
		return
	}
	name := strings.TrimSpace(string(data))
	if !isValidTunnelName(name) {
		return
	}
	mu.Lock()
	defer mu.Unlock()
	reconnecting = name
	// Right after a restart the network may not be there yet, and after a crash the server may
	// be down for a while: keep blocking and trying until the tunnel is back or the user
	// disconnects, as a kill switch should. The app shows the tunnel as on meanwhile.
	for delay := 3 * time.Second; ; delay = min(2*delay, 30*time.Second) {
		if _, err := os.Stat(activePath); err != nil || current != nil || reconnecting != name {
			return // the user disconnected or connected something else meanwhile
		}
		c, err := loadConfig(name)
		if err != nil {
			// Deleted or unreadable: it will never come back, so stop blocking.
			log("reconnect %s: %v, giving up", name, err)
			cleanUpStaleState(false)
			_ = os.Remove(activePath)
			reconnecting = ""
			return
		}
		if err = up(name); err == nil {
			log("reconnected %s", name)
			reconnecting = ""
			return
		}
		log("reconnect %s: %v", name, err)
		// A failed start removes the rules; keep blocking until the next attempt.
		excluded, _ := parseNetworkList(strings.Join(loadExcluded(), "\n"))
		_ = addRules(c, excluded)
		mu.Unlock()
		time.Sleep(delay)
		mu.Lock()
	}
}

func printConfig(name string) int {
	if !isValidTunnelName(name) {
		fmt.Fprintln(os.Stderr, "error: invalid tunnel name")
		return 1
	}
	c, err := loadConfig(name)
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		return 1
	}
	fmt.Print(c.render(false))
	if servers, _ := c.dns(); len(servers) == 0 {
		if fallback, _ := c.tunnelDNS(); len(fallback) > 0 {
			fmt.Printf("# no DNS in the config, using %v\n", fallback)
		}
	}
	if len(c.Dropped) > 0 {
		fmt.Printf("# ignored: %s\n", strings.Join(c.Dropped, ", "))
	}
	if c.leaksIPv6() {
		fmt.Println("# IPv6 will be blocked: ::/0 without an IPv6 address")
	}
	if ex := loadExcluded(); len(ex) > 0 {
		fmt.Printf("# routed outside the tunnel: %s\n", strings.Join(ex, " "))
	}
	return 0
}

func sendCommand(command string) int {
	c, err := net.Dial("unix", socketPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		return 1
	}
	defer c.Close()
	data, _ := json.Marshal(Request{Command: command})
	_, _ = c.Write(append(data, '\n'))
	var resp Response
	if err := json.NewDecoder(c).Decode(&resp); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		return 1
	}
	if !resp.OK {
		fmt.Fprintln(os.Stderr, "error:", resp.Error)
		return 1
	}
	return 0
}
