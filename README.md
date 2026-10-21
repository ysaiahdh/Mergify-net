<p align="center">
  <a href="README.md"><img src="https://img.shields.io/badge/README-English-blue?style=flat-square" alt="Read this README in English"></a>
  <a href="README.fr.md"><img src="https://img.shields.io/badge/README-Fran%C3%A7ais-lightgrey?style=flat-square" alt="Lire ce README en français"></a>
</p>

<p align="center">
  <img src="docs/icon.png" width="128" alt="mergify-net icon">
</p>

<h1 align="center">Mergify-Net</h1>

<p align="center">
  <strong>Merge every network you have — on desktop and on Android.</strong><br>
  Combine Wi-Fi, Ethernet and phone hotspots on Windows, macOS and Linux; on Android, aggregate Wi-Fi
  and mobile data (4G/5G) through a local VPN, with no root required.
</p>

<p align="center">
  <a href="https://github.com/ysaiahdh/Mergify-net/releases/latest">Download</a> ·
  <a href="#install">Install</a> ·
  <a href="#android">Android</a> ·
  <a href="#performance">Performance</a> ·
  <a href="#add-a-second-connection">Add a second connection</a> ·
  <a href="#troubleshooting">Troubleshooting</a> ·
  <a href="docs/DEVELOPING.md">How it works</a>
</p>

<p align="center">
  <a href="https://github.com/ysaiahdh/Mergify-net/actions/workflows/ci.yml"><img src="https://github.com/ysaiahdh/Mergify-net/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/ysaiahdh/Mergify-net/releases"><img src="https://img.shields.io/github/v/release/ysaiahdh/Mergify-net" alt="Latest release"></a>
  <a href="https://github.com/ysaiahdh/Mergify-net/releases"><img src="https://img.shields.io/github/downloads/ysaiahdh/Mergify-net/total" alt="Downloads"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/ysaiahdh/Mergify-net" alt="MIT license"></a>
  <img src="https://img.shields.io/badge/platforms-Windows%20%7C%20macOS%20%7C%20Linux%20%7C%20Android-blue" alt="Windows, macOS, Linux and Android">
</p>

<p align="center">
  <img src="docs/demo.gif" width="600" alt="Speed test on a Mac with Wi-Fi and an Android phone: the mergify-net menu shows both connections carrying traffic, and the download reaches about 326 Mbit/s">
  <br>
  <sub>A Mac with Wi-Fi + an Android phone. The menu shows both connections working at once. <a href="docs/demo.mp4">Watch the full 45-second video.</a></sub>
</p>

Mergify-Net uses all your network adapters together. It works on **Windows, macOS and Linux**, and on
**Android** (10+, no root) it aggregates Wi-Fi and mobile data. It is free.
There is no account, no server and no VPN.

A real test with Wi-Fi + an Android USB tether + an iPhone hotspot, 9 downloads at once:
**232-303 Mbit/s**. The fastest connection alone gave about 121 Mbit/s.

| Windows: hover the icon | Windows: the menu | macOS: the menu bar |
|---|---|---|
| ![Tooltip: Merging, speed of each adapter and the total](docs/tray-tooltip.png) | ![Menu: total, each adapter's speed and flows, IPv6 option, Pause merging, Quit](docs/tray-menu.png) | ![macOS menu: total 399 Mbit/s, en0 and feth0 each with their flows, Pause merging, Quit](docs/tray-menu-macos.png) |

## How it helps

mergify-net gives every new connection to one of your links. When an app opens many connections, they
spread over all your links.

- **Good for:** Steam and game downloads, browsers, system updates, cloud uploads (Google Drive),
  download managers like IDM, torrents.
- **Not for:** one single connection. One connection always uses one link, so one big file from one
  connection does not get faster. Browsers can split a download into several connections; see the tip
  under [Use it](#use-it).
- **Works for every app**, TCP and UDP, IPv4 and IPv6. You do not set up a proxy.
- **Smart:** faster links get more connections. A link with no internet gets none until it works again.
- **Safe:** if mergify-net crashes, your internet keeps working.
- **Light:** about 0.18 CPU cores per 100 Mbit/s.

## Install

**macOS and Linux**: paste this into a terminal.

```
curl -fsSL https://raw.githubusercontent.com/ysaiahdh/Mergify-net/main/install.sh | sh
```

**Windows**: paste this into PowerShell.

```
irm https://raw.githubusercontent.com/ysaiahdh/Mergify-net/main/install.ps1 | iex
```

That's it: mergify-net is installed and its icon appears in the menu bar or tray. On Linux the script
uses your package manager (apt, dnf, zypper, apk or pacman) and on macOS Homebrew when you have it, so
mergify-net updates with the rest of your system. To start it at sign-in, tick *Start at sign-in* in its menu.

<details>
<summary><strong>Rather type the commands yourself?</strong> One block per system, each ready to paste.</summary>
<br>

**Homebrew** (macOS):

```
brew install ysaiahdh/tap/mergify-net && sudo "$(brew --prefix)/bin/mergify-net" install && mergify-net tray
```

**macOS without Homebrew**:

```
curl -fsSL https://github.com/ysaiahdh/Mergify-net/releases/latest/download/mergify-net-macos-universal.tar.gz | tar -xz -C /tmp mergify-net && sudo /tmp/mergify-net install && mergify-net tray
```

**Debian, Ubuntu, Linux Mint, Pop!_OS, Raspberry Pi OS** (apt):

```
curl -fsSL https://ysaiahdh.github.io/Mergify-net/apt/key.gpg | sudo tee /usr/share/keyrings/mergify-net.gpg >/dev/null
echo "deb [signed-by=/usr/share/keyrings/mergify-net.gpg] https://ysaiahdh.github.io/Mergify-net/apt stable main" | sudo tee /etc/apt/sources.list.d/mergify-net.list
sudo apt update && sudo apt install -y mergify-net
```

**Fedora, RHEL, Rocky, Alma** (dnf):

```
sudo curl -fsSL -o /etc/yum.repos.d/mergify-net.repo https://ysaiahdh.github.io/Mergify-net/rpm/mergify-net.repo && sudo dnf install -y mergify-net
```

**openSUSE** (zypper):

```
sudo zypper addrepo https://ysaiahdh.github.io/Mergify-net/rpm/mergify-net.repo && sudo zypper --gpg-auto-import-keys install -y mergify-net
```

**Arch, Manjaro, EndeavourOS** (pacman):

```
curl -fsSL https://ysaiahdh.github.io/Mergify-net/apt/key.asc | sudo pacman-key --add - && sudo pacman-key --lsign-key 0168C7AB77721656904811AE904F61E7F4F1C2A1
printf '\n[mergify-net]\nSigLevel = Required\nServer = https://ysaiahdh.github.io/Mergify-net/arch/$arch\n' | sudo tee -a /etc/pacman.conf
sudo pacman -Sy --noconfirm mergify-net
```

**Alpine** (apk):

```
sudo wget -O /etc/apk/keys/mergify-net.rsa.pub https://ysaiahdh.github.io/Mergify-net/apk/mergify-net.rsa.pub
echo https://ysaiahdh.github.io/Mergify-net/apk | sudo tee -a /etc/apk/repositories && sudo apk add mergify-net
```

**Homebrew** (Linux):

```
brew install ysaiahdh/tap/mergify-net && sudo setcap cap_net_admin,cap_net_raw+ep "$(readlink -f "$(brew --prefix)/bin/mergify-net")"
```

**Any other Linux** (any CPU):

```
curl -fsSL https://github.com/ysaiahdh/Mergify-net/releases/latest/download/mergify-net-linux-$(uname -m).tar.gz | tar -xz -C /tmp mergify-net && sudo install -m 755 /tmp/mergify-net /usr/local/bin/ && sudo setcap cap_net_admin,cap_net_raw+ep /usr/local/bin/mergify-net
```

**Windows by hand**: download [mergify-net-setup.exe](https://github.com/ysaiahdh/Mergify-net/releases/latest/download/mergify-net-setup.exe)
and run it. Windows may warn because the app is not signed yet: click *More info*, then *Run anyway*.
There is also a [portable zip](https://github.com/ysaiahdh/Mergify-net/releases/latest/download/mergify-net-portable.zip).

Then start it with `mergify-net tray` (Linux, macOS) or from the Start menu (Windows).
</details>

<details>
<summary><strong>Good to know</strong></summary>
<br>

- **Which CPUs:** x86-64, ARM64 and Apple Silicon, ARMv7, ARMv6 (Raspberry Pi), x86 32-bit, RISC-V,
  PowerPC (ppc64le), s390x and LoongArch.
- **No password prompts:** the Linux packages give mergify-net the network rights it needs (`setcap`), and on
  macOS `mergify-net install` adds one rule so that only `mergify-net tray` can run as root without a password.
- **Linux tray icon:** needs a desktop with StatusNotifierItem support: KDE, XFCE, Cinnamon, LXQt, Budgie,
  or GNOME with the *AppIndicator and KStatusNotifierItem Support* extension (Ubuntu already has it).
- **Firewall:** if it blocks unknown traffic, trust the `mergify-net` interface
  (for example `firewall-cmd --zone=trusted --add-interface=mergify-net`).
- **Signed repositories:** apt, dnf, zypper and pacman use the key in
  [`packaging/apt-signing-key.asc`](packaging/apt-signing-key.asc), fingerprint
  `0168 C7AB 7772 1656 9048 11AE 904F 61E7 F4F1 C2A1`.
</details>

## Android

On a phone, one connection at a time is the norm. Mergify-Net for Android runs the same Go engine —
compiled to a native library with [gomobile](https://pkg.go.dev/golang.org/x/mobile) — inside a local
VPN (**VpnService**). Every outgoing socket is protected and pinned to the right network, so an app
opening several connections uses **Wi-Fi and mobile data (4G/5G) at once**.

<table align="center">
  <tr>
    <td align="center" width="33%">
      <a href="docs/screenshots/android-dashboard-idle.jpg"><img src="docs/screenshots/android-dashboard-idle.jpg" width="240" alt="Android dashboard with the tunnel off: Activer le VPN button and data usage card (session, cumulative, Wi-Fi / mobile split)"></a>
    </td>
    <td align="center" width="33%">
      <a href="docs/screenshots/android-dashboard-active.jpg"><img src="docs/screenshots/android-dashboard-active.jpg" width="240" alt="Android dashboard while merging: green Tunnel actif status, VPN actif button, wifi and cell cards with flows, throughput, latency and IP addresses"></a>
    </td>
    <td align="center" width="33%">
      <a href="docs/screenshots/android-settings.jpg"><img src="docs/screenshots/android-settings.jpg" width="240" alt="Android settings screen: mobile data budget from 100 Mo to 20 Go with an Illimité switch, and the force multi-flow switch"></a>
    </td>
  </tr>
  <tr>
    <td align="center"><sub><b>Dashboard</b> — tunnel off, session and cumulative data usage, split between Wi-Fi and mobile.</sub></td>
    <td align="center"><sub><b>Merging</b> — Wi-Fi + 4G/5G, flows and latency per link, IPv4/IPv6 addresses.</sub></td>
    <td align="center"><sub><b>Settings</b> — mobile data budget (100 Mo → 20 Go or unlimited) and multi-flow.</sub></td>
  </tr>
</table>

<p align="center">
  <a href="docs/screenshots/android-notification.jpg"><img src="docs/screenshots/android-notification.jpg" width="460" alt="Ongoing Android notification: Mergify-Net connected over Wi-Fi, 85 ko/s down and 15 ko/s up, 20 ms ping, with a Disconnect action"></a>
  <br>
  <sub><b>The ongoing notification</b> shows the link in use, live throughput and ping, and can disconnect the tunnel.</sub>
</p>

- **No root.** The VPN lives entirely in the app and no traffic goes through a server; Android shows
  the usual VPN key icon while it runs.
- **Smart routing.** The lowest-latency link is the primary and carries every new flow; the other one
  only takes traffic when the primary is saturated or losing packets, so downloads and speed tests
  get the best of both without being dragged down by the weaker link.
- **Both links stay yours.** Nothing is hijacked system-wide: only traffic entering the tunnel is
  balanced.
- **The ongoing notification.** The foreground service keeps a notification with the link in use,
  live throughput and ping, and a *Disconnect* action.
- **Data usage.** The dashboard reports the volume carried through the tunnel this session and
  cumulatively, split between Wi-Fi and mobile data (4G/5G), from the engine's per-interface byte
  counters.
- **Settings.** The gear at the top of the dashboard opens the settings screen (*Paramètres* in the
  app's French UI): a cellular data budget
  (slider from 100 Mo to 20 Go, or *Illimité*) persisted in `SharedPreferences` — the stats loop
  takes cellular out of the balance (`UpdateNetworks(wifiHandle, 0)`) and notifies the user as
  soon as the cumulative mobile volume reaches it — plus a switch to force multi-flow, which makes
  the scheduler alternate every new connection across both links instead of the fastest one.

To build the app, see the full procedure: [Build the Android app](#build-the-android-app)
(gomobile → `mergify.aar`, then Gradle → APK, and installing with `adb`).

## Performance

Measured on the Galaxy S25 with the tunnel active:

<p align="center">
  <a href="docs/screenshots/performance-speedtest.jpg"><img src="docs/screenshots/performance-speedtest.jpg" width="300" alt="Speedtest result with the tunnel active: 536 Mbit/s download and 947 Mbit/s upload"></a>
  <br>
  <sub><b>Speedtest, tunnel active</b> — 536 Mbit/s down and 947 Mbit/s up over Wi-Fi + 4G/5G.</sub>
</p>

The score follows your links: it is what they can carry together at that moment. Speedtest opens several
connections, so it uses both of them; a single download still stays on one link, as explained in
[How it helps](#how-it-helps).

## Build the Android app

The Android version is made of two artifacts, built in this order:

1. **`android/app/libs/mergify.aar`** — the Go engine (the same one as on desktop) compiled into a
   native library for Android with gomobile;
2. **the APK** of the Kotlin app (`VpnService` + Jetpack Compose UI), produced by Gradle in the
   `android/` folder, which bundles the `.aar`.

> The `.aar` is committed to the repository: regenerate it after **every** change to the Go engine
> (`internal/…`, `pkg/mobile/…`), otherwise the APK keeps bundling the old version.

### 1. Prerequisites

| Tool | Version | Role |
|---|---|---|
| [Go](https://go.dev/dl/) | **1.24+** (`tool` directive in `go.mod`; the repository currently targets Go 1.27) | building the engine and the tools |
| [Android Studio](https://developer.android.com/studio) | recent | Android SDK, bundled JDK (`jbr`), `adb` |
| `ANDROID_HOME` | pointing at the SDK (`C:\android-sdk`, `~/Android/Sdk`…) | detected by Gradle **and** gomobile |
| `gomobile` / `gobind` | `golang.org/x/mobile` (latest) | Go → Java/Kotlin class bindings |

Install the Go tools (once), at the repository root:

```bash
# registers gobind as a module tool (the `tool` directive in go.mod)
go get -tool golang.org/x/mobile/cmd/gobind
go mod tidy

# the gomobile and gobind drivers on the PATH (the next commands call them)
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
```

> **Windows**: `gomobile bind` calls `javac`. Add Android Studio's JDK to the `PATH`
> (`%ProgramFiles%\Android\Android Studio\jbr\bin`) or export `JAVA_HOME` to that JDK before running
> the command. On the Gradle side, use a **JDK 17 to 21** (*Settings → Build Tools → Gradle → Gradle
> JDK*): the newest JBRs (24+) are too young for the project's Gradle 8.9.

### 2. Generate the Go library (`mergify.aar`)

At the repository root (macOS/Linux):

```bash
gomobile bind \
  -target=android/arm64 \
  -androidapi 29 \
  -javapkg mergifynet \
  -o android/app/libs/mergify.aar \
  ./pkg/mobile
```

On Windows PowerShell, the same command on one line:

```powershell
gomobile bind -target=android/arm64 -androidapi 29 -javapkg mergifynet -o android\app\libs\mergify.aar .\pkg\mobile
```

| Option | Meaning |
|---|---|
| `-target=android/arm64` | Target ABI: the S25 is arm64-v8a. Without an ABI (`-target=android`), the `.aar` becomes multi-ABI (arm, arm64, amd64…). |
| `-androidapi 29` | Minimum Android API, matching `minSdk = 29` in Gradle. |
| `-javapkg mergifynet` | Generated Java package. gobind appends the Go package name to it, so the classes land in **`mergifynet.mobile`** (`mergifynet.mobile.Mobile`), which is what the Kotlin imports use. |
| `-o …/mergify.aar` | Output file; Gradle automatically includes every `.aar` present in `android/app/libs/`. |
| `./pkg/mobile` | Go package exposed to Android; only gobind-compatible types are exported there (rule 2 of [`AGENTS.md`](AGENTS.md)). |

### 3. Build the Android APK

```bash
cd android
./gradlew assembleDebug      # Windows: .\gradlew.bat assembleDebug
```

- The APK is produced in `android/app/build/outputs/apk/debug/app-debug.apk` (debug signature, enough
  for a test device).
- The Gradle 8.9 wrapper is committed (`gradlew`, `gradle/wrapper/`): no global Gradle install is
  needed.
- `android/gradle.properties` contains `android.overridePathCheck=true`, required because the
  repository path contains accented characters (AGP refuses that kind of path without this setting).
- First run: Gradle downloads its dependencies (a few minutes).

### 4. Install and launch on the device (Galaxy S25)

1. On the S25: *Settings → About phone → Software information* — tap *Build number* seven times to
   enable **developer options**, then turn on **USB debugging**.
2. Plug the phone in, accept the debugging key, then check that the device is listed:

   ```bash
   adb devices        # the S25 must appear in the "device" state (e.g. R58M1234ABC    device)
   ```

3. Install (or update) the app:

   ```bash
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```

4. First launch: the system **VpnService** consent dialog appears; once accepted, the VPN key appears
   in the status bar. The app runs as a foreground service
   (`foregroundServiceType="systemExempted"` — the Android SDK defines **no** `vpn` type).
5. Follow the Go engine start-up (Wi-Fi/cellular network handles, latency probes, any `bindSocket`
   errors):

   ```bash
   adb logcat -s MergifyNet
   ```

The app ID is `com.mergify.net`; to remove it from the device:
`adb uninstall com.mergify.net`.

## Uninstall

**macOS and Linux**: paste this into a terminal. It stops mergify-net and removes it the way it was
installed (Homebrew, apt, dnf, zypper, apk, pacman or the download), with its repository and key.

```
curl -fsSL https://raw.githubusercontent.com/ysaiahdh/Mergify-net/main/install.sh | sh -s -- uninstall
```

**Windows**: paste this into PowerShell, or use *Settings > Apps > Installed apps > mergify-net > Uninstall*.

```
& "$env:ProgramFiles\mergify-net\mergify-net.exe" quit; Start-Process "$env:ProgramFiles\mergify-net\unins000.exe" '/SILENT' -Verb RunAs -Wait
```

Your network goes back to normal as soon as mergify-net stops: it removes its virtual adapter and routes
when it quits, and even after a crash.

<details>
<summary><strong>Rather type the commands yourself?</strong> One block per way you installed it.</summary>
<br>

Each block first stops the tray and turns off start at sign-in, then removes mergify-net.

**Homebrew** (macOS):

```
mergify-net quit; mergify-net autostart off; sudo mergify-net uninstall; brew uninstall mergify-net && brew untap ysaiahdh/tap
```

**macOS without Homebrew**:

```
mergify-net quit; mergify-net autostart off; sudo mergify-net uninstall
```

**Debian, Ubuntu, Linux Mint, Pop!_OS, Raspberry Pi OS** (apt):

```
mergify-net quit; mergify-net autostart off; sudo apt remove -y mergify-net && sudo rm -f /etc/apt/sources.list.d/mergify-net.list /usr/share/keyrings/mergify-net.gpg
```

**Fedora, RHEL, Rocky, Alma** (dnf):

```
mergify-net quit; mergify-net autostart off; sudo dnf remove -y mergify-net && sudo rm -f /etc/yum.repos.d/mergify-net.repo
```

**openSUSE** (zypper):

```
mergify-net quit; mergify-net autostart off; sudo zypper remove -y mergify-net && sudo zypper removerepo mergify-net
```

**Arch, Manjaro, EndeavourOS** (pacman):

```
mergify-net quit; mergify-net autostart off; sudo pacman -R --noconfirm mergify-net && sudo sed -i '/^\[mergify-net\]$/,/^Server = .*mergify-net/d' /etc/pacman.conf && sudo pacman-key --delete 0168C7AB77721656904811AE904F61E7F4F1C2A1
```

**Alpine** (apk):

```
mergify-net quit; mergify-net autostart off; sudo apk del mergify-net && sudo sed -i '\#ysaiahdh.github.io/Mergify-net/apk#d' /etc/apk/repositories && sudo rm -f /etc/apk/keys/mergify-net.rsa.pub
```

**Homebrew** (Linux):

```
mergify-net quit; mergify-net autostart off; brew uninstall mergify-net && brew untap ysaiahdh/tap
```

**Any other Linux** (the download):

```
mergify-net quit; mergify-net autostart off; sudo rm -f /usr/local/bin/mergify-net
```

**Windows portable zip**: run `mergify-net.exe autostart off` in its folder, quit the tray, then delete the folder.

**NixOS**: remove `programs.mergify-net` (and the `mergify-net` input) from your configuration and rebuild.
</details>

### NixOS

```nix
# flake.nix
inputs.mergify-net.url = "github:ysaiahdh/Mergify-net";

# configuration.nix (with `inputs` passed via specialArgs)
imports = [ inputs.mergify-net.nixosModules.default ];
programs.mergify-net = {
  enable = true;
  autostart = true;   # optional: start the tray in every graphical session
};
```

To try it without installing: `sudo nix run github:ysaiahdh/Mergify-net -- run`.

## Add a second connection

mergify-net needs **two or more** real connections. Use Wi-Fi plus one of these: a phone (USB tethering or
hotspot), an Ethernet cable, or a second Wi-Fi. Plug it in and mergify-net adds it within a few seconds.
When you unplug it, its connections move to the others.

**Windows and Linux:** plug the phone in and turn on *USB tethering* (Android) or *Personal Hotspot*
(iPhone). That is all.

**Mac with an iPhone** (nothing extra to install)
1. Plug the iPhone in with a cable. Tap *Trust* on the iPhone if it asks.
2. On the iPhone, open *Settings > Personal Hotspot* and turn on *Allow Others to Join*.
3. An **iPhone USB** network appears on the Mac. mergify-net adds it in a few seconds.

**Mac with an Android phone** (needs the app **TetherKit**)

macOS cannot use Android USB tethering by itself. The app **TetherKit** adds that support.
1. Install and open TetherKit.
2. Plug the phone in and turn on *USB tethering* in the phone's settings.
3. Click **Apply** in TetherKit. mergify-net adds the new network in a few seconds.

> **Start mergify-net first, then connect the Android phone.** macOS drops the TetherKit connection each
> time mergify-net starts or quits. Pausing and resuming is safe: mergify-net keeps its virtual adapter while
> paused. If the phone link disappears, click **Apply** in TetherKit again. The iPhone is not affected.

A phone uses its mobile data, so part of your traffic goes through it while mergify-net runs. Quit
mergify-net when you do not want that.

## Use it

1. Start mergify-net. A tray icon appears (menu bar on Mac).
2. The icon is **green** when it is merging and **grey** when it is paused.
3. **It pauses by itself** when fewer than two connections have internet, because there is nothing to
   merge, and the menu says *Paused: one connection*. It **starts again by itself** when a second
   connection works. If you pause or start it yourself, that choice wins: it will not start again after
   you paused it. Untick *Pause when there is only one connection* in the menu to switch this off, or run
   `mergify-net tray -auto-pause=false`.
4. **Hover** the icon to see the speed of each connection and the total.
5. **Right-click** the icon (on Mac, click it) to open the menu. It shows each connection's speed, how
   many connections mergify-net gave it, and the buttons *Pause merging* / *Start merging* and *Quit*.
6. To start it at sign-in: tick *Start at sign-in* in the menu (or the box in the Windows installer, or run
   `mergify-net autostart on`). On a Mac this needs `sudo mergify-net install` once.

Tips:
- Torrent clients already use every connection. Leave qBittorrent's network interface on *Any interface*.
- **Faster browser downloads.** A browser downloads a file over one connection, so it uses one link. Chrome,
  Edge, Brave and other Chromium browsers can split it over several connections instead:
  1. Open `chrome://flags/#enable-parallel-downloading` (Edge: `edge://flags/#enable-parallel-downloading`,
     Brave: `brave://flags/#enable-parallel-downloading`).
  2. Set *Parallel downloading* to *Enabled* and click *Relaunch*.

  Firefox has no such setting; for big files use a download manager, for example `aria2c -x 8 -s 8 <link>`.
  Splitting only helps when the server allows downloading parts of a file, which most large download
  sites do.
- Leave *Use all adapters (IPv6 only if every adapter has it)* ticked. Untick it only if you must reach a
  site that only has IPv6.
- `mergify-net list` shows which connections are used and why others are skipped. `mergify-net status`
  shows live numbers.

## Troubleshooting

<details>
<summary><strong>A second connection does not make things faster</strong></summary>
<br>

You only gain speed when each connection reaches the internet by its own path. Wi-Fi from your home
router plus a phone on mobile data works. Wi-Fi and Ethernet plugged into the **same router** do not: they
share one internet line, so the total stays the same.

Also remember that one connection always uses one link. Many parallel connections (Steam, browsers,
download managers) are what speed up. Run `mergify-net status` to see how many connections each link carries.
</details>

<details>
<summary><strong>A connection does not show up</strong></summary>
<br>

Run `mergify-net list`. It shows which connections are used and why the others are skipped, for example
"no default gateway" (the link has no internet yet).

- **iPhone on a Mac:** plug it in, tap *Trust*, and turn on *Settings > Personal Hotspot > Allow Others to
  Join*. If the **iPhone USB** network in *System Settings > Network* stays inactive, turn off its
  **Disable unless needed** switch.
- **Android on a Mac:** install **TetherKit** and click **Apply** (see above).
- **Phone on Windows:** if the phone does not appear in *Network Settings*, install your phone maker's USB
  driver.
</details>

<details>
<summary><strong>The Android connection disappears on a Mac</strong></summary>
<br>

macOS drops the TetherKit connection each time mergify-net starts or quits (pausing and resuming is safe).
Start mergify-net first, then connect the phone. If it vanishes, click **Apply** in TetherKit again.
</details>

<details>
<summary><strong>Windows uses only one network</strong></summary>
<br>

Windows can switch Wi-Fi off when Ethernet is plugged in. Open the Group Policy Editor (`gpedit.msc`),
go to *Computer Configuration > Administrative Templates > Network > Windows Connection Manager*, and set
**Minimize the number of simultaneous connections to the Internet or a Windows domain** to **Disabled**.
</details>

<details>
<summary><strong>macOS says it cannot open mergify-net, or Windows says "protected your PC"</strong></summary>
<br>

The apps are not code-signed yet. On macOS run `xattr -d com.apple.quarantine mergify-net`. On Windows click
*More info*, then *Run anyway*.
</details>

<details>
<summary><strong>The menu says "Paused: one connection"</strong></summary>
<br>

mergify-net only has something to do with two or more connections that have internet, so it waits. Plug
in or connect the second one (see *Add a second connection*) and it starts by itself after a few seconds.
To merge anyway, choose *Start merging* (it then stays on until a second connection has come and gone), or
untick *Pause when there is only one connection*.

Switching merging on or off cuts the connections that are open at that moment, which is why it waits 10
seconds with nothing moving before pausing (2 minutes if a download or upload is still going) and
3 seconds before resuming,
and never switches by itself more than once every 30 seconds.
</details>

<details>
<summary><strong>I cannot find the tray icon</strong></summary>
<br>

On Windows it may be behind the `^` arrow next to the clock. On macOS it is in the menu bar. On Linux the
desktop needs StatusNotifierItem support (KDE, XFCE, Cinnamon, LXQt, Budgie, or GNOME with the *AppIndicator
and KStatusNotifierItem Support* extension).
</details>

<details>
<summary><strong>A site asks me to log in again</strong></summary>
<br>

mergify-net may use a different IP for each connection, and some sites tie your login to one IP. That is a
limit of splitting connections over several links.
</details>

## Commands

```
mergify-net tray            tray icon (the normal way to run it)
mergify-net on | off        start / pause merging in the running tray
mergify-net quit            pause and close the tray
mergify-net status | list   live numbers / which connections are used
mergify-net autostart on|off|status
mergify-net socks           plain SOCKS5 proxy on 127.0.0.1:1080 (no admin, per app)
mergify-net cleanup         remove leftover routes after a crash
mergify-net install         macOS only: `sudo mergify-net install` (see above)
```

More flags and details are in [docs/DEVELOPING.md](docs/DEVELOPING.md).

## Good to know

- Needs admin (Windows), `CAP_NET_ADMIN` (Linux) or `sudo mergify-net install` once (macOS).
- Some sites tie your login to one IP. mergify-net may use a different IP for each connection, so such a
  site can ask you to log in again.
- If your connections differ in IPv6 support, sites that only have IPv6 are not reachable while the box
  above is ticked.

## More

- What changed in each version: [CHANGELOG.md](CHANGELOG.md)
- How it works, building from source, tests: [docs/DEVELOPING.md](docs/DEVELOPING.md)
- Want to help? [CONTRIBUTING.md](CONTRIBUTING.md). Found a security problem? [SECURITY.md](SECURITY.md)
- License: MIT, see `LICENSE`. Third-party parts: `THIRD-PARTY-NOTICES.md`.

## Support

mergify-net is free. If it saves you time, you can [buy me a coffee](https://buymeacoffee.com/ysaiahdh) or [donate with PayPal](https://paypal.me/champdev). Thank you!

Building a college project? Host it on [DormHost](https://dormhost.dev/?utm_source=mergify-net&utm_medium=readme-support),
also by me: Node.js, Python or Docker with a database, 30 days free.
