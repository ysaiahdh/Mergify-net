package nics

import (
	"net"
	"testing"
)

// fakeBridge records the calls the engine would route to the VpnService.
type fakeBridge struct {
	protectOK  bool
	bindErr    error
	protected  []int
	boundTo    []int64
	fdFallback int // fd seen by Bind when Protect failed (should never be called)
}

func (f *fakeBridge) ProtectSocket(fd int) bool {
	f.protected = append(f.protected, fd)
	return f.protectOK
}

func (f *fakeBridge) BindSocketToNetwork(fd int, handle int64) error {
	f.boundTo = append(f.boundTo, handle)
	return f.bindErr
}

func TestSocketControlProtectsAndBinds(t *testing.T) {
	fb := &fakeBridge{protectOK: true}
	SetSocketBridge(fb)
	defer SetSocketBridge(nil)

	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Skipf("no loopback: %v", err)
	}
	defer ln.Close()
	go func() {
		c, err := ln.Accept()
		if err == nil {
			c.Close()
		}
	}()
	c, err := (&net.Dialer{Control: SocketControl(0, 42, false)}).Dial("tcp4", ln.Addr().String())
	if err != nil {
		t.Fatalf("dial through bridge: %v", err)
	}
	c.Close()
	if len(fb.protected) != 1 {
		t.Fatalf("ProtectSocket called %d times, want 1", len(fb.protected))
	}
	if len(fb.boundTo) != 1 || fb.boundTo[0] != 42 {
		t.Fatalf("BindSocketToNetwork handles = %v, want [42]", fb.boundTo)
	}
}

func TestSocketControlProtectOnlyWithoutHandle(t *testing.T) {
	fb := &fakeBridge{protectOK: true}
	SetSocketBridge(fb)
	defer SetSocketBridge(nil)

	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Skipf("no loopback: %v", err)
	}
	defer ln.Close()
	go func() {
		c, err := ln.Accept()
		if err == nil {
			c.Close()
		}
	}()
	c, err := (&net.Dialer{Control: SocketControl(0, 0, false)}).Dial("tcp4", ln.Addr().String())
	if err != nil {
		t.Fatalf("dial through bridge: %v", err)
	}
	c.Close()
	if len(fb.protected) != 1 || len(fb.boundTo) != 0 {
		t.Fatalf("protected=%v boundTo=%v, want 1 protect and no bind", fb.protected, fb.boundTo)
	}
}

func TestSocketControlFailsWhenProtectFails(t *testing.T) {
	fb := &fakeBridge{protectOK: false}
	SetSocketBridge(fb)
	defer SetSocketBridge(nil)

	if _, err := (&net.Dialer{Control: SocketControl(0, 42, false)}).Dial("tcp4", "192.0.2.1:80"); err == nil { // TEST-NET, never reached
		t.Fatal("dial succeeded although ProtectSocket failed")
	}
	if len(fb.boundTo) != 0 {
		t.Fatalf("BindSocketToNetwork called after failed protect: %v", fb.boundTo)
	}
}

func TestSetManualNicsOverridesDiscovery(t *testing.T) {
	defer SetManualNics(nil)
	SetManualNics([]ManualNic{
		{Name: "wlan0", IP: net.ParseIP("192.168.1.10"), NetworkHandle: 11},
		{Name: "rmnet0", IP: net.ParseIP("10.0.0.5"), IP6: net.ParseIP("2001:db8::1"), Weight: 2, NetworkHandle: 22},
	})
	got, skipped, err := DiscoverNicsDetailed(nil, nil)
	if err != nil || len(skipped) != 0 {
		t.Fatalf("DiscoverNicsDetailed = %v, %v, %v", got, skipped, err)
	}
	if len(got) != 2 {
		t.Fatalf("got %d NICs, want 2", len(got))
	}
	if got[0].Name != "wlan0" || got[0].NetworkHandle != 11 || got[0].Weight != 1 {
		t.Fatalf("wlan0 = %+v", got[0])
	}
	if got[1].Name != "rmnet0" || got[1].NetworkHandle != 22 || got[1].Weight != 2 || got[1].IP6 == nil {
		t.Fatalf("rmnet0 = %+v", got[1])
	}

	SetManualNics(nil) // back to auto-discovery
	if _, ok := manualNics(); ok {
		t.Fatal("manual mode still on after SetManualNics(nil)")
	}
}
