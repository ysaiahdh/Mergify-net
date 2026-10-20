package mobile

import (
	"encoding/json"
	"testing"

	"mergify-net/internal/engine"
)

// The counters must land on the transport that carried them, whatever names the app used.
func TestSplitUsage(t *testing.T) {
	usages := []engine.DataUsage{
		{Name: "wifi", Rx: 100, Tx: 10},
		{Name: "cell", Rx: 200, Tx: 20},
		{Name: "tether0", Rx: 400, Tx: 40}, // not a registered transport: ignored
	}

	wifiRx, wifiTx, cellRx, cellTx := splitUsage(usages, "wifi", "cell")
	if wifiRx != 100 || wifiTx != 10 || cellRx != 200 || cellTx != 20 {
		t.Fatalf("got wifi %d/%d cell %d/%d, want 100/10 200/20", wifiRx, wifiTx, cellRx, cellTx)
	}

	// No registered names (manual nics cleared between two syncs): the app's own names win.
	wifiRx, wifiTx, cellRx, cellTx = splitUsage(usages, "", "")
	if wifiRx != 100 || wifiTx != 10 || cellRx != 200 || cellTx != 20 {
		t.Fatalf("fallback: got wifi %d/%d cell %d/%d, want 100/10 200/20", wifiRx, wifiTx, cellRx, cellTx)
	}

	// Registered names win over the fallback ones.
	wifiRx, wifiTx, cellRx, cellTx = splitUsage(
		[]engine.DataUsage{{Name: "rmnet0", Rx: 1, Tx: 2}, {Name: "wlan1", Rx: 4, Tx: 8}},
		"rmnet0", "wlan1",
	)
	if wifiRx != 1 || wifiTx != 2 || cellRx != 4 || cellTx != 8 {
		t.Fatalf("registered: got wifi %d/%d cell %d/%d, want 1/2 4/8", wifiRx, wifiTx, cellRx, cellTx)
	}
}

// Without a running engine the JSON still parses and every counter is zero (and total is the
// sum of the four).
func TestGetStatsJSONNoService(t *testing.T) {
	var st struct {
		Running     bool  `json:"running"`
		WifiRxBytes int64 `json:"wifi_rx_bytes"`
		WifiTxBytes int64 `json:"wifi_tx_bytes"`
		CellRxBytes int64 `json:"cell_rx_bytes"`
		CellTxBytes int64 `json:"cell_tx_bytes"`
		TotalBytes  int64 `json:"total_bytes"`
	}
	if err := json.Unmarshal([]byte(GetStatsJSON()), &st); err != nil {
		t.Fatalf("GetStatsJSON: %v", err)
	}
	if st.Running || st.WifiRxBytes != 0 || st.WifiTxBytes != 0 ||
		st.CellRxBytes != 0 || st.CellTxBytes != 0 || st.TotalBytes != 0 {
		t.Fatalf("expected an idle all-zero report, got %+v", st)
	}
}

// The settings switch is remembered before any engine exists (Kotlin pushes it at startup and
// on every change, the VPN may be down at the time), and nothing else about the stats changes.
func TestSetForceMultiFlowIsRememberedWithoutService(t *testing.T) {
	if svc != nil {
		t.Fatal("test needs the engine stopped")
	}
	SetForceMultiFlow(true)
	defer SetForceMultiFlow(false)
	if !forceMultiFlow.Load() {
		t.Fatal("flag not stored: StartVpn would not apply it to the next scheduler")
	}
	SetForceMultiFlow(false)
	if forceMultiFlow.Load() {
		t.Fatal("switching back off must clear the stored flag")
	}
	var st struct {
		Running bool `json:"running"`
	}
	if err := json.Unmarshal([]byte(GetStatsJSON()), &st); err != nil || st.Running {
		t.Fatalf("stats must stay idle and parsable, running=%v err=%v", st.Running, err)
	}
}
