package main

import (
	"fmt"
	"os"
	"regexp"
)

// Locations shared with the app and the package scripts.
// AWGBAR_STATE / AWGBAR_RUNTIME override them for running the helper in tests.
var (
	stateDir   = envOr("AWGBAR_STATE", "/var/lib/awgbar")
	runtimeDir = envOr("AWGBAR_RUNTIME", "/run/awgbar")

	tunnelsDir   = stateDir + "/tunnels"
	excludedPath = stateDir + "/excluded.txt"
	// Present only while the user has turned the debug log on.
	debugLogFlag = stateDir + "/debug-log"
	socketPath   = runtimeDir + "/helper.sock"
	// Name of the tunnel that should be up; survives a helper restart (RuntimeDirectoryPreserve=restart)
	// so a crash or an upgrade reconnects, but not a reboot.
	activePath = runtimeDir + "/active"
)

const (
	logPath = "/var/log/awgbar-helper.log"
	// Bump when the protocol changes, so the app can tell an old helper.
	helperVersion = 1
	// One tunnel at a time, always on the same interface: a tunnel called "eth0" can't clash with a real one.
	ifaceName = "awgbar0"
)

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// Same JSON as the macOS app's AWGShared/Protocol.swift, one request and one response per connection,
// each a single line.
type Request struct {
	Command  string   `json:"command"`
	Name     string   `json:"name,omitempty"`
	Config   string   `json:"config,omitempty"`
	Excluded []string `json:"excluded,omitempty"`
	// Display name for importConfig/rename; the file name stays a short id.
	Title   string `json:"title,omitempty"`
	Enabled *bool  `json:"enabled,omitempty"`
}

type TunnelInfo struct {
	Name   string `json:"name"`
	Active bool   `json:"active"`
	// Endpoint from the config ("host:port").
	Endpoint string `json:"endpoint,omitempty"`
	Title    string `json:"title,omitempty"`
}

type TunnelStats struct {
	Endpoint string `json:"endpoint,omitempty"`
	// Unix time of the latest handshake, absent if none yet.
	LastHandshake *int64 `json:"lastHandshake,omitempty"`
	RxBytes       int64  `json:"rxBytes"`
	TxBytes       int64  `json:"txBytes"`
}

type Response struct {
	OK       bool         `json:"ok"`
	Error    string       `json:"error,omitempty"`
	Version  int          `json:"version,omitempty"`
	Tunnels  []TunnelInfo `json:"tunnels,omitempty"`
	Excluded []string     `json:"excluded,omitempty"`
	DebugLog *bool        `json:"debugLog,omitempty"`
	Stats    *TunnelStats `json:"stats,omitempty"`
	Log      string       `json:"log,omitempty"`
	// Set on some failures so the app can word them itself: "duplicate:<tunnel name>".
	Code string `json:"code,omitempty"`
}

// HelperError carries a message for the user plus an optional machine-readable code.
type HelperError struct {
	Message string
	Code    string
}

func (e *HelperError) Error() string { return e.Message }

func fail(format string, args ...any) error {
	return &HelperError{Message: fmt.Sprintf(format, args...)}
}

var tunnelNamePattern = regexp.MustCompile(`^[a-zA-Z0-9_=+.-]{1,15}$`)

// Tunnel ids are file names; kept to the macOS app's pattern so ids look the same on both.
func isValidTunnelName(name string) bool {
	return tunnelNamePattern.MatchString(name) && name != "." && name != ".."
}
