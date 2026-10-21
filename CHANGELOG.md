# Changelog

What changed in each release. Each version's section becomes its GitHub release notes, so add the
entry here before pushing a version tag.

## [Unreleased]

### Added
- **Android: data usage tracking.** The engine's per-interface atomic counters (Rx/Tx per address
  family) are now aggregated per transport in `GetStatsJSON` — `wifi_rx_bytes`, `wifi_tx_bytes`,
  `cell_rx_bytes`, `cell_tx_bytes` and `total_bytes` — and the dashboard gained a *CONSOMMATION*
  card: session total, cumulative total kept across sessions (SharedPreferences), and the
  Wi-Fi / mobile split with a share bar.
- **Android: *Paramètres* screen.** A gear opens it from the dashboard (same dark, hairline
  style). Two controls, both persisted in `SharedPreferences`: a cellular data budget slider
  (100 Mo … 20 Go, plus *Illimité*) and a *Forcer le trafic multi-flux* switch. The stats poll
  loop cuts the cellular route (`UpdateNetworks(wifiHandle, 0)`) and notifies the user the moment
  the cumulative mobile volume reaches the budget — raising it restores the route on the next
  tick — and the new exported `Mobile.SetForceMultiFlow(enabled)` makes the Go scheduler alternate
  every new flow across both links instead of stacking them on the lowest-latency one.

### Changed
- **Rebranded as Mergify-Net.** The project, Go module (`mergify-net`), binary, state directories and
  Android app (`com.mergify.net`, AAR `mergify.aar`) now carry the Mergify-Net name everywhere. The
  Android app aggregates Wi-Fi and cellular (4G/5G) links through a local VpnService, with a
  lowest-latency primary policy (see the README's Android section).

## [0.3.3] - 2026-10-20

### Added
- **Start at sign-in from the tray menu** on Windows, macOS and Linux (#28): tick *Start at sign-in*. It
  does what `mergify-net autostart on` does, without a terminal. On a Mac it needs `sudo mergify-net install` once,
  as before.

### Fixed
- **Windows: fewer antivirus false positives.** `mergify-net.exe` and `mergify-net-tray.exe` now carry a
  name, version, icon and manifest. Bare unsigned Go programs are what antivirus machine-learning models
  flag (Microsoft Defender's `Trojan:Win32/Wacatac.B!ml` on VirusTotal).

## [0.3.2] - 2026-10-19

### Fixed
- **Windows: no more PowerShell window flashing when a new adapter connects** (#10). The adapter capability
  check now hides its PowerShell window, the way the other helper commands in `internal/tun` already did.

## [0.3.1] - 2026-10-19

### Added
- **One-line install.** `curl -fsSL https://raw.githubusercontent.com/ysaiahdh/Mergify-net/main/install.sh | sh` on
  macOS and Linux (it uses Homebrew, apt, dnf, zypper, apk or pacman when it can, the release tarball
  otherwise, and starts the tray), and `irm https://raw.githubusercontent.com/ysaiahdh/Mergify-net/main/install.ps1 | iex`
  in PowerShell on Windows.
- Releases also carry the downloads under names without the version (`mergify-net-setup.exe`,
  `mergify-net-macos-universal.tar.gz`, `mergify-net-linux-$(uname -m).tar.gz`), so
  `releases/latest/download/...` links always give the newest one.
- **One-line uninstall** on macOS and Linux: `curl -fsSL https://raw.githubusercontent.com/ysaiahdh/Mergify-net/main/install.sh | sh -s -- uninstall`
  stops mergify-net and removes it the way it was installed, with its repository and key. The README has the
  Windows line and every manual way.

### Fixed
- **macOS: the wired Ethernet disappeared when an Android phone was connected through TetherKit** (#20).
  When the phone became the primary connection, macOS could not add its own route for the Ethernet next
  to mergify-net's, and mergify-net then removed its route, leaving the Ethernet with none until the cable was
  plugged in again. mergify-net now leaves that route to macOS.
- **Windows: turning on merging could stop all internet, with every adapter showing "no internet"**
  (#10). On an adapter with *weak host send* or *forwarding* turned on, which Mobile Hotspot, Internet
  Connection Sharing, Hyper-V and some VPNs do, Windows ignores the adapter a connection is pinned to, so
  mergify-net's own connections went back into its virtual adapter. While merging, mergify-net now turns those
  two settings off on the adapters it uses and puts them back afterwards (sharing that connection pauses
  meanwhile). It also re-applies its virtual adapter's address when Windows drops it right after start.
- `sudo mergify-net install` refused on Macs where `/usr/local/bin` is writable by your user (Intel Macs
  with Homebrew). It now puts its root-owned copy in `/opt/mergify-net/bin` there, and links `mergify-net` into
  `/usr/local/bin`.

## [0.3.0] - 2026-10-17

### Added
- **mergify-net as a normal package on every Linux distribution.** Releases now include a `.deb`, `.rpm`,
  `.apk` (Alpine) and Arch package for every CPU: x86-64, ARM64, ARMv7, ARMv6 (Raspberry Pi), x86 32-bit,
  RISC-V, PowerPC (ppc64le), s390x and LoongArch, plus a tarball for each. They are also published as signed
  repositories on GitHub Pages, so `sudo apt install mergify-net`, `sudo dnf install mergify-net`,
  `sudo zypper install mergify-net`, `sudo apk add mergify-net` and `sudo pacman -S mergify-net` work after a
  one-time setup. The packages give mergify-net its network rights themselves, and add an app-menu entry,
  icons and AppStream information.
- **Pauses by itself when only one connection works, and resumes when two do.** With fewer than two
  connections that reach the internet there is nothing to merge, so the tray pauses merging ("Paused: one
  connection") and starts it again when a second connection works. Pausing or starting by hand always wins,
  and it waits for things to settle first: 10 seconds with nothing moving (2 minutes if a download or
  upload is still going) before pausing, 3 seconds before resuming, and at least 30 seconds between
  automatic switches.
  Untick *Pause when there is only one connection* in the tray menu, or run `mergify-net tray -auto-pause=false`.

### Changed
- On macOS, pausing and resuming no longer removes and recreates the virtual adapter, so it no longer
  drops a TetherKit (Android) link. Starting and quitting still do.
- New connections no longer wait while the once-a-second adapter speed reading runs (thanks @b0r1ssh).

## [0.2.0] - 2026-10-16

### Added
- **Downloads for macOS and Linux.** Each release now also has `mergify-net-X.Y.Z-macos-universal.tar.gz`
  (Apple Silicon and Intel) and `mergify-net-X.Y.Z-linux-x86_64.tar.gz` / `-linux-aarch64.tar.gz`. The steps
  to run them are in the README.
- **macOS support** (Apple Silicon and Intel). System-wide mode, the menu-bar tray, the SOCKS5 proxy, live
  per-adapter speeds and start at login (a LaunchAgent) all work. The TUN is a utun, flows are pinned with
  `IP_BOUND_IF` plus a scoped default route per adapter, and adapters come from the routing table. iPhone
  hotspot over USB works as is; Android USB tethering needs the TetherKit app. `sudo mergify-net install`
  sets up a passwordless rule for `mergify-net tray`.
- **Linux support**, including NixOS. System-wide mode, the tray icon (any desktop with
  StatusNotifierItem support: KDE, GNOME with the AppIndicator extension, XFCE, Cinnamon, ...), the
  SOCKS5 proxy, live per-adapter speeds and autostart (XDG autostart entry) all work. The TUN and
  its routes are set up over netlink, flows are pinned with `SO_BINDTOIFINDEX`, and adapters are
  detected from sysfs and the routing tables. Needs root or `CAP_NET_ADMIN` (`setcap`).
- **Nix flake** with a package and a NixOS module: `programs.mergify-net.enable` installs a capability
  wrapper so the tray runs without root, and `programs.mergify-net.autostart` starts it in every
  graphical session.

### Changed
- Clicking the tray icon no longer turns merging on or off (it was easy to hit by accident). Open the
  menu (right-click) and choose *Pause merging* / *Start merging*.
- The source is split into packages: `cmd/mergify-net` (the command) and `internal/...` (engine, nics,
  tun, tray, platform, state, icon, logx). Build with `go build ./cmd/mergify-net`.

## [0.1.1] - 2026-10-14

### Added
- **Speeds on hover.** Hovering the tray icon shows each adapter's download/upload speed and a Total
  line. The numbers come from Windows' own adapter counters, so every app counts, including apps that
  bypass mergify-net (qBittorrent binds to adapters directly), and they show while paused too.
- **Real speeds in the menu.** The right-click menu shows each adapter's speed, how many connections
  mergify-net put on it, and whether it has IPv6.
- `mergify-net status` shows each adapter's total traffic ("link") next to the part mergify-net relayed.
- `-mtu` flag to change the virtual adapter's MTU (default 9000).
- `-pprof` flag to serve Go's profiler on a localhost address, for diagnosing CPU use.
- README: screenshots, features and how to use.

### Changed
- **About 3x less CPU.** The virtual adapter now runs at MTU 9000 instead of 1500, so about 5x fewer
  packets go through the userland network stack. Measured on the same machine and links: 0.54 → 0.18
  CPU cores per 100 Mbit/s.
- Tray wording: **Merging** / **Paused**, and *Pause merging* / *Start merging* in the menu (was ON/OFF
  and *Turn on* / *Turn off*).
- Idle UDP sessions end after 30 seconds instead of 60, which means fewer sockets and less memory with
  torrent clients.
- Quieter log: torrent traffic no longer writes a warning for every dropped packet.

### Fixed
- **Tray icon missing after sign-in.** With "start at sign-in" on, the tray could start before the
  taskbar existed and stay invisible and inactive. It now waits for the taskbar, and the sign-in task
  retries if the tray fails to start. If you enabled autostart in 0.1.0, install 0.1.1 with the option
  ticked to update it.
- UDP datagrams larger than 1500 bytes going through the virtual adapter were silently dropped.
- Tooltip cut off after "Ethernet 4 ↓": Windows 11 shows only 64 characters of a tray tooltip, so the
  text now fits that limit.
- A garbled "…" appeared when long tooltip text was shortened.
- Errors from the tray library were not written to the log in the windowless build.

## [0.1.0] - 2026-10-13

First release.

### Added
- System-wide per-connection balancing: every new TCP and UDP connection, IPv4 and IPv6, is pinned to
  one adapter, so parallel connections use Wi-Fi, Ethernet and phone/USB tethers at the same time.
- Tray app: left-click turns it on or off, right-click shows per-adapter speeds, optional start at
  sign-in without a UAC prompt.
- Automatic adapter detection: real hardware with a default gateway is used, while VPN and virtual adapters
  (Tailscale, WireGuard, Hyper-V, WSL) are skipped; `mergify-net list` shows why.
- Per-adapter internet checks: a link without internet gets no connections until it recovers.
- Connections are spread by each adapter's measured speed, separately for IPv4 and IPv6.
- IPv6 policy (`-ipv6 auto|on|off`): when only some adapters have IPv6, apps are steered to IPv4 so
  every adapter stays in use.
- Crash-safe: the virtual adapter and its routes disappear with the process.
- Command line: `on`, `off`, `quit`, `status`, `list`, `autostart`, `cleanup`, plus a SOCKS5 mode
  that needs no admin.
- Installer and portable zip.
