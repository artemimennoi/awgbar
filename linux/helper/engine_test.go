package main

import (
	"bufio"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
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

// Flags lines of /proc/cpuinfo, shortened: a Haswell (x86-64-v3), a Sandy Bridge (AVX, no AVX2),
// and one like Rosetta's in an x86_64 Linux machine on Apple Silicon (FMA, no AVX, AVX2, BMI2).
const (
	cpuHaswell = "fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush mmx fxsr sse sse2 ss ht syscall nx pdpe1gb rdtscp lm constant_tsc pni pclmulqdq ssse3 fma cx16 pcid sse4_1 sse4_2 x2apic movbe popcnt aes xsave avx f16c rdrand lahf_lm abm fsgsbase bmi1 avx2 smep bmi2 erms invpcid"
	cpuSandy   = "fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush mmx fxsr sse sse2 ss ht syscall nx rdtscp lm constant_tsc pni pclmulqdq ssse3 cx16 pcid sse4_1 sse4_2 x2apic popcnt aes xsave avx lahf_lm"
	cpuRosetta = "fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush mmx fxsr sse sse2 ss ht syscall nx lm constant_tsc pni pclmulqdq ssse3 fma cx16 sse4_1 sse4_2 movbe popcnt aes f16c lahf_lm bmi1"
)

func writeCpuinfo(t *testing.T, flags string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "cpuinfo")
	body := "processor\t: 0\nvendor_id\t: GenuineIntel\nflags\t\t: " + flags + "\nbugs\t\t:\n\nprocessor\t: 1\nflags\t\t: " + flags + "\n"
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestMissingFlags(t *testing.T) {
	for _, c := range []struct {
		name, flags string
		missing     string
	}{
		{"haswell", cpuHaswell, ""},
		{"sandy bridge", cpuSandy, "avx2 bmi1 bmi2 f16c fma abm movbe"},
		{"rosetta", cpuRosetta, "avx avx2 bmi2 abm xsave"},
	} {
		got := strings.Join(missingFlags(writeCpuinfo(t, c.flags), x86v3Flags), " ")
		if got != c.missing {
			t.Errorf("%s: missing %q, want %q", c.name, got, c.missing)
		}
	}
	if got := missingFlags(filepath.Join(t.TempDir(), "none"), x86v3Flags); len(got) != len(x86v3Flags) {
		t.Errorf("no file: missing %v, want all", got)
	}
	arm := filepath.Join(t.TempDir(), "arm")
	_ = os.WriteFile(arm, []byte("processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n"), 0o644)
	if got := missingFlags(arm, x86v3Flags); len(got) != len(x86v3Flags) {
		t.Errorf("no flags line: missing %v, want all", got)
	}
}

// The package and the CPU decide: awgcore when its binary is there and, on amd64, the CPU is
// x86-64-v3; otherwise the unsupported-CPU refusal, which `version` reports and `up` returns
// before anything is set up. A helper built with the amneziawg_go tag runs amneziawg-go instead.
func TestEngineChoice(t *testing.T) {
	oldPath, oldInfo, oldArch := awgcorePath, cpuinfoPath, goarch
	t.Cleanup(func() {
		awgcorePath, cpuinfoPath, goarch = oldPath, oldInfo, oldArch
		cpuOnce, cpuMissing = sync.Once{}, nil
	})
	on := func(arch, flags string) {
		goarch, cpuinfoPath = arch, writeCpuinfo(t, flags)
		cpuOnce, cpuMissing = sync.Once{}, nil
	}
	awgcorePath = filepath.Join(t.TempDir(), "awgcore")
	on("amd64", cpuHaswell)
	if useAwgcore() || unsupportedCPU() != "" {
		t.Fatal("no binary on a v3 CPU: neither awgcore nor a CPU refusal")
	}
	if err := os.WriteFile(awgcorePath, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	on("amd64", cpuHaswell)
	if !useAwgcore() || unsupportedCPU() != "" {
		t.Fatal("binary there on an x86-64-v3 CPU, yet not awgcore")
	}
	for _, c := range []struct{ flags, missing string }{
		{cpuSandy, "avx2 bmi1 bmi2 f16c fma abm movbe"},
		{cpuRosetta, "avx avx2 bmi2 abm xsave"},
	} {
		on("amd64", c.flags)
		if useAwgcore() {
			t.Fatalf("awgcore on a CPU without x86-64-v3, lacking %s", c.missing)
		}
		if builtinGo {
			if got := unsupportedCPU(); got != "" {
				t.Errorf("a helper with amneziawg-go refuses: %q", got)
			}
			continue
		}
		if got := unsupportedCPU(); got != c.missing {
			t.Errorf("version reports %q, want %q", got, c.missing)
		}
		var he *HelperError
		if err := up("anything"); !errors.As(err, &he) || he.Code != "unsupported-cpu" {
			t.Errorf("up: %v, want the unsupported-cpu refusal", err)
		}
		if _, err := startEngine(1280); !errors.As(err, &he) || he.Code != "unsupported-cpu" ||
			!strings.Contains(he.Message, c.missing) {
			t.Errorf("startEngine: %v, want the unsupported-cpu refusal naming %s", err, c.missing)
		}
	}
	on("arm64", "")
	if !useAwgcore() || unsupportedCPU() != "" {
		t.Fatal("arm64 checks no x86 flags, yet not awgcore")
	}
}
