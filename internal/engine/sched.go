package engine

import (
	"math"
	"math/rand/v2"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"mergify-net/internal/nics"
)

// Family selects which address of a NIC a flow is pinned to. Load, speed and health are tracked
// per family because one link can behave very differently over each (e.g. a carrier hotspot that
// is fast over native IPv6 and slow over translated IPv4).
type Family int

const (
	Fam4 Family = iota
	Fam6
)

// linkState is the result of the internet probe: new adapters are "unknown" (still preferred
// over dead ones, so a probe that can't run never causes an outage) until the first probe lands.
type linkState int

const (
	stateUnknown linkState = iota
	stateUp
	stateDown
)

func (l linkState) String() string {
	return [...]string{"checking", "up", "no internet"}[l]
}

type slotAddr struct {
	ip4, ip6 net.IP
	handle   int64 // Android Network handle bound to these addresses (0 elsewhere)
}

// Slot is a NIC plus its live counters. Counters are atomic so the data path never takes a lock.
type Slot struct {
	Name  string
	Index int
	key   string
	addr  atomic.Pointer[slotAddr]

	active, total, failed atomic.Int64 // active is across families (idle check on removal)
	actF                  [2]atomic.Int64

	// Data usage counters, one per address family: bytesIn is what the network sent us (Rx),
	// bytesOut what we sent to it (Tx). The data path adds to them with plain atomic adds —
	// never a lock — so the pump, the relays and UDP flows all report without contention;
	// Snapshot/DataUsage only load them.
	bytesIn, bytesOut [2]atomic.Int64

	probing [2]atomic.Bool

	// Probe-based quality estimates (probe.go), written by the probe goroutines and read by
	// the balancer's primary selection: plain atomics, no lock on that path. The loss rate is
	// a float64's bits in a Uint64 (sync/atomic has no float types anymore).
	rtt  [2]atomic.Int64  // smoothed connect time, microseconds; 0 = never measured
	loss [2]atomic.Uint64 // math.Float64bits of the smoothed probe failure rate, 0..1

	// guarded by Scheduler.mu
	weight          [2]float64
	present         bool
	state           [2]linkState
	lastProbe       [2]time.Time
	downUntil       [2]time.Time
	lastIn, lastOut [2]int64
	rateIn, rateOut [2]float64 // bytes/s over the last tick
	peak            [2]float64 // observed capacity: max recent rateIn+rateOut

	// The adapter's total traffic from the OS's counters (all apps, not only mergify-net's flows).
	linkIn, linkOut         uint64
	linkRateIn, linkRateOut float64
	linkPrimed              bool
}

func (sl *Slot) ip(f Family) net.IP {
	a := sl.addr.Load()
	if f == Fam6 {
		return a.ip6
	}
	return a.ip4
}

// networkHandle is the Android Network handle of the slot's current addresses (0 on platforms
// that force egress by interface index). It rides along addr so an address change (new network)
// atomically updates the handle too.
func (sl *Slot) networkHandle() int64 { return sl.addr.Load().handle }

// rx/tx are this interface's session data usage through mergify-net, both families summed:
// Rx = bytes received from the network, Tx = bytes sent to it. Plain atomic loads, so a stats
// poll never blocks the data path.
func (sl *Slot) rx() int64 { return sl.bytesIn[Fam4].Load() + sl.bytesIn[Fam6].Load() }
func (sl *Slot) tx() int64 { return sl.bytesOut[Fam4].Load() + sl.bytesOut[Fam6].Load() }

// DataUsage is one interface's data usage counters, in bytes, over the scheduler's lifetime
// (i.e. the current VPN session): Rx is what came in from that interface, Tx what was sent
// through it. Both families are summed, the counters never reset while the engine runs.
type DataUsage struct {
	Name string
	Rx   int64
	Tx   int64
}

// DataUsage returns every interface's byte counters (Rx/Tx), read with plain atomic loads.
func (s *Scheduler) DataUsage() []DataUsage {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]DataUsage, 0, len(s.slots))
	for _, sl := range s.slots {
		out = append(out, DataUsage{Name: sl.Name, Rx: sl.rx(), Tx: sl.tx()})
	}
	return out
}

type SlotStats struct {
	Name              string
	IP                string
	IP6               string
	State             string // IPv4 probe state, "-" if the adapter has no IPv4
	State6            string
	Weight, Weight6   float64
	Present           bool
	Active, Total     int64
	Failed            int64
	BytesIn, BytesOut int64
	RateIn, RateOut   float64 // through mergify-net, both families summed (bytes/s)
	LinkIn, LinkOut   float64 // through the adapter by any app, from the OS's counters (bytes/s)
	Peak, Peak6       float64
	RTT               float64 // smoothed probe latency (ms); -1 when never measured
	Loss              float64 // smoothed probe failure rate, 0..1
	Primary           bool    // currently the preferred NIC for its address family
}

// Scheduler picks the NIC each new flow leaves through. The lowest-latency NIC per address
// family (smoothed probe RTT) is the primary and takes every new flow while it has room;
// the others only see traffic when it is saturated or lossy, and until the first probes
// land the picking is the plain weighted least-active-flows below. Only new flows take the
// lock. With forceMultiFlow on, that last spreading rule becomes the only one: every new
// flow alternates across the healthy links instead of stacking on the fastest.
type Scheduler struct {
	mu             sync.Mutex
	slots          []*Slot
	primary        [2]*Slot // the preferred NIC per family; guarded by mu
	auto           bool
	forceMultiFlow bool // spread every new flow; guarded by mu
	now            func() time.Time
}

func NewScheduler(nics []nics.Nic) *Scheduler {
	s := &Scheduler{now: time.Now}
	s.Update(nics)
	return s
}

// SetAuto makes Tick derive weights from observed throughput instead of the static ones.
func (s *Scheduler) SetAuto(on bool) {
	s.mu.Lock()
	s.auto = on
	s.mu.Unlock()
}

// SetForceMultiFlow makes every new flow spread across the healthy links of the pool — the
// plain weighted least-active rule below — instead of stacking on the lowest-latency primary,
// which is what the app's "Forcer le trafic multi-flux" switch asks for: two links then carry
// the load together rather than the second one waiting for the first to saturate. The primary
// is still elected (and reported in Snapshot), so the dashboard keeps showing which link is
// the fastest, it just no longer decides where flows leave.
func (s *Scheduler) SetForceMultiFlow(on bool) {
	s.mu.Lock()
	s.forceMultiFlow = on
	s.mu.Unlock()
}

func normAddr(n nics.Nic) *slotAddr {
	a := &slotAddr{handle: n.NetworkHandle}
	if n.IP != nil {
		a.ip4 = n.IP.To4()
	}
	if n.IP6 != nil && n.IP6.To4() == nil {
		a.ip6 = n.IP6
	}
	return a
}

// Update applies a re-scan: keeps counters of known NICs, picks up changed addresses (DHCP
// renewal, a new IPv6 prefix), adds new ones (a tether plugged in later), and drops vanished
// ones once their last flow ends. Adapters are keyed by name.
func (s *Scheduler) Update(nics []nics.Nic) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, sl := range s.slots {
		sl.present = false
	}
	for _, n := range nics {
		na := normAddr(n)
		var found *Slot
		for _, sl := range s.slots {
			if sl.key == n.Name {
				found = sl
				break
			}
		}
		if found == nil {
			sl := &Slot{Name: n.Name, Index: n.Index, key: n.Name, weight: [2]float64{n.Weight, n.Weight}, present: true}
			sl.addr.Store(na)
			s.slots = append(s.slots, sl)
			continue
		}
		found.present = true
		if !s.auto {
			found.weight = [2]float64{n.Weight, n.Weight}
		}
		old := found.addr.Load()
		if !old.ip4.Equal(na.ip4) {
			found.state[Fam4] = stateUnknown // new address: re-verify
		}
		if !old.ip6.Equal(na.ip6) {
			found.state[Fam6] = stateUnknown
		}
		if !old.ip4.Equal(na.ip4) || !old.ip6.Equal(na.ip6) || old.handle != na.handle {
			found.addr.Store(na)
		}
	}
	kept := s.slots[:0]
	for _, sl := range s.slots {
		if sl.present || sl.active.Load() > 0 {
			kept = append(kept, sl)
		}
	}
	s.slots = kept
}

// A NIC counts as measured once it has carried this much (bytes/s, about 0.4 Mbit/s).
const minMeasuredPeak = 50_000

// Primary-selection tuning. The fastest interface (lowest smoothed probe RTT) takes every new
// flow; the others only see traffic when it is saturated or lossy. Latency is the right
// primary metric because it reacts within one probe interval to a link that degrades (loss
// retransmissions and queuing inflate it long before throughput collapses), and because
// alternating flows over two links of very different quality drags every flow down to the
// slow one — the speed-test drops this policy exists to fix.
const (
	primarySwitchPct = 25 // a challenger must be this much (%) faster to take the seat
	primaryLossMax   = 0.25
	saturationFrac   = 0.85 // of the observed capacity peak counts as saturated
	minFlowsSpread   = 12   // flows before spreading while no capacity was measured yet
)

// Tick samples throughput per family. With auto on, a family's weights follow each NIC's
// observed peak for that family (floored at 0.05, so a slow path still sees an occasional flow
// and is re-measured). Until every NIC with that family has been measured the weights stay
// equal, which spreads load so each one gets measured. The peak holds while a NIC is idle and
// only decays when it is in use but underperforming, so capacity is remembered across pauses.
func (s *Scheduler) Tick(dt time.Duration) {
	// nics.Octets calls into the OS, so read the counters on a snapshot of the slots without s.mu
	// (it would stall AcquireFor and Update) and take the lock only to store the results.
	s.mu.Lock()
	slots := append([]*Slot(nil), s.slots...)
	s.mu.Unlock()

	type sample struct {
		in, out uint64
		ok      bool
	}

	samples := make([]sample, len(slots))
	for i, sl := range slots {
		in, out, ok := nics.Octets(sl.Index)
		samples[i] = sample{in, out, ok}
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	sec := dt.Seconds()
	for i, sl := range slots {
		in, out, ok := samples[i].in, samples[i].out, samples[i].ok
		if !sl.present || !ok {
			sl.linkRateIn, sl.linkRateOut, sl.linkPrimed = 0, 0, false
			continue
		}
		if sl.linkPrimed && in >= sl.linkIn && out >= sl.linkOut { // counters can reset when an adapter reconnects
			sl.linkRateIn = float64(in-sl.linkIn) / sec
			sl.linkRateOut = float64(out-sl.linkOut) / sec
		}
		sl.linkIn, sl.linkOut, sl.linkPrimed = in, out, true
	}
	for _, f := range []Family{Fam4, Fam6} {
		var maxPeak float64
		live, measured := 0, true
		idle := map[*Slot]bool{}
		for _, sl := range s.slots {
			in, out := sl.bytesIn[f].Load(), sl.bytesOut[f].Load()
			sl.rateIn[f] = float64(in-sl.lastIn[f]) / sec
			sl.rateOut[f] = float64(out-sl.lastOut[f]) / sec
			sl.lastIn[f], sl.lastOut[f] = in, out
			r := sl.rateIn[f] + sl.rateOut[f]
			if r >= 0.05*sl.peak[f] {
				sl.peak[f] = math.Max(r, sl.peak[f]*0.97)
			} else {
				idle[sl] = true
			}
			if !sl.present || sl.ip(f) == nil {
				continue
			}
			live++
			maxPeak = math.Max(maxPeak, sl.peak[f])
		}
		// An idle NIC's old estimate may be stale or pessimistic (it was measured during a bad
		// moment and then starved of flows, so it could never correct itself). Let it drift back
		// up, capped at half of the best NIC, so it keeps getting a fair chance to be re-measured.
		for sl := range idle {
			if sl.present && sl.ip(f) != nil && sl.peak[f] > 0 {
				sl.peak[f] = math.Min(sl.peak[f]*1.03, math.Max(sl.peak[f], 0.5*maxPeak))
			}
		}
		for _, sl := range s.slots {
			if sl.present && sl.ip(f) != nil && sl.peak[f] < minMeasuredPeak {
				measured = false
			}
		}
		if !s.auto {
			continue
		}
		for _, sl := range s.slots {
			if !sl.present || sl.ip(f) == nil {
				continue
			}
			if live < 2 || !measured {
				sl.weight[f] = 1
			} else {
				sl.weight[f] = math.Max(0.05, sl.peak[f]/maxPeak)
			}
		}
	}
}

// Acquire picks an IPv4 NIC for a new flow, skipping the ones in skip. The caller must Release it.
func (s *Scheduler) Acquire(skip map[string]struct{}) *Slot { return s.AcquireFor(Fam4, skip) }
func (s *Scheduler) Release(sl *Slot)                       { s.ReleaseFor(sl, Fam4) }

// AcquireFor picks a NIC that has an address of the given family.
func (s *Scheduler) AcquireFor(f Family, skip map[string]struct{}) *Slot {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	// Tiers: 0 = probed up, 1 = not probed yet, 2 = probe failed or link error cooldown.
	// Only the best non-empty tier is used, so a dead tether gets no flows but, if every NIC
	// looks dead, flows still go somewhere rather than nowhere.
	var tiers [3][]*Slot
	for _, sl := range s.slots {
		if !sl.present || sl.ip(f) == nil {
			continue
		}
		if _, no := skip[sl.key]; no {
			continue
		}
		t := 1
		switch {
		case now.Before(sl.downUntil[f]) || sl.state[f] == stateDown:
			t = 2
		case sl.state[f] == stateUp:
			t = 0
		}
		tiers[t] = append(tiers[t], sl)
	}
	var pool []*Slot
	for _, tier := range tiers {
		if len(tier) > 0 {
			pool = tier
			break
		}
	}
	if len(pool) == 0 {
		return nil
	}
	// Stick to the lowest-latency NIC of the pool while it has room: the primary takes every
	// new flow, and the others only see traffic once it is saturated or lossy. Before the
	// first probes land (or when every measured link is lossy) this falls through to the
	// plain weighted least-active spreading below — and so does every flow when the scheduler
	// runs in forced multi-flow mode, whatever the latencies are.
	s.primary[f] = s.pickPrimaryLocked(f, pool)
	if !s.forceMultiFlow {
		if p := s.primary[f]; p != nil && !p.lossy(f) && !s.saturatedLocked(p, f) {
			p.active.Add(1)
			p.actF[f].Add(1)
			p.total.Add(1)
			return p
		}
	}
	// Spill-over: a lossy NIC (recent probe failures = packets being dropped) stops attracting
	// flows unless nothing healthy is left in the pool.
	var live []*Slot
	for _, sl := range pool {
		if !sl.lossy(f) {
			live = append(live, sl)
		}
	}
	if len(live) > 0 {
		pool = live
	}
	// Random start so ties don't resonate with the arrival pattern (a rotating counter can
	// lock onto e.g. "every third flow is a short DNS-like one" and starve an adapter).
	start := rand.IntN(len(pool))
	var best *Slot
	bestScore := math.Inf(1)
	for i := range pool {
		sl := pool[(start+i)%len(pool)]
		if score := float64(sl.actF[f].Load()+1) / sl.weight[f]; score < bestScore {
			best, bestScore = sl, score
		}
	}
	best.active.Add(1)
	best.actF[f].Add(1)
	best.total.Add(1)
	return best
}

// pickPrimaryLocked elects the preferred NIC for one family: the pool member with the lowest
// smoothed probe latency. The sitting primary keeps its seat unless a challenger is
// primarySwitchPct faster, so two similar links don't flap; when the primary left the pool
// (cooldown, vanished) its seat goes to the fastest member of the pool. Returns nil when
// nothing has been measured yet — the caller then falls back to plain least-active spreading,
// which is also what keeps behaviour identical before the first probe lands.
func (s *Scheduler) pickPrimaryLocked(f Family, pool []*Slot) *Slot {
	var best *Slot
	for _, sl := range pool {
		if r := sl.rtt[f].Load(); r > 0 && (best == nil || r < best.rtt[f].Load()) {
			best = sl
		}
	}
	if best == nil {
		return nil // no latency measured on any link of this pool yet
	}
	cur := s.primary[f]
	if cur == nil || cur == best {
		return best
	}
	inPool := false
	for _, sl := range pool {
		if sl == cur {
			inPool = true
			break
		}
	}
	if !inPool {
		return best // the old primary is gone: the fastest takes over
	}
	if cr := cur.rtt[f].Load(); cr == 0 || best.rtt[f].Load()*100 <= cr*(100-primarySwitchPct) {
		return best // a clearly faster challenger takes the seat
	}
	return cur
}

// saturatedLocked reports whether the primary already runs at its observed capacity: new flows
// would only queue behind the ones in progress and do better on another link. Before any
// capacity has been measured (no throughput seen yet) the active-flow count stands in.
// Callers hold s.mu.
func (s *Scheduler) saturatedLocked(sl *Slot, f Family) bool {
	if sl.peak[f] >= minMeasuredPeak {
		return sl.rateIn[f]+sl.rateOut[f] >= saturationFrac*sl.peak[f]
	}
	return sl.actF[f].Load() >= minFlowsSpread
}

// lossy reports whether recent probes failed often enough on this link that its packets are
// being dropped: it must stop attracting new flows unless it is the only path left.
func (sl *Slot) lossy(f Family) bool { return sl.loadLoss(f) > primaryLossMax }

// V6Usable reports whether any adapter can currently carry IPv6.
func (s *Scheduler) V6Usable() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, sl := range s.slots {
		if sl.present && sl.ip(Fam6) != nil && sl.state[Fam6] != stateDown {
			return true
		}
	}
	return false
}

// V6CoversAll reports whether every adapter that can carry IPv4 can also carry IPv6, i.e. whether
// balancing IPv6 flows would use the same set of adapters as IPv4 does. If not, IPv6 would funnel
// everything onto the adapters that have it.
func (s *Scheduler) V6CoversAll() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	any6 := false
	for _, sl := range s.slots {
		if !sl.present {
			continue
		}
		has6 := sl.ip(Fam6) != nil && sl.state[Fam6] != stateDown
		if has6 {
			any6 = true
		}
		if sl.ip(Fam4) != nil && sl.state[Fam4] != stateDown && !has6 {
			return false
		}
	}
	return any6
}

func (s *Scheduler) ReleaseFor(sl *Slot, f Family) {
	sl.active.Add(-1)
	sl.actF[f].Add(-1)
}

// SetState records a probe result. A passing probe also clears any link-error cooldown.
func (s *Scheduler) SetState(sl *Slot, f Family, up bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if up {
		sl.state[f] = stateUp
		sl.downUntil[f] = time.Time{}
	} else {
		sl.state[f] = stateDown
	}
}

// Fail sidelines a NIC whose link itself is broken (for one address family).
func (s *Scheduler) Fail(sl *Slot, f Family, cooldown time.Duration) {
	s.mu.Lock()
	sl.downUntil[f] = s.now().Add(cooldown)
	s.mu.Unlock()
	sl.failed.Add(1)
}

func (s *Scheduler) Snapshot() []SlotStats {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]SlotStats, 0, len(s.slots))
	for _, sl := range s.slots {
		a := sl.addr.Load()
		st := SlotStats{
			Name: sl.Name, State: "-", State6: "-", Present: sl.present,
			Weight: sl.weight[Fam4], Weight6: sl.weight[Fam6],
			Active: sl.active.Load(), Total: sl.total.Load(), Failed: sl.failed.Load(),
			BytesIn:  sl.rx(),
			BytesOut: sl.tx(),
			RateIn:   sl.rateIn[Fam4] + sl.rateIn[Fam6], RateOut: sl.rateOut[Fam4] + sl.rateOut[Fam6],
			LinkIn: sl.linkRateIn, LinkOut: sl.linkRateOut,
			Peak: sl.peak[Fam4], Peak6: sl.peak[Fam6],
		}
		if a.ip4 != nil {
			st.IP, st.State = a.ip4.String(), sl.state[Fam4].String()
		}
		if a.ip6 != nil {
			st.IP6, st.State6 = a.ip6.String(), sl.state[Fam6].String()
		}
		st.RTT = -1
		if us := sl.rtt[Fam4].Load(); us > 0 {
			st.RTT = float64(us) / 1000
		} else if us := sl.rtt[Fam6].Load(); us > 0 {
			st.RTT = float64(us) / 1000
		}
		st.Loss = math.Max(sl.loadLoss(Fam4), sl.loadLoss(Fam6))
		st.Primary = sl == s.primary[Fam4] || sl == s.primary[Fam6]
		out = append(out, st)
	}
	return out
}
