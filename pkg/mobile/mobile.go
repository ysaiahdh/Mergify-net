// Package mobile exposes mergify-net's balancing engine to Android through gomobile bind.
//
// Build:
//
//	gomobile bind -target=android -androidapi 31 -o mergify.aar ./pkg/mobile
//
// On Android (no root) neither net.Interfaces() nor SO_BINDTODEVICE is usable from a
// VpnService, so the hosting app drives the engine in three steps:
//
//  1. implement AndroidNetworkBridge in the VpnService (protect + Network.bindSocket) and
//     hand it over with SetNetworkBridge;
//  2. push the active networks (Wi-Fi, cellular, each with its Network handle) with
//     AddManualNic, then call ApplyManualNics on every ConnectivityManager.NetworkCallback;
//  3. run the engine — every outbound socket is then protected and bound to the right
//     network automatically, inside the dialer's Control callback.
//
// Only gomobile-supported types appear in exported signatures (int, int64, float64, string,
// bool, error, simple interfaces), per the constraints in AGENTS.md.
package mobile

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"sync"
	"time"

	"mergify-net/internal/engine"
	"mergify-net/internal/nics"
	"mergify-net/internal/tun"
)

// AndroidNetworkBridge is implemented by the hosting VpnService (Kotlin/Java). Both methods
// are called from the dialer's RawConn.Control callback, i.e. on a freshly created socket
// before connect():
//
//   - ProtectSocket must call VpnService.protect(fd) and return whether it succeeded, so our
//     own flow is never routed back into the TUN we serve (routing loop);
//   - BindSocketToNetwork must call Network.bindSocket(fd) for the network identified by
//     networkHandle (Network.getNetworkHandle(), API 31+), pinning the flow to Wi-Fi or
//     cellular.
type AndroidNetworkBridge interface {
	BindSocketToNetwork(socketFd int, networkHandle int64) error
	ProtectSocket(socketFd int) bool
}

// bridgeAdapter narrows the exported interface to internal/nics.SocketBridge.
type bridgeAdapter struct{ impl AndroidNetworkBridge }

func (a bridgeAdapter) BindSocketToNetwork(socketFd int, networkHandle int64) error {
	return a.impl.BindSocketToNetwork(socketFd, networkHandle)
}

func (a bridgeAdapter) ProtectSocket(socketFd int) bool { return a.impl.ProtectSocket(socketFd) }

// SetNetworkBridge injects the VpnService bridge used on every outbound socket; pass nil to
// remove it (e.g. when the VPN stops).
func SetNetworkBridge(b AndroidNetworkBridge) {
	if b == nil {
		nics.SetSocketBridge(nil)
		return
	}
	nics.SetSocketBridge(bridgeAdapter{b})
}

var (
	manualMu sync.Mutex
	manual   []nics.ManualNic
)

// AddManualNic registers (or replaces, by name) one active network. ip4 and ip6 are the source
// addresses flows will be pinned to ("" when the family is absent), networkHandle comes from
// Network.getNetworkHandle() and weight is the balancing weight (<= 0 means 1). Call
// ApplyManualNics afterwards to hand the list to the engine.
func AddManualNic(name, ip4, ip6 string, networkHandle int64, weight float64) error {
	if name == "" {
		return errors.New("mobile: AddManualNic needs a non-empty name")
	}
	var a4, a6 net.IP
	if ip4 != "" {
		if a4 = net.ParseIP(ip4).To4(); a4 == nil {
			return fmt.Errorf("mobile: %s: invalid IPv4 address %q", name, ip4)
		}
	}
	if ip6 != "" {
		a6 = net.ParseIP(ip6)
		if a6 == nil || a6.To4() != nil {
			return fmt.Errorf("mobile: %s: invalid IPv6 address %q", name, ip6)
		}
	}
	if a4 == nil && a6 == nil {
		return fmt.Errorf("mobile: %s: at least one address is required", name)
	}
	if weight < 0 {
		weight = 0
	}
	manualMu.Lock()
	defer manualMu.Unlock()
	entry := nics.ManualNic{Name: name, IP: a4, IP6: a6, Weight: weight, NetworkHandle: networkHandle}
	for i := range manual {
		if manual[i].Name == name {
			manual[i] = entry
			return nil
		}
	}
	manual = append(manual, entry)
	return nil
}

// ApplyManualNics hands the registered networks to the engine and disables auto-discovery
// (net.Interfaces() can't enumerate the active networks from a VpnService). Re-call it on
// every network change (ConnectivityManager.NetworkCallback).
func ApplyManualNics() error {
	manualMu.Lock()
	defer manualMu.Unlock()
	if len(manual) == 0 {
		return errors.New("mobile: no network registered, call AddManualNic first")
	}
	nics.SetManualNics(append([]nics.ManualNic(nil), manual...))
	return nil
}

// ClearManualNics drops the injected networks and restores auto-discovery (VPN stopped).
func ClearManualNics() {
	manualMu.Lock()
	manual = nil
	manualMu.Unlock()
	nics.SetManualNics(nil)
}

// androidMTU matches VpnService.Builder.setMtu's default: the kernel side of the adapter
// accepts nothing bigger, so the userland stack must not emit bigger packets either.
const androidMTU = 1500

// service is one running mergify-net instance.
type service struct {
	sch  *engine.Scheduler
	bal  *engine.Balancer
	tun  *tun.Tun
	stop chan struct{}
}

var (
	svcMu sync.Mutex
	svc   *service
)

func currentService() *service {
	svcMu.Lock()
	defer svcMu.Unlock()
	return svc
}

// StartVpn starts the VPN core on the TUN fd obtained from
// VpnService.Builder.establish().detachFd() — never /dev/net/tun, which can't be opened on
// Android without root. The active networks must already be registered (AddManualNic +
// ApplyManualNics), and bridge must be non-nil: without it the outbound sockets would be
// routed straight back into our own TUN.
//
// tunFd's ownership passes to mergify-net: StopVpn closes it on success, and every error
// path here closes it too, so the caller never has to.
func StartVpn(tunFd int, bridge AndroidNetworkBridge) error {
	svcMu.Lock()
	defer svcMu.Unlock()
	// consumeFd closes the caller's fd on the error paths below, where no Go component ever
	// took it (t.On() failures close it themselves, via openDevice/teardown).
	consumeFd := func() { os.NewFile(uintptr(tunFd), "tun").Close() }
	if svc != nil {
		consumeFd()
		return errors.New("mobile: already started, call StopVpn first")
	}
	if tunFd < 0 {
		return errors.New("mobile: invalid TUN fd")
	}
	if bridge == nil {
		consumeFd()
		return errors.New("mobile: a non-nil AndroidNetworkBridge is required (protect + bind sockets)")
	}
	SetNetworkBridge(bridge)
	found, err := nics.DiscoverNics(nil, nil) // the injected networks, see ApplyManualNics
	if err != nil || len(found) == 0 {
		nics.SetSocketBridge(nil)
		consumeFd()
		if err != nil {
			return fmt.Errorf("mobile: list networks: %w", err)
		}
		return errors.New("mobile: no usable network: call AddManualNic and ApplyManualNics first")
	}
	sch := engine.NewScheduler(found)
	bal := engine.NewBalancer(sch)
	stop := make(chan struct{})
	go sch.RunProbes(stop) // internet reachability per network, through the bridge
	// Tick feeds the scheduler's per-NIC capacity estimates, which the primary selection needs
	// to detect saturation (and the auto weights on desktop): without it every link would look
	// infinitely roomy and flows would never spill over. Desktop's main loop ticks every
	// second; Android has no such loop, so the service owns one.
	go func() {
		t := time.NewTicker(2 * time.Second)
		defer t.Stop()
		last := time.Now()
		for {
			select {
			case <-stop:
				return
			case now := <-t.C:
				sch.Tick(now.Sub(last))
				last = now
			}
		}
	}()
	t := tun.New(bal)
	t.MTU = androidMTU
	t.UseFd(tunFd)
	if err := t.On(); err != nil {
		close(stop)
		nics.SetSocketBridge(nil)
		return fmt.Errorf("mobile: start TUN: %w", err)
	}
	svc = &service{sch: sch, bal: bal, tun: t, stop: stop}
	return nil
}

// UpdateNetworks refreshes the Android Network handles of the registered networks without
// interrupting the flows in progress: the first network registered with AddManualNic is taken
// as Wi-Fi, the second as cellular (that order). The scheduler re-balances on the new handles
// and drops the networks that vanished. A handle of 0 means the network is currently
// unavailable: it stops receiving new flows.
func UpdateNetworks(wifiHandle, cellHandle int64) {
	manualMu.Lock()
	if len(manual) > 0 {
		manual[0].NetworkHandle = wifiHandle
	}
	if len(manual) > 1 {
		manual[1].NetworkHandle = cellHandle
	}
	list := append([]nics.ManualNic(nil), manual...)
	manualMu.Unlock()
	nics.SetManualNics(list)
	s := currentService()
	if s == nil {
		return
	}
	if found, err := nics.DiscoverNics(nil, nil); err == nil {
		s.sch.Update(found)
	}
}

// stats is what GetStatsJSON reports.
type stats struct {
	Running bool               `json:"running"`
	V6      string             `json:"v6"`
	Nics    []engine.SlotStats `json:"nics"`
}

// GetStatsJSON returns the current balancing state as JSON: one entry per network with its
// addresses, probe state, active flows, rates and totals.
func GetStatsJSON() string {
	st := stats{}
	if s := currentService(); s != nil {
		st.Running = s.tun.IsOn()
		st.V6 = s.bal.V6State()
		st.Nics = s.sch.Snapshot()
	}
	b, err := json.Marshal(st)
	if err != nil {
		b, _ = json.Marshal(map[string]string{"error": err.Error()})
	}
	return string(b)
}

// StopVpn stops the VPN core: probes, then the TUN (which closes the fd it now owns),
// then the bridge. Safe to call when nothing is running.
func StopVpn() {
	svcMu.Lock()
	s := svc
	svc = nil
	svcMu.Unlock()
	if s == nil {
		return
	}
	close(s.stop)
	s.tun.Close()
	SetNetworkBridge(nil)
}
