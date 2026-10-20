package nics

import (
	"net"
	"sync"
)

// ManualNic is one adapter described by the host app instead of platform discovery. On Android,
// net.Interfaces() can't enumerate the active networks from a VpnService without root, so the
// app pushes them itself: Wi-Fi and cellular, each with its Network handle from
// ConnectivityManager (Network.getNetworkHandle(), API 31+).
type ManualNic struct {
	Name          string
	IP            net.IP // IPv4 source address, or nil
	IP6           net.IP // global IPv6 source address, or nil
	Weight        float64
	NetworkHandle int64
}

var (
	manualMu   sync.Mutex
	manualList []Nic
	manualOn   bool
)

// SetManualNics switches discovery to host-injected adapters; DiscoverNics* then returns
// exactly this list. Passing nil restores platform discovery. A weight <= 0 defaults to 1.
func SetManualNics(list []ManualNic) {
	manualMu.Lock()
	defer manualMu.Unlock()
	if list == nil {
		manualList, manualOn = nil, false
		return
	}
	out := make([]Nic, 0, len(list))
	for _, m := range list {
		w := m.Weight
		if w <= 0 {
			w = 1
		}
		out = append(out, Nic{
			Name: m.Name, IP: m.IP, IP6: m.IP6, Weight: w, NetworkHandle: m.NetworkHandle,
		})
	}
	manualList, manualOn = out, true
}

// manualNics returns the injected adapters when manual mode is on (ok=false = auto discovery).
func manualNics() ([]Nic, bool) {
	manualMu.Lock()
	defer manualMu.Unlock()
	if !manualOn {
		return nil, false
	}
	return append([]Nic(nil), manualList...), true
}
