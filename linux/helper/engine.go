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
)

// engine is the userspace tunnel behind awgbar0: awgcore, our own core in Rust, as a process of
// its own that the helper talks UAPI to. A helper built with the amneziawg_go tag
// (`make deb USERSPACE=amneziawg-go`, engine_go.go) has amneziawg-go inside as well and runs it
// when the package ships no awgcore or the CPU cannot run it; the default build has no
// amneziawg-go and refuses to connect on such a CPU (unsupportedCPU).
type engine interface {
	name() string
	// set and get are UAPI, as `awg set` and `awg show` speak it.
	set(uapi string) error
	get() (string, error)
	// up starts the device once awgbar0 is configured.
	up() error
	// kick is what a reconnect needs besides new endpoints: a fresh socket and a handshake.
	kick(peers []noiseKey)
	close()
}

var awgcorePath = envOr("AWGBAR_AWGCORE", "/usr/lib/awgbar/awgcore")

// noiseKey is a peer's public key, as the engines take it.
type noiseKey [32]byte

func haveAwgcore() bool {
	fi, err := os.Stat(awgcorePath)
	return err == nil && fi.Mode().IsRegular()
}

func useAwgcore() bool { return haveAwgcore() && cpuRunsAwgcore() }

// On amd64 awgcore is built for x86-64-v3 (AVX2 and the rest in every function: RustCrypto's
// AVX2 code is only fast that way, linux/Makefile). On a CPU without those it would die at its
// first such instruction, again at every restart, so the helper never starts it there. The
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
			then := "this processor is not supported"
			if builtinGo {
				then = "using amneziawg-go"
			}
			fmt.Fprintf(os.Stderr, "awgcore needs an x86-64-v3 CPU (this one lacks %s): %s\n",
				strings.Join(cpuMissing, " "), then)
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

// unsupportedCPU names what is missing, for `version` and for a refused connect; empty when this
// helper can run a tunnel here.
func unsupportedCPU() string {
	if builtinGo || cpuRunsAwgcore() {
		return ""
	}
	return strings.Join(cpuMissing, " ")
}

func startEngine(mtu int) (engine, error) {
	if useAwgcore() {
		return startCore()
	}
	if builtinGo {
		return startGo(mtu)
	}
	if missing := unsupportedCPU(); missing != "" {
		return nil, cpuError(missing)
	}
	return nil, errors.New("awgcore is missing: " + awgcorePath)
}

// cpuError is the refusal the app words itself (code "unsupported-cpu").
func cpuError(missing string) error {
	return &HelperError{
		Message: "This processor is not supported: AWG Bar needs x86-64-v3 (missing: " + missing + ")",
		Code:    "unsupported-cpu",
	}
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
func (c *coreEngine) kick([]noiseKey) {}

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
