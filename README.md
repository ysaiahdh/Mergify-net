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
  <a href="https://github.com/rohit267/mergify-net/releases/latest">Download</a> ·
  <a href="#install">Install</a> ·
  <a href="#android">Android</a> ·
  <a href="#add-a-second-connection">Add a second connection</a> ·
  <a href="#troubleshooting">Troubleshooting</a> ·
  <a href="docs/DEVELOPING.md">How it works</a>
</p>

<p align="center">
  <a href="https://github.com/rohit267/mergify-net/actions/workflows/ci.yml"><img src="https://github.com/rohit267/mergify-net/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/rohit267/mergify-net/releases"><img src="https://img.shields.io/github/v/release/rohit267/mergify-net" alt="Latest release"></a>
  <a href="https://github.com/rohit267/mergify-net/releases"><img src="https://img.shields.io/github/downloads/rohit267/mergify-net/total" alt="Downloads"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/rohit267/mergify-net" alt="MIT license"></a>
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
curl -fsSL https://raw.githubusercontent.com/rohit267/mergify-net/main/install.sh | sh
```

**Windows**: paste this into PowerShell.

```
irm https://raw.githubusercontent.com/rohit267/mergify-net/main/install.ps1 | iex
```

That's it: mergify-net is installed and its icon appears in the menu bar or tray. On Linux the script
uses your package manager (apt, dnf, zypper, apk or pacman) and on macOS Homebrew when you have it, so
mergify-net updates with the rest of your system. To start it at sign-in, tick *Start at sign-in* in its menu.

<details>
<summary><strong>Rather type the commands yourself?</strong> One block per system, each ready to paste.</summary>
<br>

**Homebrew** (macOS):

```
brew install rohit267/tap/mergify-net && sudo "$(brew --prefix)/bin/mergify-net" install && mergify-net tray
```

**macOS without Homebrew**:

```
curl -fsSL https://github.com/rohit267/mergify-net/releases/latest/download/mergify-net-macos-universal.tar.gz | tar -xz -C /tmp mergify-net && sudo /tmp/mergify-net install && mergify-net tray
```

**Debian, Ubuntu, Linux Mint, Pop!_OS, Raspberry Pi OS** (apt):

```
curl -fsSL https://rohit267.github.io/mergify-net/apt/key.gpg | sudo tee /usr/share/keyrings/mergify-net.gpg >/dev/null
echo "deb [signed-by=/usr/share/keyrings/mergify-net.gpg] https://rohit267.github.io/mergify-net/apt stable main" | sudo tee /etc/apt/sources.list.d/mergify-net.list
sudo apt update && sudo apt install -y mergify-net
```

**Fedora, RHEL, Rocky, Alma** (dnf):

```
sudo curl -fsSL -o /etc/yum.repos.d/mergify-net.repo https://rohit267.github.io/mergify-net/rpm/mergify-net.repo && sudo dnf install -y mergify-net
```

**openSUSE** (zypper):

```
sudo zypper addrepo https://rohit267.github.io/mergify-net/rpm/mergify-net.repo && sudo zypper --gpg-auto-import-keys install -y mergify-net
```

**Arch, Manjaro, EndeavourOS** (pacman):

```
curl -fsSL https://rohit267.github.io/mergify-net/apt/key.asc | sudo pacman-key --add - && sudo pacman-key --lsign-key 0168C7AB77721656904811AE904F61E7F4F1C2A1
printf '\n[mergify-net]\nSigLevel = Required\nServer = https://rohit267.github.io/mergify-net/arch/$arch\n' | sudo tee -a /etc/pacman.conf
sudo pacman -Sy --noconfirm mergify-net
```

**Alpine** (apk):

```
sudo wget -O /etc/apk/keys/mergify-net.rsa.pub https://rohit267.github.io/mergify-net/apk/mergify-net.rsa.pub
echo https://rohit267.github.io/mergify-net/apk | sudo tee -a /etc/apk/repositories && sudo apk add mergify-net
```

**Homebrew** (Linux):

```
brew install rohit267/tap/mergify-net && sudo setcap cap_net_admin,cap_net_raw+ep "$(readlink -f "$(brew --prefix)/bin/mergify-net")"
```

**Any other Linux** (any CPU):

```
curl -fsSL https://github.com/rohit267/mergify-net/releases/latest/download/mergify-net-linux-$(uname -m).tar.gz | tar -xz -C /tmp mergify-net && sudo install -m 755 /tmp/mergify-net /usr/local/bin/ && sudo setcap cap_net_admin,cap_net_raw+ep /usr/local/bin/mergify-net
```

**Windows by hand**: download [mergify-net-setup.exe](https://github.com/rohit267/mergify-net/releases/latest/download/mergify-net-setup.exe)
and run it. Windows may warn because the app is not signed yet: click *More info*, then *Run anyway*.
There is also a [portable zip](https://github.com/rohit267/mergify-net/releases/latest/download/mergify-net-portable.zip).

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

- **No root.** The VPN lives entirely in the app and no traffic goes through a server; Android shows
  the usual VPN key icon while it runs.
- **Smart routing.** The lowest-latency link is the primary and carries every new flow; the other one
  only takes traffic when the primary is saturated or losing packets, so downloads and speed tests
  get the best of both without being dragged down by the weaker link.
- **Both links stay yours.** Nothing is hijacked system-wide: only traffic entering the tunnel is
  balanced.
- **Data usage.** The dashboard reports the volume carried through the tunnel this session and
  cumulatively, split between Wi-Fi and mobile data (4G/5G), from the engine's per-interface byte
  counters.
- **Settings.** The gear at the top of the dashboard opens *Paramètres*: a cellular data budget
  (slider from 100 Mo to 20 Go, or *Illimité*) persisted in `SharedPreferences` — the stats loop
  takes cellular out of the balance (`UpdateNetworks(wifiHandle, 0)`) and notifies the user as
  soon as the cumulative mobile volume reaches it — plus a switch to force multi-flow, which makes
  the scheduler alternate every new connection across both links instead of the fastest one.

Pour compiler l'app, voir la procédure complète : [Compiler la version mobile (Android)](#compiler-la-version-mobile-android)
(gomobile → `mergify.aar`, puis Gradle → APK, et installation via `adb`).

## Compiler la version mobile (Android)

La version Android se compose de deux artefacts, à compiler dans cet ordre :

1. **`android/app/libs/mergify.aar`** — le moteur Go (le même que sur desktop) compilé en
   bibliothèque native pour Android via gomobile ;
2. **l'APK** de l'application Kotlin (`VpnService` + interface Jetpack Compose), produite par Gradle
   dans le dossier `android/` et qui embarque le `.aar`.

> Le `.aar` est versionné dans le dépôt : à régénérer après **chaque** modification du moteur Go
> (`internal/…`, `pkg/mobile/…`), sinon l'APK continue d'embarquer l'ancienne version.

### 1. Prérequis

| Outil | Version | Rôle |
|---|---|---|
| [Go](https://go.dev/dl/) | **1.24+** (directive `tool` du `go.mod` ; le dépôt vise actuellement Go 1.27) | compilation du moteur et des outils |
| [Android Studio](https://developer.android.com/studio) | récent | SDK Android, JDK embarqué (`jbr`), `adb` |
| `ANDROID_HOME` | pointant vers le SDK (`C:\android-sdk`, `~/Android/Sdk`…) | détecté par Gradle **et** gomobile |
| `gomobile` / `gobind` | `golang.org/x/mobile` (dernière version) | binding Go → classes Java/Kotlin |

Installer les outils Go (une seule fois), à la racine du dépôt :

```bash
# enregistre gobind comme outil du module (directive `tool` du go.mod)
go get -tool golang.org/x/mobile/cmd/gobind
go mod tidy

# le pilote gomobile et gobind sur le PATH (ce sont eux que les commandes suivantes appellent)
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
```

> **Windows** : `gomobile bind` appelle `javac`. Ajoutez le JDK d'Android Studio au `PATH`
> (`%ProgramFiles%\Android\Android Studio\jbr\bin`) ou exportez `JAVA_HOME` vers ce JDK avant la
> commande. Côté Gradle, utilisez un **JDK 17 à 21** (*Settings → Build Tools → Gradle → Gradle JDK*) :
> les JBR les plus récents (24+) sont trop jeunes pour le Gradle 8.9 du projet.

### 2. Générer la bibliothèque Go (`mergify.aar`)

À la racine du dépôt (macOS/Linux) :

```bash
gomobile bind \
  -target=android/arm64 \
  -androidapi 29 \
  -javapkg mergifynet \
  -o android/app/libs/mergify.aar \
  ./pkg/mobile
```

Sous Windows PowerShell, la même commande sur une ligne :

```powershell
gomobile bind -target=android/arm64 -androidapi 29 -javapkg mergifynet -o android\app\libs\mergify.aar .\pkg\mobile
```

| Option | Signification |
|---|---|
| `-target=android/arm64` | ABI cible : le S25 est en arm64-v8a. Sans ABI (`-target=android`), le `.aar` devient multi-ABI (arm, arm64, amd64…). |
| `-androidapi 29` | API Android minimale, en cohérence avec `minSdk = 29` du Gradle. |
| `-javapkg mergifynet` | package Java généré. gobind y append le nom du package Go : les classes atterrissent dans **`mergifynet.mobile`** (`mergifynet.mobile.Mobile`), c'est ce que les imports Kotlin utilisent. |
| `-o …/mergify.aar` | fichier produit ; Gradle inclut automatiquement tout `.aar` présent dans `android/app/libs/`. |
| `./pkg/mobile` | package Go exposé à Android ; seuls des types compatibles gobind y sont exportés (règle 2 de [`AGENTS.md`](AGENTS.md)). |

### 3. Compiler l'APK Android

```bash
cd android
./gradlew assembleDebug      # Windows : .\gradlew.bat assembleDebug
```

- L'APK est produite dans `android/app/build/outputs/apk/debug/app-debug.apk` (signature de debug,
  suffisante pour un appareil de test).
- Le wrapper Gradle 8.9 est versionné (`gradlew`, `gradle/wrapper/`) : aucune installation Gradle
  globale n'est nécessaire.
- `android/gradle.properties` contient `android.overridePathCheck=true`, requis car le chemin du
  dépôt contient des accents (AGP refuse ce type de chemin sans ce réglage).
- Première exécution : Gradle télécharge ses dépendances (quelques minutes).

### 4. Installer et lancer sur l'appareil (Galaxy S25)

1. Sur le S25 : *Paramètres → À propos du téléphone → Informations sur le logiciel* — tapez 7 fois sur
   *Numéro de build* pour activer les **options pour les développeurs**, puis activez **Débogage USB**.
2. Branchez le téléphone, acceptez la clé de débogage, et vérifiez la présence de l'appareil :

   ```bash
   adb devices        # le S25 doit apparaître en état "device" (ex. RFCY410YYEA    device)
   ```

3. Installer (ou mettre à jour) l'app :

   ```bash
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```

4. Premier lancement : le dialogue de consentement système **VpnService** s'affiche ; une fois accepté,
   la clé VPN apparaît dans la barre d'état. L'app tourne en service au premier plan
   (`foregroundServiceType="systemExempted"` — le SDK Android ne définit **aucun** type `vpn`).
5. Suivre le démarrage du moteur Go (handles réseau Wi-Fi/cellulaire, sondes de latence, erreurs
   éventuelles de `bindSocket`) :

   ```bash
   adb logcat -s MergifyNet
   ```

L'identifiant de l'application est `com.mergify.net` ; pour la retirer de l'appareil :
`adb uninstall com.mergify.net`.

## Uninstall

**macOS and Linux**: paste this into a terminal. It stops mergify-net and removes it the way it was
installed (Homebrew, apt, dnf, zypper, apk, pacman or the download), with its repository and key.

```
curl -fsSL https://raw.githubusercontent.com/rohit267/mergify-net/main/install.sh | sh -s -- uninstall
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
mergify-net quit; mergify-net autostart off; sudo mergify-net uninstall; brew uninstall mergify-net && brew untap rohit267/tap
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
mergify-net quit; mergify-net autostart off; sudo apk del mergify-net && sudo sed -i '\#rohit267.github.io/mergify-net/apk#d' /etc/apk/repositories && sudo rm -f /etc/apk/keys/mergify-net.rsa.pub
```

**Homebrew** (Linux):

```
mergify-net quit; mergify-net autostart off; brew uninstall mergify-net && brew untap rohit267/tap
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
inputs.mergify-net.url = "github:rohit267/mergify-net";

# configuration.nix (with `inputs` passed via specialArgs)
imports = [ inputs.mergify-net.nixosModules.default ];
programs.mergify-net = {
  enable = true;
  autostart = true;   # optional: start the tray in every graphical session
};
```

To try it without installing: `sudo nix run github:rohit267/mergify-net -- run`.

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

mergify-net is free. If it saves you time, you can [buy me a coffee](https://buymeacoffee.com/rohit267) or [donate with PayPal](https://paypal.me/champdev). Thank you!

Building a college project? Host it on [DormHost](https://dormhost.dev/?utm_source=mergify-net&utm_medium=readme-support),
also by me: Node.js, Python or Docker with a database, 30 days free.
