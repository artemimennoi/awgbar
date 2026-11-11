package main

import (
	"bufio"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/ipc"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
)

// engine is the userspace tunnel behind awgbar0: awgcore, our own core in Rust, as a process of
// its own that the helper talks UAPI to, or amneziawg-go built into the helper, the fallback. The
// package decides which: awgcore when it ships /usr/lib/awgbar/awgcore, amneziawg-go when it does
// not (`make deb USERSPACE=amneziawg-go`) or when the CPU cannot run it (cpuRunsAwgcore).
type engine interface {
	name() string
	// set and get are UAPI, as `awg set` and `awg show` speak it.
	set(uapi string) error
	get() (string, error)
	// up starts the device once awgbar0 is configured.
	up() error
	// kick is what a reconnect needs besides new endpoints: a fresh socket and a handshake.
	kick(peers []device.NoisePublicKey)
	close()
}

var awgcorePath = envOr("AWGBAR_AWGCORE", "/usr/lib/awgbar/awgcore")

func useAwgcore() bool {
	fi, err := os.Stat(awgcorePath)
	return err == nil && fi.Mode().IsRegular() && cpuRunsAwgcore()
}

// On amd64 awgcore is built for x86-64-v3 (AVX2 and the rest in every function: RustCrypto's
// AVX2 code is only fast that way, linux/Makefile). On a CPU without those it would die at its
// first such instruction, again at every restart, so the helper runs amneziawg-go there. The
// kernel lists in /proc/cpuinfo only what the CPU has and the system enabled (AVX needs XSAVE).
var (
	cpuinfoPath = "/proc/cpuinfo"
	goarch      = runtime.GOARCH
	x86v3Flags  = []string{
		"cx16", "lahf_lm", "popcnt", "sse4_1", "sse4_2", "ssse3", // x86-64-v2
		"avx", "avx2", "bmi1", "bmi2", "f16c", "fma", "abm", "movbe", "xsave", // v3; abm is LZCNT
	}
	cpuOnce    sync.Once
	cpuMissing []string
)

func cpuRunsAwgcore() bool {
	if goarch != "amd64" {
		return true
	}
	cpuOnce.Do(func() {
		cpuMissing = missingFlags(cpuinfoPath, x86v3Flags)
		if len(cpuMissing) > 0 {
			fmt.Fprintf(os.Stderr, "awgcore needs an x86-64-v3 CPU (this one lacks %s): using amneziawg-go\n",
				strings.Join(cpuMissing, " "))
		}
	})
	return len(cpuMissing) == 0
}

// missingFlags returns the flags of want that the first "flags" line of a cpuinfo file lacks,
// all of them when the file cannot be read or has no such line.
func missingFlags(path string, want []string) []string {
	have := map[string]bool{}
	if f, err := os.Open(path); err == nil {
		s := bufio.NewScanner(f)
		s.Buffer(make([]byte, 0, 64<<10), 1<<20)
		for s.Scan() {
			if k, v, ok := strings.Cut(s.Text(), ":"); ok && strings.TrimSpace(k) == "flags" {
				for _, fl := range strings.Fields(v) {
					have[fl] = true
				}
				break
			}
		}
		f.Close()
	}
	var missing []string
	for _, w := range want {
		if !have[w] {
			missing = append(missing, w)
		}
	}
	return missing
}

func startEngine(mtu int) (engine, error) {
	if useAwgcore() {
		return startCore()
	}
	return startGo(mtu)
}

// MARK: awgcore

// Where awgcore listens, as amneziawg-go does: `awg show awgbar0` works.
var coreSocket = "/var/run/amneziawg/" + ifaceName + ".sock"

// coreEngine is an awgcore process. It creates awgbar0 and its UAPI socket itself, and awgbar0
// goes away when it exits, so a crash stops the tunnel's traffic and leaves the rules and the
// kill switch in place; the helper then starts it again (watchCore).
type coreEngine struct {
	cmd      *exec.Cmd
	done     chan struct{}
	err      error
	stopping atomic.Bool
}

func startCore() (*coreEngine, error) {
	cmd := exec.Command(awgcorePath, "-f", ifaceName)
	// Errors and recoveries go to the system log as well, under this service's unit
	// (journalctl -u awgbar-helper, or -t awgcore). Standard error stays for a panic's message.
	cmd.Env = []string{"LOG_LEVEL=error"}
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	c := &coreEngine{cmd: cmd, done: make(chan struct{})}
	go func() {
		c.err = cmd.Wait()
		close(c.done)
	}()
	// Ready once its socket answers.
	deadline := time.Now().Add(5 * time.Second)
	for {
		if _, err := c.get(); err == nil {
			return c, nil
		}
		select {
		case <-c.done:
			return nil, fmt.Errorf("awgcore exited: %v", c.err)
		case <-time.After(20 * time.Millisecond):
		}
		if time.Now().After(deadline) {
			c.close()
			return nil, errors.New("awgcore did not open its socket")
		}
	}
}

func (c *coreEngine) name() string { return "awgcore" }

// uapiExchange sends one UAPI request and reads its answer up to the empty line that ends it.
// awgcore answers at once; a core that does not within 3 s is hung, and the caller holds the
// helper's lock, so it gives up rather than wait (the watchdog then rebuilds it).
func uapiExchange(request string) ([]string, error) {
	s, err := net.DialTimeout("unix", coreSocket, time.Second)
	if err != nil {
		return nil, err
	}
	defer s.Close()
	_ = s.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := s.Write([]byte(request)); err != nil {
		return nil, err
	}
	var lines []string
	r := bufio.NewScanner(s)
	r.Buffer(make([]byte, 64<<10), 1<<20)
	for r.Scan() {
		if r.Text() == "" {
			return lines, nil
		}
		lines = append(lines, r.Text())
	}
	if err := r.Err(); err != nil {
		return nil, err
	}
	return nil, errors.New("UAPI answer cut short")
}

// uapiErrno reads the errno line that ends every answer.
func uapiErrno(lines []string) ([]string, error) {
	if len(lines) == 0 {
		return nil, errors.New("empty UAPI answer")
	}
	last := lines[len(lines)-1]
	v, ok := strings.CutPrefix(last, "errno=")
	if !ok {
		return nil, fmt.Errorf("bad UAPI answer %q", last)
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return nil, fmt.Errorf("bad UAPI answer %q", last)
	}
	if n != 0 {
		return nil, fmt.Errorf("UAPI error %d", n)
	}
	return lines[:len(lines)-1], nil
}

func (c *coreEngine) set(uapi string) error {
	lines, err := uapiExchange("set=1\n" + uapi + "\n")
	if err == nil {
		_, err = uapiErrno(lines)
	}
	return err
}

func (c *coreEngine) get() (string, error) {
	lines, err := uapiExchange("get=1\n\n")
	if err == nil {
		lines, err = uapiErrno(lines)
	}
	if err != nil {
		return "", err
	}
	return strings.Join(lines, "\n") + "\n", nil
}

// awgcore follows awgbar0: it opens its sockets when the link comes up.
func (c *coreEngine) up() error { return nil }

// awgcore keeps its socket off the tunnel by itself (fwmark and the interface its route leaves
// by), follows network changes over netlink, rebuilds its socket after any error and starts a
// handshake after 20 s without replies. The new endpoints the kick sets are all it needs.
func (c *coreEngine) kick([]device.NoisePublicKey) {}

func (c *coreEngine) exited() bool {
	select {
	case <-c.done:
		return true
	default:
		return false
	}
}

// close stops awgcore: SIGTERM, then SIGKILL if it has not exited within 3 s. awgbar0 goes with it.
func (c *coreEngine) close() {
	c.stopping.Store(true)
	if c.exited() {
		return
	}
	_ = c.cmd.Process.Signal(syscall.SIGTERM)
	select {
	case <-c.done:
	case <-time.After(3 * time.Second):
		_ = c.cmd.Process.Kill()
		<-c.done
	}
}

// MARK: amneziawg-go in-process, the fallback

type goEngine struct {
	dev  *device.Device
	uapi net.Listener
}

func startGo(mtu int) (*goEngine, error) {
	tdev, err := tun.CreateTUN(ifaceName, mtu)
	if err != nil {
		return nil, err
	}
	logger := &device.Logger{
		Verbosef: func(format string, args ...any) { log("awg: "+format, args...) },
		Errorf:   func(format string, args ...any) { log("awg error: "+format, args...) },
	}
	g := &goEngine{dev: device.NewDevice(tdev, conn.NewDefaultBind(), logger)}
	g.listenUAPI()
	return g, nil
}

func (g *goEngine) name() string { return "amneziawg-go" }

func (g *goEngine) set(uapi string) error { return g.dev.IpcSet(uapi) }

func (g *goEngine) get() (string, error) { return g.dev.IpcGet() }

func (g *goEngine) up() error { return g.dev.Up() }

func (g *goEngine) kick(peers []device.NoisePublicKey) {
	if err := g.dev.BindUpdate(); err != nil {
		log("kick: rebind: %v", err)
	}
	for _, pk := range peers {
		if peer := g.dev.LookupPeer(pk); peer != nil {
			_ = peer.SendHandshakeInitiation(false)
		}
	}
}

// listenUAPI exposes the usual /var/run/amneziawg/awgbar0.sock, so `awg show awgbar0` works for
// debugging. Best effort.
func (g *goEngine) listenUAPI() {
	file, err := ipc.UAPIOpen(ifaceName)
	if err != nil {
		return
	}
	l, err := ipc.UAPIListen(ifaceName, file)
	if err != nil {
		return
	}
	g.uapi = l
	dev := g.dev
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

func (g *goEngine) close() {
	if g.uapi != nil {
		g.uapi.Close()
	}
	// Closing the TUN deletes awgbar0 and with it the routes via it.
	g.dev.Close()
}
