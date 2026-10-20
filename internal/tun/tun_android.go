//go:build android

package tun

import (
	"errors"
	"os"

	"github.com/xjasonlyu/tun2socks/v2/core/device"
	"github.com/xjasonlyu/tun2socks/v2/core/device/iobased"
)

const openHint = "on Android the TUN fd must come from VpnService.Builder.establish().detachFd()"

// configure is a no-op on Android: the VpnService already gave the adapter its addresses,
// routes and MTU (Builder.addAddress/addRoute/setMtu) before handing us the fd, and touching
// any of it would need CAP_NET_ADMIN, which an unprivileged app doesn't have. t.idx stays 0,
// which also makes teardown's delRoutes a no-op — removing the capture routes is done by
// stopping the VpnService itself.
func configure(name string, _ int) (int, error) { return 0, nil }

// delRoutes is a no-op, see configure.
func delRoutes(idx int) {}

// openDevice attaches the userland stack to the fd received from the VpnService instead of
// opening /dev/net/tun, which is impossible there without root. Android creates that fd with
// IFF_NO_PI, so the packets on it are raw IPv4/IPv6 datagrams with no TUN header to skip
// (offset 0). The MTU is the one the VpnService configured (Builder.setMtu, default 1500):
// the stack must not emit bigger packets than the fd accepts.
func (t *Tun) openDevice() (device.Device, error) {
	if t.fd < 0 {
		return nil, errors.New("no TUN fd: call UseFd with the fd from VpnService.establish().detachFd() first")
	}
	f := os.NewFile(uintptr(t.fd), "tun")
	ep, err := iobased.New(f, uint32(t.MTU), 0)
	if err != nil {
		f.Close()
		return nil, err
	}
	return &fdDevice{Endpoint: ep, f: f}, nil
}

// fdDevice adapts the externally-created fd to tun2socks' device.Device.
type fdDevice struct {
	*iobased.Endpoint
	f *os.File
}

func (d *fdDevice) Name() string { return tunName } // the VpnService names the adapter ("tun0")
func (d *fdDevice) Type() string { return "tun" }

// Close detaches the stack, then closes the fd: VpnService.detachFd() transferred its ownership
// to us. A goroutine may stay blocked on read until the VpnService tears down the kernel side
// of the adapter; it exits as soon as that happens.
func (d *fdDevice) Close() {
	d.Endpoint.Close()
	d.f.Close()
}
