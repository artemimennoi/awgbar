//go:build amneziawg_go

package main

import (
	"net"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/ipc"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
)

// amneziawg-go in-process: in a helper built with the amneziawg_go tag only
// (`make deb USERSPACE=amneziawg-go`), run when the package ships no awgcore or the CPU cannot
// run it.

const builtinGo = true

type goEngine struct {
	dev  *device.Device
	uapi net.Listener
}

func startGo(mtu int) (engine, error) {
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

func (g *goEngine) kick(peers []noiseKey) {
	if err := g.dev.BindUpdate(); err != nil {
		log("kick: rebind: %v", err)
	}
	for _, pk := range peers {
		if peer := g.dev.LookupPeer(device.NoisePublicKey(pk)); peer != nil {
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
