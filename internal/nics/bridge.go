package nics

import (
	"errors"
	"sync"
	"syscall"
)

// SocketBridge lets the hosting app steer our outbound sockets through a network it controls.
// On Android (no root) neither SO_BINDTODEVICE nor an interface index is usable, so the
// VpnService implements this interface instead:
//
//   - ProtectSocket calls VpnService.protect(fd) before connect(), so our own flow is never
//     routed back into the TUN we serve (routing loop);
//   - BindSocketToNetwork calls Network.bindSocket(fd) for the network identified by its
//     handle (Network.getNetworkHandle(), API 31+), pinning the flow to Wi-Fi or cellular.
//
// Desktop platforms inject no bridge: SocketControl then falls back to ForceEgress (SO_*).
type SocketBridge interface {
	BindSocketToNetwork(socketFd int, networkHandle int64) error
	ProtectSocket(socketFd int) bool
}

var (
	bridgeMu sync.RWMutex
	bridge   SocketBridge
)

// SetSocketBridge injects the platform bridge used on every outbound socket (nil removes it).
func SetSocketBridge(b SocketBridge) {
	bridgeMu.Lock()
	bridge = b
	bridgeMu.Unlock()
}

func currentBridge() SocketBridge {
	bridgeMu.RLock()
	defer bridgeMu.RUnlock()
	return bridge
}

// SocketControl is the net.Dialer/net.ListenConfig Control func for every outbound socket of a
// flow pinned to one NIC (idx, networkHandle). With a bridge injected (Android), protect() runs
// first — inside RawConn.Control, i.e. after socket creation but before connect() — then the
// socket is bound to the target network when its handle is known. Without a bridge the
// platform's egress forcing applies (SO_BINDTOIFINDEX, IP_UNICAST_IF, IP_BOUND_IF).
func SocketControl(idx int, networkHandle int64, v6 bool) func(network, address string, c syscall.RawConn) error {
	if br := currentBridge(); br != nil {
		return func(network, address string, c syscall.RawConn) error {
			var perr, berr error
			if err := c.Control(func(fd uintptr) {
				if !br.ProtectSocket(int(fd)) {
					perr = errors.New("mergify-net: ProtectSocket failed: the socket would loop back into the TUN")
					return
				}
				if networkHandle > 0 { // 0 = protect only (no handle, e.g. API < 31)
					berr = br.BindSocketToNetwork(int(fd), networkHandle)
				}
			}); err != nil {
				return err
			}
			if perr != nil {
				return perr
			}
			return berr
		}
	}
	return ForceEgress(idx, v6)
}
