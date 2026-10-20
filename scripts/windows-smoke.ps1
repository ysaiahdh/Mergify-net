# Smoke test of system-wide mode on Windows (CI runs it on windows-latest, as admin). The runner has
# one NIC, so this checks the TUN, routes, the per-adapter internet probe over pinned sockets while
# capture is on, relaying, and off; not balancing.
#
# usage: scripts/windows-smoke.ps1 dist\mergify-net.exe
param([string]$Bin = 'dist\mergify-net.exe')
$ErrorActionPreference = 'Continue'
$Bin = (Resolve-Path $Bin).Path
$state = Join-Path $env:TEMP 'mergify-net-smoke'
$script:fails = 0
function Pass($m) { Write-Host "PASS  $m" }
function Fail($m) { Write-Host "FAIL  $m"; $script:fails++ }
function Http { try { (Invoke-WebRequest https://example.com -UseBasicParsing -TimeoutSec 10).StatusCode } catch { 0 } }

# A TCP connect to 1.1.1.1:443 the way mergify-net pins its sockets: bound to the adapter's address,
# with IP_UNICAST_IF (31) set to its index, or only one of the two.
function Pinned([string]$src, [int]$idx) {
    $s = New-Object Net.Sockets.Socket([Net.Sockets.AddressFamily]::InterNetwork, [Net.Sockets.SocketType]::Stream, [Net.Sockets.ProtocolType]::Tcp)
    try {
        if ($idx -gt 0) { $s.SetSocketOption([Net.Sockets.SocketOptionLevel]::IP, [Net.Sockets.SocketOptionName]31, [Net.IPAddress]::HostToNetworkOrder($idx)) }
        if ($src) { $s.Bind((New-Object Net.IPEndPoint([Net.IPAddress]::Parse($src), 0))) }
        $r = $s.BeginConnect('1.1.1.1', 443, $null, $null)
        return ($r.AsyncWaitHandle.WaitOne(3000) -and $s.Connected)
    } catch { return $false } finally { $s.Close() }
}

$nic = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway } | Select-Object -First 1
$ip = $nic.IPv4Address.IPAddress; $idx = $nic.InterfaceIndex
Write-Host "== real adapter: $($nic.InterfaceAlias) $ip (index $idx)"
& $Bin list
Write-Host ("   pinned connects before: bind+if {0}, bind {1}, if {2}" -f (Pinned $ip $idx), (Pinned $ip 0), (Pinned '' $idx))

Write-Host '== system-wide ON'
Remove-Item -Recurse -Force $state -ErrorAction SilentlyContinue
$p = Start-Process $Bin -ArgumentList 'run', '-state-dir', $state, '-log', "$state\mergify-net.log", '-stats', '0', '-for', '150s' -PassThru -WindowStyle Hidden
for ($i = 0; $i -lt 60 -and -not ((& $Bin status -state-dir $state 2>$null | Out-String) -match 'is ON'); $i++) { Start-Sleep -Milliseconds 500 }
Start-Sleep 6 # let the internet probes run
$status = & $Bin status -state-dir $state | Out-String
Write-Host $status
if ($status -match 'is ON') { Pass 'mergify-net turned on' } else { Fail 'mergify-net did not turn on' }
route print -4 | Select-String ' 0.0.0.0 | 128.0.0.0 '
function TunAddr { Get-NetIPAddress -InterfaceAlias 'mergify-net' -AddressFamily IPv4 -ErrorAction SilentlyContinue | ForEach-Object { "$($_.IPAddress)/$($_.AddressState)" } }
Write-Host "   TUN IPv4 address: [$(TunAddr)]  interface: $((Get-NetIPInterface -InterfaceAlias 'mergify-net' -AddressFamily IPv4 -ErrorAction SilentlyContinue).ConnectionState)"
Write-Host ("   pinned connects while ON: bind+if {0}, bind {1}, if {2}" -f (Pinned $ip $idx), (Pinned $ip 0), (Pinned '' $idx))
if ($status -match 'v4 up') { Pass 'the adapter probe reaches the internet over its pinned socket while capture is on' }
else { Fail 'the adapter probe fails while capture is on' }
$code = Http
if ($code -ne 200) {
    for ($i = 0; $i -lt 6 -and $code -ne 200; $i++) { Start-Sleep 5; $code = Http; Write-Host "   retry $i HTTPS $code, TUN IPv4 address: [$(TunAddr)]" }
}
if ($code -eq 200) { Pass 'HTTPS works through mergify-net' } else { Fail 'HTTPS through mergify-net' }

Write-Host '== off'
& $Bin off -state-dir $state
Start-Sleep 3
if (-not (route print -4 | Select-String ' 128.0.0.0 ')) { Pass 'capture routes removed' } else { Fail 'capture routes left behind' }
if ((Http) -eq 200) { Pass 'internet works after off' } else { Fail 'internet after off' }
if (-not $p.HasExited) { Stop-Process $p -Force }
# ---- weak host send and forwarding on the real NIC (Mobile Hotspot, ICS and Hyper-V turn these on):
# Windows then ignores pinned sockets, so mergify-net turns both off while merging and puts them back.
Write-Host '== weak host send + forwarding on the real NIC'
Set-NetIPInterface -InterfaceIndex $idx -AddressFamily IPv4 -WeakHostSend Enabled -Forwarding Enabled
Remove-Item -Recurse -Force "$state-w" -ErrorAction SilentlyContinue
$pw = Start-Process $Bin -ArgumentList "run -state-dir `"$state-w`" -log `"$state-w\mergify-net.log`" -stats 0 -for 40s" -PassThru -WindowStyle Hidden
for ($i = 0; $i -lt 60 -and -not ((& $Bin status -state-dir "$state-w" 2>$null | Out-String) -match 'is ON'); $i++) { Start-Sleep -Milliseconds 500 }
Start-Sleep 6
$statusw = & $Bin status -state-dir "$state-w" | Out-String
Write-Host $statusw
Get-NetIPInterface -InterfaceIndex $idx -AddressFamily IPv4 | Format-Table -AutoSize InterfaceAlias, WeakHostSend, Forwarding | Out-String | Write-Host
if ($statusw -match 'v4 up') { Pass 'weak host adapter: the probe works while capture is on' } else { Fail 'weak host adapter: the probe fails while capture is on' }
if ((Http) -eq 200) { Pass 'weak host adapter: HTTPS works through mergify-net' } else { Fail 'weak host adapter: HTTPS through mergify-net' }
& $Bin off -state-dir "$state-w"
if (-not $pw.HasExited) { Stop-Process $pw -Force }
Start-Sleep 2
$after = Get-NetIPInterface -InterfaceIndex $idx -AddressFamily IPv4
if ($after.WeakHostSend -eq 'Enabled' -and $after.Forwarding -eq 'Enabled') { Pass 'off put weak host send and forwarding back' }
else { Fail "off did not put the settings back (weak host send $($after.WeakHostSend), forwarding $($after.Forwarding))" }
Get-Content "$state-w\mergify-net.log" -ErrorAction SilentlyContinue | Select-String 'weak host'
Set-NetIPInterface -InterfaceIndex $idx -AddressFamily IPv4 -WeakHostSend Disabled -Forwarding Disabled
Start-Sleep 2

# ---- the real NIC as a secondary adapter: a fake adapter's default route wins (for about a minute)
Write-Host '== secondary adapter: a fake adapter holds the best default route'
$fake = Start-Process (Join-Path (Split-Path $Bin) 'winfakeup.exe') -ArgumentList '75s' -PassThru -WindowStyle Hidden -RedirectStandardOutput "$env:TEMP\fakeup.log"
for ($i = 0; $i -lt 40 -and -not (Get-NetAdapter -Name fakeup -ErrorAction SilentlyContinue); $i++) { Start-Sleep -Milliseconds 500 }
Start-Sleep 3
Set-NetIPInterface -InterfaceIndex $idx -AddressFamily IPv4 -InterfaceMetric 50
Start-Sleep 2
route print -4 | Select-String ' 0.0.0.0 '
Write-Host ("   secondary, mergify-net off: bind+if {0}, bind {1}, if {2}" -f (Pinned $ip $idx), (Pinned $ip 0), (Pinned '' $idx))
Remove-Item -Recurse -Force "$state-2" -ErrorAction SilentlyContinue
# one string: Start-Process does not quote list items, and the adapter name has a space
$p2 = Start-Process $Bin -ArgumentList "run -include `"$($nic.InterfaceAlias)`" -state-dir `"$state-2`" -log `"$state-2\mergify-net.log`" -stats 0 -for 40s" -PassThru -WindowStyle Hidden
for ($i = 0; $i -lt 60 -and -not ((& $Bin status -state-dir "$state-2" 2>$null | Out-String) -match 'is ON'); $i++) { Start-Sleep -Milliseconds 500 }
Start-Sleep 6
$status2 = & $Bin status -state-dir "$state-2" | Out-String
Write-Host $status2
Write-Host ("   secondary, mergify-net ON: bind+if {0}, bind {1}, if {2}" -f (Pinned $ip $idx), (Pinned $ip 0), (Pinned '' $idx))
if ($status2 -match 'v4 up') { Pass 'a secondary adapter reaches the internet over its pinned socket while capture is on' }
else { Fail 'a secondary adapter fails its probe while capture is on' }
if ((Http) -eq 200) { Pass 'HTTPS works through mergify-net over the secondary adapter' } else { Fail 'HTTPS through mergify-net over the secondary adapter' }
& $Bin off -state-dir "$state-2"
if (-not $p2.HasExited) { Stop-Process $p2 -Force }
if (-not $fake.HasExited) { Stop-Process $fake -Force }
Set-NetIPInterface -InterfaceIndex $idx -AddressFamily IPv4 -AutomaticMetric Enabled
Start-Sleep 3
if ((Http) -eq 200) { Pass 'internet works again after the secondary-adapter test' } else { Fail 'internet after the secondary-adapter test' }
Get-Content "$env:TEMP\fakeup.log" -ErrorAction SilentlyContinue
Get-Content "$state-2\mergify-net.log" -ErrorAction SilentlyContinue | Select-Object -Last 20

Write-Host '== mergify-net log'
Get-Content "$state\mergify-net.log" -ErrorAction SilentlyContinue | Select-Object -Last 40

Write-Host ''
if ($script:fails -eq 0) { Write-Host 'ALL CHECKS PASSED' } else { Write-Host "$($script:fails) CHECK(S) FAILED" }
exit $script:fails
