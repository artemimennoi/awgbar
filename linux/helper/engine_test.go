package main

import (
	"bufio"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// A stand-in for awgcore's UAPI socket: answers get with a fixed dump, and set with the errno the
// test asks for, recording what was set.
func fakeCore(t *testing.T, setErrno string) *[]string {
	t.Helper()
	dir := t.TempDir()
	old := coreSocket
	coreSocket = filepath.Join(dir, "awgbar0.sock")
	t.Cleanup(func() { coreSocket = old })
	l, err := net.Listen("unix", coreSocket)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	var sets []string
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			r := bufio.NewReader(c)
			op, _ := r.ReadString('\n')
			var body []string
			for {
				line, err := r.ReadString('\n')
				if err != nil || line == "\n" {
					break
				}
				body = append(body, strings.TrimSuffix(line, "\n"))
			}
			switch op {
			case "get=1\n":
				c.Write([]byte("private_key=00\nlisten_port=51820\npublic_key=11\nrx_bytes=5\ntx_bytes=7\nerrno=0\n\n"))
			case "set=1\n":
				sets = append(sets, strings.Join(body, "\n"))
				c.Write([]byte("errno=" + setErrno + "\n\n"))
			}
			c.Close()
		}
	}()
	return &sets
}

func TestCoreUAPI(t *testing.T) {
	sets := fakeCore(t, "0")
	c := &coreEngine{}
	got, err := c.get()
	if err != nil || !strings.HasPrefix(got, "private_key=00\n") || strings.Contains(got, "errno") {
		t.Fatalf("get: %q %v", got, err)
	}
	if err := c.set("fwmark=16727\nreplace_peers=true\n"); err != nil {
		t.Fatal(err)
	}
	if len(*sets) != 1 || (*sets)[0] != "fwmark=16727\nreplace_peers=true" {
		t.Fatalf("set sent %q", *sets)
	}
}

func TestCoreUAPIError(t *testing.T) {
	fakeCore(t, "22")
	if err := (&coreEngine{}).set("x=1\n"); err == nil || !strings.Contains(err.Error(), "22") {
		t.Fatalf("errno 22 came back as %v", err)
	}
	for _, bad := range [][]string{nil, {"rx_bytes=1"}, {"errno=x"}} {
		if _, err := uapiErrno(bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
	if rest, err := uapiErrno([]string{"a=1", "errno=0"}); err != nil || len(rest) != 1 {
		t.Errorf("%q %v", rest, err)
	}
}

func TestCoreBackoff(t *testing.T) {
	var tun Tunnel
	now := time.Now()
	want := []time.Duration{1, 2, 4, 8, 16, 30, 30}
	for i, w := range want {
		if got := tun.coreBackoff(now.Add(time.Duration(i) * time.Second)); got != w*time.Second {
			t.Errorf("restart %d waits %v, want %v s", i, got, w)
		}
	}
	// A minute later the count starts over.
	if got := tun.coreBackoff(now.Add(2 * time.Minute)); got != time.Second {
		t.Errorf("after a quiet minute: %v", got)
	}
}

// The package decides the engine: awgcore when its binary is there, amneziawg-go otherwise.
func TestEngineChoice(t *testing.T) {
	old := awgcorePath
	t.Cleanup(func() { awgcorePath = old })
	awgcorePath = filepath.Join(t.TempDir(), "awgcore")
	if useAwgcore() {
		t.Fatal("no binary, yet awgcore")
	}
	if err := os.WriteFile(awgcorePath, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	if !useAwgcore() {
		t.Fatal("binary there, yet not awgcore")
	}
}
