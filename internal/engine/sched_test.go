package engine

import (
	"math"
	"net"
	"testing"
	"time"

	"mergify-net/internal/nics"
)

func nic(name, ip string, w float64) nics.Nic {
	return nics.Nic{Name: name, IP: net.ParseIP(ip), Weight: w}
}

func countBy(picks []*Slot, name string) int {
	n := 0
	for _, p := range picks {
		if p.Name == name {
			n++
		}
	}
	return n
}

func acquireN(s *Scheduler, n int) []*Slot {
	out := make([]*Slot, n)
	for i := range out {
		out[i] = s.Acquire(nil)
	}
	return out
}

func TestEqualWeightsSpreadEvenly(t *testing.T) {
	s := NewScheduler([]nics.Nic{nic("A", "10.0.0.1", 1), nic("B", "10.0.0.2", 1)})
	p := acquireN(s, 6)
	if countBy(p, "A") != 3 || countBy(p, "B") != 3 {
		t.Fatalf("want 3/3, got A=%d B=%d", countBy(p, "A"), countBy(p, "B"))
	}
}

func TestWeightsSkew(t *testing.T) {
	s := NewScheduler([]nics.Nic{nic("A", "10.0.0.1", 3), nic("B", "10.0.0.2", 1)})
	p := acquireN(s, 8)
	if countBy(p, "A") != 6 || countBy(p, "B") != 2 {
		t.Fatalf("want 6/2, got A=%d B=%d", countBy(p, "A"), countBy(p, "B"))
	}
}

func TestReleaseFreesNic(t *testing.T) {
	s := NewScheduler([]nics.Nic{nic("A", "10.0.0.1", 1), nic("B", "10.0.0.2", 1)})
	first := s.Acquire(nil)
	s.Acquire(nil)
	s.Release(first)
	if got := s.Acquire(nil); got != first {
		t.Fatalf("want the released NIC %s, got %s", first.Name, got.Name)
	}
}

func TestFailCooldown(t *testing.T) {
	s := NewScheduler([]nics.Nic{nic("A", "10.0.0.1", 1), nic("B", "10.0.0.2", 1)})
	clock := time.Unix(1000, 0)
	s.now = func() time.Time { return clock }
	bad := s.Snapshot()[0]
	var badSlot *Slot
	for _, sl := range s.slots {
		if sl.Name == bad.Name {
			badSlot = sl
		}
	}
	s.Fail(badSlot, Fam4, 5*time.Second)
	for i := 0; i < 4; i++ {
		if s.Acquire(nil) == badSlot {
			t.Fatal("cooling-down NIC was picked")
		}
	}
	clock = clock.Add(6 * time.Second)
	seen := false
	for i := 0; i < 6; i++ {
		if s.Acquire(nil) == badSlot {
			seen = true
		}
	}
	if !seen {
		t.Fatal("NIC never came back after cooldown")
	}
}

func TestAllDownStillServes(t *testing.T) {
	s := NewScheduler([]nics.Nic{nic("A", "10.0.0.1", 1)})
	s.Fail(s.slots[0], Fam4, time.Hour)
	if s.Acquire(nil) == nil {
		t.Fatal("total outage: all-NICs-down must still hand one out")
	}
}

func TestProbeStateTiers(t *testing.T) {
	s := NewScheduler([]nics.Nic{nic("A", "10.0.0.1", 1), nic("B", "10.0.0.2", 1), nic("C", "10.0.0.3", 1)})
	a, b, c := s.slots[0], s.slots[1], s.slots[2]
	s.SetState(a, Fam4, true)
	s.SetState(c, Fam4, false)
	// A is up, B is unprobed, C is dead: only A gets flows while it is the best tier.
	for i := 0; i < 5; i++ {
		if got := s.Acquire(nil); got != a {
			t.Fatalf("flow %d went to %s, want the probed-up NIC", i, got.Name)
		}
	}
	s.SetState(a, Fam4, false) // now B (unknown) beats the two dead ones
	if got := s.Acquire(nil); got != b {
		t.Fatalf("want unprobed NIC over dead ones, got %s", got.Name)
	}
	s.SetState(b, Fam4, false) // everything dead: still serve, never an outage
	if s.Acquire(nil) == nil {
		t.Fatal("all-dead must still hand out a NIC")
	}
	s.SetState(c, Fam4, true) // a recovered NIC takes over immediately
	if got := s.Acquire(nil); got != c {
		t.Fatalf("recovered NIC should win, got %s", got.Name)
	}
}

func TestProbeAgainstLocalListener(t *testing.T) {
	needLoopbackAliases(t)
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	old := probeTargets[Fam4]
	defer func() { probeTargets[Fam4] = old }()
	probeTargets[Fam4] = []string{ln.Addr().String()}

	s := NewScheduler([]nics.Nic{nic("A", "127.0.0.2", 1)})
	if !probe(s.slots[0], Fam4) {
		t.Fatal("probe should pass against a reachable target")
	}
	ln.Close()
	if probe(s.slots[0], Fam4) {
		t.Fatal("probe should fail once the target is gone")
	}
}

func TestIPv6OnlyGoesToNicsWithV6(t *testing.T) {
	n6 := nic("B", "10.0.0.2", 1)
	n6.IP6 = net.ParseIP("2001:db8::2")
	s := NewScheduler([]nics.Nic{nic("A", "10.0.0.1", 1), n6})
	for i := 0; i < 4; i++ {
		if got := s.AcquireFor(Fam6, nil); got == nil || got.Name != "B" {
			t.Fatalf("v6 flow %d went to %v, want the only v6-capable NIC B", i, got)
		}
	}
	if got := s.AcquireFor(Fam4, nil); got == nil {
		t.Fatal("v4 flows must still work")
	}
	s2 := NewScheduler([]nics.Nic{nic("A", "10.0.0.1", 1)})
	if s2.AcquireFor(Fam6, nil) != nil {
		t.Fatal("no v6-capable NIC: v6 flows must be refused so apps fall back to IPv4")
	}
}

func TestV6StateIsIndependentOfV4(t *testing.T) {
	n := nic("A", "10.0.0.1", 1)
	n.IP6 = net.ParseIP("2001:db8::1")
	m := nic("B", "10.0.0.2", 1)
	m.IP6 = net.ParseIP("2001:db8::2")
	s := NewScheduler([]nics.Nic{n, m})
	a, b := s.slots[0], s.slots[1]
	s.SetState(a, Fam4, true)
	s.SetState(b, Fam4, true)
	s.SetState(a, Fam6, false) // A has working v4 but its v6 is dead
	s.SetState(b, Fam6, true)
	for i := 0; i < 4; i++ {
		got := s.AcquireFor(Fam6, nil)
		if got != b {
			t.Fatalf("v6 flow %d went to %s, want B", i, got.Name)
		}
		s.ReleaseFor(got, Fam6)
	}
	seen := map[string]bool{}
	for i := 0; i < 4; i++ {
		seen[s.AcquireFor(Fam4, nil).Name] = true
	}
	if !seen["A"] || !seen["B"] {
		t.Fatalf("v4 should still use both NICs, got %v", seen)
	}
}

// A hotspot that is fast over native IPv6 but slow over translated IPv4 must be weighted per
// family: nearly starved for v4 flows, fully used for v6 flows.
func TestAutoWeightsArePerFamily(t *testing.T) {
	a := nic("A", "10.0.0.1", 1)
	a.IP6 = net.ParseIP("2001:db8::1")
	b := nic("B", "10.0.0.2", 1)
	b.IP6 = net.ParseIP("2001:db8::2")
	s := NewScheduler([]nics.Nic{a, b})
	s.SetAuto(true)
	sa, sb := s.slots[0], s.slots[1]
	const mbit = 125_000 // bytes/s
	// v4: A 100 Mbit/s, B 5 Mbit/s.  v6: both 100 Mbit/s.
	for i := 0; i < 3; i++ {
		sa.bytesIn[Fam4].Add(100 * mbit)
		sb.bytesIn[Fam4].Add(5 * mbit)
		sa.bytesIn[Fam6].Add(100 * mbit)
		sb.bytesIn[Fam6].Add(100 * mbit)
		s.Tick(time.Second)
	}
	if got := sb.weight[Fam4] / sa.weight[Fam4]; got > 0.06 {
		t.Fatalf("slow v4 path should be down-weighted to ~0.05, got ratio %.2f", got)
	}
	if got := sb.weight[Fam6] / sa.weight[Fam6]; got < 0.95 {
		t.Fatalf("fast v6 path must keep full weight, got ratio %.2f", got)
	}
	// And the peak is remembered while idle (no traffic for a while).
	for i := 0; i < 20; i++ {
		s.Tick(time.Second)
	}
	if sb.peak[Fam6] < 90*mbit {
		t.Fatalf("idle NIC forgot its capacity: peak %.0f", sb.peak[Fam6])
	}
}

// A NIC whose estimate was poisoned by one bad moment must not stay starved forever.
func TestPessimisticEstimateRecoversWhileIdle(t *testing.T) {
	s := NewScheduler([]nics.Nic{nic("A", "10.0.0.1", 1), nic("B", "10.0.0.2", 1)})
	s.SetAuto(true)
	sa, sb := s.slots[0], s.slots[1]
	const mbit = 125_000
	for i := 0; i < 3; i++ { // B looks 20x slower than A
		sa.bytesIn[Fam4].Add(100 * mbit)
		sb.bytesIn[Fam4].Add(5 * mbit)
		s.Tick(time.Second)
	}
	if w := sb.weight[Fam4] / sa.weight[Fam4]; w > 0.06 {
		t.Fatalf("setup: B should be down-weighted, ratio %.2f", w)
	}
	for i := 0; i < 80; i++ { // then nobody uses B, and A keeps working
		sa.bytesIn[Fam4].Add(100 * mbit)
		s.Tick(time.Second)
	}
	if w := sb.weight[Fam4] / sa.weight[Fam4]; w < 0.45 {
		t.Fatalf("idle NIC should drift back up to ~0.5 so it is re-measured, ratio %.2f", w)
	}
}

func TestV6CoverageDecidesWhetherIPv6IsBalanced(t *testing.T) {
	a := nic("A", "10.0.0.1", 1)
	a.IP6 = net.ParseIP("2001:db8::1")
	b := nic("B", "10.0.0.2", 1) // IPv4 only
	s := NewScheduler([]nics.Nic{a, b})
	if s.V6CoversAll() {
		t.Fatal("B has no IPv6: IPv6 would funnel onto A, coverage must be partial")
	}
	if !s.V6Usable() {
		t.Fatal("A can carry IPv6")
	}
	b.IP6 = net.ParseIP("2001:db8::2")
	s.Update([]nics.Nic{a, b})
	if !s.V6CoversAll() {
		t.Fatal("every adapter has IPv6 now")
	}
	s.SetState(s.slots[1], Fam6, false) // B's IPv6 probe fails
	if s.V6CoversAll() {
		t.Fatal("a dead IPv6 path on B means partial coverage again")
	}
	only4 := NewScheduler([]nics.Nic{nic("A", "10.0.0.1", 1)})
	if only4.V6CoversAll() || only4.V6Usable() {
		t.Fatal("no IPv6 anywhere: nothing to allow")
	}
}

func TestSkipSet(t *testing.T) {
	s := NewScheduler([]nics.Nic{nic("A", "10.0.0.1", 1), nic("B", "10.0.0.2", 1)})
	a, b := s.slots[0], s.slots[1]
	if got := s.Acquire(map[string]struct{}{a.key: {}}); got != b {
		t.Fatal("skip set not honoured")
	}
	if got := s.Acquire(map[string]struct{}{a.key: {}, b.key: {}}); got != nil {
		t.Fatal("want nil when everything is skipped")
	}
}

func TestUpdateAddsAndKeepsCounters(t *testing.T) {
	s := NewScheduler([]nics.Nic{nic("A", "10.0.0.1", 1)})
	s.Acquire(nil)
	s.Update([]nics.Nic{nic("A", "10.0.0.1", 1), nic("B", "10.0.0.2", 1)})
	snap := s.Snapshot()
	if len(snap) != 2 || snap[0].Total != 1 {
		t.Fatalf("unexpected snapshot: %+v", snap)
	}
}

func TestUpdateDropsVanishedWhenIdle(t *testing.T) {
	s := NewScheduler([]nics.Nic{nic("A", "10.0.0.1", 1), nic("B", "10.0.0.2", 1)})
	busy := s.Acquire(nil)
	s.Update(nil)
	if len(s.Snapshot()) != 1 {
		t.Fatalf("idle NIC should be dropped, busy one kept: %+v", s.Snapshot())
	}
	if s.Acquire(nil) != nil {
		t.Fatal("vanished NIC must not get new flows")
	}
	s.Release(busy)
	s.Update(nil)
	if len(s.Snapshot()) != 0 {
		t.Fatal("NIC should be dropped once idle")
	}
}

// The lowest-latency interface takes every flow while it has room; the other one only sees
// traffic once the primary counts as saturated (no capacity measured yet: the active-flow
// count stands in for it).
func TestStickyPrimaryByLatency(t *testing.T) {
	s := NewScheduler([]nics.Nic{nic("wifi", "10.0.0.1", 1), nic("cell", "10.0.0.2", 1)})
	wifi, cell := s.slots[0], s.slots[1]
	s.SetState(wifi, Fam4, true)
	s.SetState(cell, Fam4, true)
	wifi.rtt[Fam4].Store(20_000) // µs
	cell.rtt[Fam4].Store(80_000)

	for i := 0; i < minFlowsSpread; i++ {
		if got := s.Acquire(nil); got != wifi {
			t.Fatalf("flow %d went to %s, want the lowest-latency primary (wifi)", i, got.Name)
		}
	}
	snap := s.Snapshot()
	if !snap[0].Primary || snap[0].RTT != 20 {
		t.Fatalf("snapshot must expose the primary and its latency: %+v", snap[0])
	}
	// The primary now counts as saturated: new flows spill onto the secondary.
	seen := map[string]int{}
	for i := 0; i < 8; i++ {
		seen[s.Acquire(nil).Name]++
	}
	if seen["cell"] == 0 {
		t.Fatalf("saturated primary must spill to the secondary, got %v", seen)
	}
}

// Forced multi-flow inverts that policy: the fastest link must no longer take every new flow
// while it has room — the flows alternate across both links instead — even though the primary
// is still elected for the stats.
func TestForceMultiFlowSpreadsBeforeSaturation(t *testing.T) {
	s := NewScheduler([]nics.Nic{nic("wifi", "10.0.0.1", 1), nic("cell", "10.0.0.2", 1)})
	wifi, cell := s.slots[0], s.slots[1]
	s.SetState(wifi, Fam4, true)
	s.SetState(cell, Fam4, true)
	wifi.rtt[Fam4].Store(20_000) // µs: wifi is clearly the fastest link
	cell.rtt[Fam4].Store(80_000)

	s.SetForceMultiFlow(true)
	seen := map[string]int{}
	for i := 0; i < 10; i++ {
		seen[s.Acquire(nil).Name]++
	}
	if seen["wifi"] == 0 || seen["cell"] == 0 {
		t.Fatalf("forced multi-flow must alternate between both links, got %v", seen)
	}
	if snap := s.Snapshot(); !snap[0].Primary || snap[0].RTT != 20 {
		t.Fatalf("the fastest NIC must still be reported as primary: %+v", snap[0])
	}

	// Switching it back off restores the latency preference (the flows held by the forced
	// phase are released first, so the primary is not already counted as saturated).
	for _, p := range []*Slot{wifi, cell} {
		for p.actF[Fam4].Load() > 0 {
			s.Release(p)
		}
	}
	s.SetForceMultiFlow(false)
	for i := 0; i < minFlowsSpread; i++ {
		got := s.Acquire(nil)
		if got != wifi {
			t.Fatalf("flow %d went to %s, want the lowest-latency primary again", i, got.Name)
		}
		s.Release(got)
	}
}

// A challenger only takes the seat when it is clearly (primarySwitchPct) faster: similar
// latencies must not make the primary flap back and forth.
func TestPrimarySwitchNeedsMargin(t *testing.T) {
	s := NewScheduler([]nics.Nic{nic("wifi", "10.0.0.1", 1), nic("cell", "10.0.0.2", 1)})
	wifi, cell := s.slots[0], s.slots[1]
	s.SetState(wifi, Fam4, true)
	s.SetState(cell, Fam4, true)
	wifi.rtt[Fam4].Store(20_000)
	cell.rtt[Fam4].Store(30_000)

	for i := 0; i < 3; i++ { // wifi is fastest: primary
		if got := s.Acquire(nil); got != wifi {
			t.Fatalf("flow %d went to %s, want the fastest NIC (wifi)", i, got.Name)
		}
	}
	cell.rtt[Fam4].Store(16_000) // 20% faster: inside the margin, no handover
	if got := s.Acquire(nil); got != wifi {
		t.Fatalf("a challenger inside the margin must not steal the seat, got %s", got.Name)
	}
	cell.rtt[Fam4].Store(14_000) // 33% faster: outside the margin, handover
	if got := s.Acquire(nil); got != cell {
		t.Fatalf("a clearly faster challenger must take the seat, got %s", got.Name)
	}
}

// A lossy primary (recent probe failures = dropped packets) yields every new flow to the
// secondary, even though it still wins on latency.
func TestLossyPrimaryYieldsAllFlows(t *testing.T) {
	s := NewScheduler([]nics.Nic{nic("wifi", "10.0.0.1", 1), nic("cell", "10.0.0.2", 1)})
	wifi, cell := s.slots[0], s.slots[1]
	s.SetState(wifi, Fam4, true)
	s.SetState(cell, Fam4, true)
	wifi.rtt[Fam4].Store(10_000)
	cell.rtt[Fam4].Store(50_000)
	for i := 0; i < 3; i++ { // loss EWMA: 0.2 → 0.36 → 0.488 > primaryLossMax
		wifi.recordProbe(Fam4, false, 0)
	}
	for i := 0; i < 6; i++ {
		if got := s.Acquire(nil); got != cell {
			t.Fatalf("flow %d went to the lossy primary (%s), want the healthy secondary", i, got.Name)
		}
	}
}

func TestRecordProbeSmooths(t *testing.T) {
	s := NewScheduler([]nics.Nic{nic("A", "10.0.0.1", 1)})
	sl := s.slots[0]
	sl.recordProbe(Fam4, true, 10*time.Millisecond) // first sample: taken as-is
	if got := sl.rtt[Fam4].Load(); got != 10_000 {
		t.Fatalf("first sample should be raw, got %d µs", got)
	}
	sl.recordProbe(Fam4, true, 30*time.Millisecond) // EWMA: 0.3*30 + 0.7*10 = 16 ms
	if got := sl.rtt[Fam4].Load(); got != 16_000 {
		t.Fatalf("smoothed RTT = %d µs, want 16000", got)
	}
	sl.recordProbe(Fam4, false, 0)
	sl.recordProbe(Fam4, false, 0) // loss: 0.2 → 0.36
	if got := sl.loadLoss(Fam4); math.Abs(got-0.36) > 1e-9 {
		t.Fatalf("loss after two failed probes = %v, want 0.36", got)
	}
}
