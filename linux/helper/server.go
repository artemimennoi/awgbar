package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"golang.org/x/sys/unix"
)

var (
	// mu serializes everything that touches the tunnel: requests and the watchdog.
	mu      sync.Mutex
	current *Tunnel
	// The tunnel reconnect() is bringing back after a restart: shown as up, its rules already
	// block traffic, and disconnecting it has to undo them.
	reconnecting string
	// Set by prepareRestart (the package's postinst, right before restarting us for an upgrade):
	// the stop that follows keeps the rules and DNS, so nothing leaks until the new helper is back.
	keepUntil time.Time
)

func withTunnel(f func(*Tunnel)) {
	mu.Lock()
	defer mu.Unlock()
	if current != nil {
		f(current)
	}
}

// MARK: requests

func confPath(name string) string { return filepath.Join(tunnelsDir, name+".conf") }

func loadConfig(name string) (*TunnelConfig, error) {
	data, err := os.ReadFile(confPath(name))
	if err != nil {
		return nil, err
	}
	return parseConfig(string(data))
}

func listTunnels() []TunnelInfo {
	entries, _ := os.ReadDir(tunnelsDir)
	var out []TunnelInfo
	for _, e := range entries {
		name, ok := strings.CutSuffix(e.Name(), ".conf")
		if !ok || !isValidTunnelName(name) {
			continue
		}
		info := TunnelInfo{Name: name, Active: current != nil && current.name == name || current == nil && reconnecting == name}
		if c, err := loadConfig(name); err == nil {
			info.Endpoint, info.Title = c.endpoint(), c.Title
		}
		out = append(out, info)
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out
}

func loadExcluded() []string {
	data, _ := os.ReadFile(excludedPath)
	networks, _ := parseNetworkList(string(data))
	out := make([]string, len(networks))
	for i, n := range networks {
		out[i] = n.String()
	}
	return out
}

func listResponse() *Response {
	debug := debugLogEnabled()
	return &Response{OK: true, Tunnels: listTunnels(), Excluded: loadExcluded(), DebugLog: &debug}
}

func requireName(req *Request, mustExist bool) (string, error) {
	if !isValidTunnelName(req.Name) {
		return "", fail("Invalid tunnel name")
	}
	if _, err := os.Stat(confPath(req.Name)); mustExist && err != nil {
		return "", fail("Tunnel %s not found", req.Name)
	}
	return req.Name, nil
}

func up(name string) error {
	c, err := loadConfig(name)
	if err != nil {
		return err
	}
	if len(c.Dropped) > 0 {
		log("%s: ignored keys %s", name, strings.Join(c.Dropped, ", "))
	}
	data, _ := os.ReadFile(excludedPath)
	excluded, _ := parseNetworkList(string(data))
	t, err := startTunnel(name, c, excluded)
	if err != nil {
		return err
	}
	current = t
	return writeAtomic(activePath, []byte(name), 0o600)
}

// down stops the tunnel. forget=false keeps the active marker, so the next helper start
// (after an upgrade or a crash) reconnects.
func down(forget bool) {
	if current != nil {
		current.stop()
		current = nil
	}
	if forget {
		if reconnecting != "" {
			// What the previous helper left in place for the reconnect.
			cleanUpStaleState(false)
			reconnecting = ""
		}
		_ = os.Remove(activePath)
	}
}

func handle(req *Request) (*Response, error) {
	switch req.Command {
	case "version":
		return &Response{OK: true, Version: helperVersion}, nil

	case "list":
		return listResponse(), nil

	case "stats":
		name, err := requireName(req, true)
		if err != nil {
			return nil, err
		}
		if current == nil || current.name != name {
			return &Response{OK: true}, nil
		}
		s := current.stats()
		return &Response{OK: true, Stats: &s}, nil

	case "importConfig":
		name, err := requireName(req, false)
		if err != nil {
			return nil, err
		}
		// The app always picks a free name; replacing a tunnel in place would let anything that
		// may talk to us swap the server behind a name the user trusts.
		if _, err := os.Stat(confPath(name)); err == nil {
			return nil, fail("Tunnel %s already exists", name)
		}
		c, err := parseConfig(req.Config)
		if err != nil {
			return nil, err
		}
		for _, t := range listTunnels() {
			if other, err := loadConfig(t.Name); err == nil && t.Name != name && other.identity() == c.identity() {
				title := t.Title
				if title == "" {
					title = t.Name
				}
				return nil, &HelperError{Message: "Already added as " + title, Code: "duplicate:" + t.Name}
			}
		}
		c.Title = cleanTitle(req.Title)
		if err := writeAtomic(confPath(name), []byte(c.render(true)), 0o600); err != nil {
			return nil, err
		}
		log("imported %s, ignored keys: %s", name, strings.Join(c.Dropped, ", "))
		return listResponse(), nil

	case "rename":
		name, err := requireName(req, true)
		if err != nil {
			return nil, err
		}
		title := cleanTitle(req.Title)
		if title == "" {
			return nil, fail("Empty name")
		}
		c, err := loadConfig(name)
		if err != nil {
			return nil, err
		}
		c.Title = title
		if err := writeAtomic(confPath(name), []byte(c.render(true)), 0o600); err != nil {
			return nil, err
		}
		return listResponse(), nil

	case "delete":
		name, err := requireName(req, true)
		if err != nil {
			return nil, err
		}
		if current != nil && current.name == name {
			down(true)
		}
		if err := os.Remove(confPath(name)); err != nil {
			return nil, err
		}
		log("deleted %s", name)
		return listResponse(), nil

	case "up":
		name, err := requireName(req, true)
		if err != nil {
			return nil, err
		}
		if current != nil && current.name == name {
			return listResponse(), nil
		}
		// One tunnel at a time: two would fight over the default route and DNS.
		down(true)
		if err := up(name); err != nil {
			return nil, err
		}
		return listResponse(), nil

	case "down":
		if _, err := requireName(req, true); err != nil {
			return nil, err
		}
		down(true)
		return listResponse(), nil

	case "setExcluded":
		networks, invalid := parseNetworkList(strings.Join(req.Excluded, "\n"))
		if len(invalid) > 0 {
			return nil, fail("Not a network: %s", strings.Join(invalid, ", "))
		}
		var lines []string
		for _, n := range networks {
			lines = append(lines, n.String()+"\n")
		}
		if err := writeAtomic(excludedPath, []byte(strings.Join(lines, "")), 0o644); err != nil {
			return nil, err
		}
		log("excluded: %v", networks)
		if current != nil {
			// Only the exclusion rules change; the tunnel itself keeps running.
			current.excluded = networks
			if err := setExcludedRules(current.config, networks); err != nil {
				return nil, fail("Cannot update routing rules: %v", err)
			}
		}
		return listResponse(), nil

	case "setDebugLog":
		setDebugLog(req.Enabled != nil && *req.Enabled)
		return listResponse(), nil

	case "kick":
		// Debugging aid: what the watchdog does after sleep.
		if current != nil {
			current.kick("requested")
		}
		return listResponse(), nil

	case "prepareRestart":
		keepUntil = time.Now().Add(2 * time.Minute)
		return listResponse(), nil
	}
	return nil, fail("Unknown command %s", req.Command)
}

// MARK: socket

// Commands that only read; everything else changes the system and needs authorization.
var readOnly = map[string]bool{"version": true, "list": true, "stats": true}

func serve() error {
	if err := os.MkdirAll(tunnelsDir, 0o700); err != nil {
		return err
	}
	if err := os.MkdirAll(runtimeDir, 0o755); err != nil {
		return err
	}
	_ = os.Remove(socketPath)
	l, err := net.Listen("unix", socketPath)
	if err != nil {
		return err
	}
	// Access control is per request (peer credentials and polkit); anyone may connect.
	if err := os.Chmod(socketPath, 0o666); err != nil {
		return err
	}
	log("listening on %s", socketPath)
	for {
		c, err := l.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			continue
		}
		// Connections are served concurrently so a polkit prompt doesn't block the app's polling,
		// but anything touching the tunnel takes mu.
		go serveConn(c.(*net.UnixConn))
	}
}

func serveConn(c *net.UnixConn) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(10 * time.Minute))
	resp := respond(c)
	data, _ := json.Marshal(resp)
	_ = c.SetWriteDeadline(time.Now().Add(10 * time.Second))
	_, _ = c.Write(append(data, '\n'))
}

func respond(c *net.UnixConn) *Response {
	cred, err := peerCred(c)
	if err != nil {
		return &Response{Error: "Cannot identify the caller"}
	}
	_ = c.SetReadDeadline(time.Now().Add(10 * time.Second))
	line, err := bufio.NewReader(&limitedReader{c, 1 << 20}).ReadBytes('\n')
	if err != nil && len(line) == 0 {
		return &Response{Error: "Empty request"}
	}
	var req Request
	if err := json.Unmarshal(line, &req); err != nil {
		return &Response{Error: "Bad request: " + err.Error()}
	}
	if (req.Command == "kick" || req.Command == "prepareRestart") && cred.Uid != 0 {
		return &Response{Error: "Permission denied"}
	}
	if err := authorize(cred, !readOnly[req.Command]); err != nil {
		return &Response{Error: err.Error(), Code: "denied"}
	}

	mu.Lock()
	defer mu.Unlock()
	resp, err := handle(&req)
	if err != nil {
		var he *HelperError
		if errors.As(err, &he) {
			return &Response{Error: he.Message, Code: he.Code}
		}
		return &Response{Error: err.Error()}
	}
	return resp
}

func peerCred(c *net.UnixConn) (*unix.Ucred, error) {
	raw, err := c.SyscallConn()
	if err != nil {
		return nil, err
	}
	var cred *unix.Ucred
	var credErr error
	if err := raw.Control(func(fd uintptr) {
		cred, credErr = unix.GetsockoptUcred(int(fd), unix.SOL_SOCKET, unix.SO_PEERCRED)
	}); err != nil {
		return nil, err
	}
	return cred, credErr
}

type limitedReader struct {
	c *net.UnixConn
	n int
}

func (r *limitedReader) Read(p []byte) (int, error) {
	if r.n <= 0 {
		return 0, fmt.Errorf("request too large")
	}
	if len(p) > r.n {
		p = p[:r.n]
	}
	n, err := r.c.Read(p)
	r.n -= n
	return n, err
}
