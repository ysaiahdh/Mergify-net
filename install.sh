#!/bin/sh
# Installs mergify-net on macOS or Linux, the easiest way the machine allows:
#
#   curl -fsSL https://raw.githubusercontent.com/ysaiahdh/Mergify-net/main/install.sh | sh
#
# Linux: the system's package manager (apt, dnf, yum, zypper, apk, pacman) from mergify-net's signed
# repositories, so it updates with everything else; otherwise Homebrew; otherwise the release tarball.
# macOS: Homebrew if it is installed, otherwise the release tarball.
# Then it starts the tray. MERGIFY_NET_METHOD=tarball skips the package managers, MERGIFY_NET_NO_START=1
# leaves the tray off.
#
# Uninstall, however it was installed:
#
#   curl -fsSL https://raw.githubusercontent.com/ysaiahdh/Mergify-net/main/install.sh | sh -s -- uninstall
set -eu

REPO=https://github.com/ysaiahdh/Mergify-net
BASE=https://ysaiahdh.github.io/Mergify-net
FPR=0168C7AB77721656904811AE904F61E7F4F1C2A1

say() { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
die() { printf 'mergify-net install: %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }
fetch() { # fetch URL: the body on stdout
    if have curl; then curl -fsSL "$1"; elif have wget; then wget -qO- "$1"; else die "needs curl or wget"; fi
}

if [ "$(id -u)" = 0 ]; then SUDO=
elif have sudo; then SUDO=sudo
elif have doas; then SUDO=doas
else die "run it as root, or install sudo"; fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# ---- the release tarball, for any CPU mergify-net is built for
tarball() {
    if [ "$(uname -s)" = Darwin ]; then
        name=macos-universal
    else
        case $(uname -m) in
            i?86) name=linux-i686 ;;
            armv8l) name=linux-armv7l ;;
            *) name=linux-$(uname -m) ;;
        esac
    fi
    say "Downloading mergify-net ($name)"
    fetch "$REPO/releases/latest/download/mergify-net-$name.tar.gz" | tar -xz -C "$tmp" mergify-net ||
        die "no download for $name; see $REPO/releases"
    if [ "$(uname -s)" = Darwin ]; then
        $SUDO "$tmp/mergify-net" install   # copies it to /usr/local/bin, lets the tray start without a password
    else
        $SUDO install -D -m 755 "$tmp/mergify-net" /usr/local/bin/mergify-net
        if have setcap; then
            $SUDO setcap cap_net_admin,cap_net_raw+ep /usr/local/bin/mergify-net
        else
            say "setcap not found: install libcap, then run: sudo setcap cap_net_admin,cap_net_raw+ep /usr/local/bin/mergify-net"
        fi
    fi
}

# ---- Homebrew (as the user: brew refuses root)
homebrew() {
    say "Installing with Homebrew"
    brew install ysaiahdh/tap/mergify-net
    bin=$(brew --prefix)/bin/mergify-net
    if [ "$(uname -s)" = Darwin ]; then
        $SUDO "$bin" install
    else
        $SUDO setcap cap_net_admin,cap_net_raw+ep "$(readlink -f "$bin")"
    fi
}

# ---- the system package manager, from the signed repositories
apt_install() {
    say "Installing with apt"
    fetch "$BASE/apt/key.gpg" > "$tmp/key.gpg"
    $SUDO install -D -m 644 "$tmp/key.gpg" /usr/share/keyrings/mergify-net.gpg
    echo "deb [signed-by=/usr/share/keyrings/mergify-net.gpg] $BASE/apt stable main" | $SUDO tee /etc/apt/sources.list.d/mergify-net.list >/dev/null
    $SUDO apt-get update -qq
    $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq mergify-net
}
rpm_install() { # rpm_install dnf|yum
    say "Installing with $1"
    fetch "$BASE/rpm/mergify-net.repo" > "$tmp/mergify-net.repo"
    $SUDO install -m 644 "$tmp/mergify-net.repo" /etc/yum.repos.d/mergify-net.repo
    $SUDO "$1" install -y mergify-net
}
zypper_install() {
    say "Installing with zypper"
    $SUDO zypper --non-interactive lr mergify-net >/dev/null 2>&1 || $SUDO zypper --non-interactive addrepo "$BASE/rpm/mergify-net.repo"
    $SUDO zypper --non-interactive --gpg-auto-import-keys refresh mergify-net
    $SUDO zypper --non-interactive install mergify-net
}
apk_install() {
    say "Installing with apk"
    fetch "$BASE/apk/mergify-net.rsa.pub" > "$tmp/mergify-net.rsa.pub"
    $SUDO install -m 644 "$tmp/mergify-net.rsa.pub" /etc/apk/keys/mergify-net.rsa.pub
    grep -qx "$BASE/apk" /etc/apk/repositories || echo "$BASE/apk" | $SUDO tee -a /etc/apk/repositories >/dev/null
    $SUDO apk add mergify-net
}
pacman_install() {
    say "Installing with pacman"
    # a fresh system may have no local pacman key yet, which signing ours needs
    $SUDO pacman-key --list-secret-keys 2>/dev/null | grep -q '^sec' || $SUDO pacman-key --init
    if ! $SUDO pacman-key --list-keys "$FPR" >/dev/null 2>&1; then
        fetch "$BASE/apt/key.asc" > "$tmp/key.asc"
        $SUDO pacman-key --add "$tmp/key.asc"
        $SUDO pacman-key --lsign-key "$FPR"
    fi
    grep -q '^\[mergify-net\]' /etc/pacman.conf ||
        printf '\n[mergify-net]\nSigLevel = Required\nServer = %s/arch/$arch\n' "$BASE" | $SUDO tee -a /etc/pacman.conf >/dev/null
    $SUDO pacman -Sy --needed --noconfirm mergify-net
}

# ---- uninstall: stop it, then remove it the way it was installed, with the repository it came from
uninstall() {
    say "Stopping mergify-net"
    if have mergify-net; then
        mergify-net quit >/dev/null 2>&1 || true           # the tray
        $SUDO mergify-net off >/dev/null 2>&1 || true      # system-wide mode started from a terminal
        mergify-net autostart off >/dev/null 2>&1 || true  # start at sign-in
        sleep 2
    fi
    if [ "$(uname -s)" = Darwin ]; then
        for exe in /opt/mergify-net/bin/mergify-net /usr/local/bin/mergify-net; do
            [ -x "$exe" ] && { $SUDO "$exe" uninstall || true; break; }   # the root-owned copy and the sudoers rule
        done
        if have brew && brew list --formula mergify-net >/dev/null 2>&1; then
            say "Removing with Homebrew"
            brew uninstall mergify-net
            brew untap ysaiahdh/tap >/dev/null 2>&1 || true
        fi
        $SUDO rm -rf /var/run/mergify-net "$HOME/Library/Logs/mergify-net.log"
    else
        if have dpkg && dpkg -s mergify-net >/dev/null 2>&1; then
            say "Removing with apt"
            $SUDO env DEBIAN_FRONTEND=noninteractive apt-get remove -y -qq mergify-net
            $SUDO rm -f /etc/apt/sources.list.d/mergify-net.list /usr/share/keyrings/mergify-net.gpg
        elif have rpm && rpm -q mergify-net >/dev/null 2>&1; then
            if have zypper; then
                say "Removing with zypper"
                $SUDO zypper --non-interactive remove mergify-net
                $SUDO zypper --non-interactive removerepo mergify-net >/dev/null 2>&1 || true
            else
                pm=dnf; have dnf || pm=yum
                say "Removing with $pm"
                $SUDO "$pm" remove -y mergify-net
                $SUDO rm -f /etc/yum.repos.d/mergify-net.repo
            fi
        elif have apk && apk info -e mergify-net >/dev/null 2>&1; then
            say "Removing with apk"
            $SUDO apk del mergify-net
            $SUDO sed -i "\#^$BASE/apk\$#d" /etc/apk/repositories
            $SUDO rm -f /etc/apk/keys/mergify-net.rsa.pub
        elif have pacman && pacman -Q mergify-net >/dev/null 2>&1; then
            say "Removing with pacman"
            $SUDO pacman -R --noconfirm mergify-net
            $SUDO sed -i '/^\[mergify-net\]$/,/^Server = .*mergify-net/d' /etc/pacman.conf
            $SUDO pacman-key --delete "$FPR" >/dev/null 2>&1 || true
        fi
        if have brew && brew list --formula mergify-net >/dev/null 2>&1; then
            say "Removing with Homebrew"
            brew uninstall mergify-net
            brew untap ysaiahdh/tap >/dev/null 2>&1 || true
        fi
        $SUDO rm -f /usr/local/bin/mergify-net   # the tarball
        rm -rf "${XDG_CACHE_HOME:-$HOME/.cache}/mergify-net"
    fi
    hash -r 2>/dev/null || true
    have mergify-net && die "a mergify-net is still at $(command -v mergify-net); remove it by hand"
    say "mergify-net is uninstalled. Your network settings are back to normal."
}

case ${1:-} in
    uninstall|--uninstall) uninstall; exit 0 ;;
    '') ;;
    *) die "unknown option $1 (use: uninstall)" ;;
esac

# ---- pick the way
method=${MERGIFY_NET_METHOD:-auto}
if [ "$(uname -s)" = Darwin ]; then
    # a running tray holds the old binary: stop it before replacing it
    pgrep -qf 'mergify-net tray' && { mergify-net quit 2>/dev/null || true; sleep 2; }
    $SUDO mkdir -p /usr/local/bin   # where `mergify-net install` puts its root-owned copy
    if [ "$method" = auto ] && have brew && [ "$(id -u)" != 0 ]; then homebrew; else tarball; fi
elif [ "$(uname -s)" = Linux ]; then
    if [ -f /etc/NIXOS ]; then
        die "on NixOS use the module instead: $REPO#nixos"
    elif [ "$method" != auto ]; then tarball
    elif have apt-get && have dpkg && case $(dpkg --print-architecture) in amd64|arm64|armhf|i386|loong64|ppc64el|riscv64|s390x) true ;; *) false ;; esac; then apt_install
    elif have dnf; then rpm_install dnf
    elif have yum; then rpm_install yum
    elif have zypper; then zypper_install
    elif have apk; then apk_install
    elif have pacman; then pacman_install
    elif have brew && [ "$(id -u)" != 0 ]; then homebrew
    else tarball; fi
else
    die "this script is for macOS and Linux; on Windows run in PowerShell: irm https://raw.githubusercontent.com/ysaiahdh/Mergify-net/main/install.ps1 | iex"
fi

have mergify-net || PATH=$PATH:/usr/local/bin
say "Installed $(mergify-net version)"

# ---- start the tray: on a Mac, or on a Linux desktop session that isn't root's
if [ "${MERGIFY_NET_NO_START:-}" = 1 ]; then
    say "Start it with: mergify-net tray"
elif [ "$(uname -s)" = Darwin ] || { [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ] && [ "$(id -u)" != 0 ]; }; then
    nohup mergify-net tray >/dev/null 2>&1 &
    say "Started: look for the mergify-net icon in the menu bar or tray. To start it at sign-in: mergify-net autostart on"
else
    say "Start it with: mergify-net tray (from your desktop session), or mergify-net run (any terminal, as root)"
fi
