# Installs mergify-net on Windows. In PowerShell:
#
#   irm https://raw.githubusercontent.com/ysaiahdh/Mergify-net/main/install.ps1 | iex
#
# Downloads the newest installer, runs it silently (Windows asks once for admin rights), then starts
# the tray. Set $env:MERGIFY_NET_NO_START = '1' first to leave the tray off.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # the progress bar makes downloads very slow in Windows PowerShell
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$url = 'https://github.com/ysaiahdh/Mergify-net/releases/latest/download/mergify-net-setup.exe'
$setup = Join-Path $env:TEMP 'mergify-net-setup.exe'
Write-Host '==> Downloading mergify-net'
Invoke-WebRequest $url -OutFile $setup -UseBasicParsing
Write-Host '==> Installing'
$p = Start-Process $setup -ArgumentList '/SILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Verb RunAs -Wait -PassThru
Remove-Item $setup -ErrorAction SilentlyContinue
if ($p.ExitCode -ne 0) { throw "the installer failed (exit code $($p.ExitCode))" }

$dir = Join-Path $env:ProgramFiles 'mergify-net'
Write-Host "==> Installed $(& (Join-Path $dir 'mergify-net.exe') version)"
if ($env:MERGIFY_NET_NO_START -eq '1') {
    Write-Host '==> Start it from the Start menu: mergify-net'
} else {
    Start-Process (Join-Path $dir 'mergify-net-tray.exe')
    Write-Host '==> Started: the icon is in the tray (maybe behind the ^ arrow next to the clock)'
}
