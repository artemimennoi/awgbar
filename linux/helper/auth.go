package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/godbus/dbus/v5"
	"golang.org/x/sys/unix"
)

// Who may drive the helper is polkit's call, the same way NetworkManager does it: the user at
// the machine (an active local session) may connect and disconnect without a password, anyone
// else has to authenticate as an administrator. See io.github.artemimennoi.awgbar.policy.
const polkitAction = "io.github.artemimennoi.awgbar.control"

type authKey struct {
	pid   int32
	start uint64
}

var (
	authMu    sync.Mutex
	authCache = map[authKey]time.Time{}
)

func authorize(cred *unix.Ucred, mayPrompt bool) error {
	if cred.Uid == 0 {
		return nil
	}
	start, err := processStartTime(cred.Pid)
	if err != nil {
		return fmt.Errorf("Permission denied")
	}
	key := authKey{cred.Pid, start}

	authMu.Lock()
	if until, ok := authCache[key]; ok && time.Now().Before(until) {
		authMu.Unlock()
		return nil
	}
	authMu.Unlock()

	ok, err := checkPolkit(cred, start, mayPrompt)
	if err != nil {
		log("polkit: %v", err)
		return fmt.Errorf("Cannot check permissions with polkit: %v", err)
	}
	if !ok {
		return fmt.Errorf("Permission denied")
	}
	authMu.Lock()
	// The app polls every few seconds; don't ask polkit every time.
	authCache[key] = time.Now().Add(time.Minute)
	for k, until := range authCache {
		if time.Now().After(until) {
			delete(authCache, k)
		}
	}
	authMu.Unlock()
	return nil
}

func checkPolkit(cred *unix.Ucred, start uint64, mayPrompt bool) (bool, error) {
	conn, err := systemBus()
	if err != nil {
		return false, err
	}
	subject := struct {
		Kind    string
		Details map[string]dbus.Variant
	}{"unix-process", map[string]dbus.Variant{
		"pid":        dbus.MakeVariant(uint32(cred.Pid)),
		"start-time": dbus.MakeVariant(start),
		"uid":        dbus.MakeVariant(int32(cred.Uid)),
	}}
	var flags uint32
	if mayPrompt {
		flags = 1 // AllowUserInteraction: polkit's agent shows a password dialog if needed
	}
	var result struct {
		Authorized bool
		Challenge  bool
		Details    map[string]string
	}
	authority := conn.Object("org.freedesktop.PolicyKit1", "/org/freedesktop/PolicyKit1/Authority")
	call := authority.Call("org.freedesktop.PolicyKit1.Authority.CheckAuthorization", 0,
		subject, polkitAction, map[string]string{}, flags, "")
	if err := call.Store(&result); err != nil {
		return false, err
	}
	return result.Authorized, nil
}

// processStartTime is field 22 of /proc/<pid>/stat; with the pid it identifies the process
// even if the pid is reused.
func processStartTime(pid int32) (uint64, error) {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(int(pid)) + "/stat")
	if err != nil {
		return 0, err
	}
	// The command name is in parentheses and may contain spaces; fields start after the last ")".
	s := string(data)
	i := strings.LastIndexByte(s, ')')
	if i < 0 {
		return 0, fmt.Errorf("bad stat")
	}
	fields := strings.Fields(s[i+1:])
	// fields[0] is field 3 (state), so field 22 is fields[19].
	if len(fields) < 20 {
		return 0, fmt.Errorf("bad stat")
	}
	return strconv.ParseUint(fields[19], 10, 64)
}
