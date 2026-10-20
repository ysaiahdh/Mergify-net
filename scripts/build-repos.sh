#!/usr/bin/env bash
# Builds a signed apt repository and a signed dnf/zypper repository from .deb/.rpm files, as a
# static site that can be served from GitHub Pages. Needs apt-ftparchive (apt-utils), dpkg-deb,
# createrepo_c and gpg, with the signing key already in the keyring. The Alpine and Arch
# repositories are added to the same site by build-container-repos.sh.
#
#   GPG_KEY_ID=<fingerprint> REPO_BASE_URL=https://rohit267.github.io/mergify-net \
#       scripts/build-repos.sh PKG_DIR OUT_DIR
set -euo pipefail
PKGS=$(realpath "${1:?usage: build-repos.sh PKG_DIR OUT_DIR}")
mkdir -p "${2:?usage: build-repos.sh PKG_DIR OUT_DIR}"
OUT=$(realpath "$2")
KEY=${GPG_KEY_ID:?set GPG_KEY_ID to the signing key fingerprint}
BASE=${REPO_BASE_URL:-https://rohit267.github.io/mergify-net}
sign() { gpg --batch --yes --pinentry-mode loopback --local-user "$KEY" "$@"; }

rm -rf "$OUT"/*
mkdir -p "$OUT/apt/pool/main" "$OUT/rpm"
cp "$PKGS"/*.deb "$OUT/apt/pool/main/"
cp "$PKGS"/*.rpm "$OUT/rpm/"

# ---- apt: dists/stable/main/binary-<arch>/Packages, Release, InRelease
cd "$OUT/apt"
archs=$(for f in pool/main/*.deb; do dpkg-deb -f "$f" Architecture; done | sort -u | tr '\n' ' ')
for arch in $archs; do
    d=dists/stable/main/binary-$arch
    mkdir -p "$d"
    apt-ftparchive --arch "$arch" packages pool > "$d/Packages"
    gzip -9kf "$d/Packages"
done
apt-ftparchive \
    -o APT::FTPArchive::Release::Origin=mergify-net \
    -o APT::FTPArchive::Release::Label=mergify-net \
    -o APT::FTPArchive::Release::Suite=stable \
    -o APT::FTPArchive::Release::Codename=stable \
    -o APT::FTPArchive::Release::Architectures="$archs" \
    -o APT::FTPArchive::Release::Components=main \
    -o APT::FTPArchive::Release::Description="mergify-net: use all your network adapters at once" \
    release dists/stable > dists/stable/Release
sign --clearsign -o dists/stable/InRelease dists/stable/Release
sign --armor --detach-sign -o dists/stable/Release.gpg dists/stable/Release
gpg --export "$KEY" > key.gpg      # binary keyring, for signed-by=
gpg --armor --export "$KEY" > key.asc

# ---- dnf: repodata signed with repo_gpgcheck
cd "$OUT/rpm"
createrepo_c --quiet .
sign --armor --detach-sign -o repodata/repomd.xml.asc repodata/repomd.xml
gpg --armor --export "$KEY" > key.asc
cat > mergify-net.repo <<REPO
[mergify-net]
name=mergify-net
baseurl=$BASE/rpm
enabled=1
gpgcheck=0
repo_gpgcheck=1
gpgkey=$BASE/rpm/key.asc
REPO

# ---- a plain page at the root, so the site has something to show
cat > "$OUT/index.html" <<HTML
<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>mergify-net packages</title>
<style>body{font:16px/1.5 system-ui,sans-serif;max-width:46rem;margin:2rem auto;padding:0 1rem}pre{background:#f3f4f6;padding:.8rem;overflow:auto;border-radius:6px}@media(prefers-color-scheme:dark){body{background:#0b1220;color:#e5e7eb}pre{background:#111827}a{color:#4ade80}}</style>
<h1>mergify-net packages</h1>
<p>Use all your internet connections at once. <a href="https://github.com/rohit267/mergify-net">Project on GitHub</a>.
Signing key fingerprint: <code>$KEY</code>.</p>
<h2>Any Linux or macOS: one line</h2>
<pre>curl -fsSL https://raw.githubusercontent.com/rohit267/mergify-net/main/install.sh | sh</pre>
<p>It uses your package manager when it can. Or set it up by hand:</p>
<h2>Debian, Ubuntu, Mint, Pop!_OS, Raspberry Pi OS</h2>
<pre>curl -fsSL $BASE/apt/key.gpg | sudo tee /usr/share/keyrings/mergify-net.gpg &gt;/dev/null
echo "deb [signed-by=/usr/share/keyrings/mergify-net.gpg] $BASE/apt stable main" | sudo tee /etc/apt/sources.list.d/mergify-net.list
sudo apt update &amp;&amp; sudo apt install mergify-net</pre>
<h2>Fedora, RHEL, Rocky, Alma</h2>
<pre>sudo curl -fsSL -o /etc/yum.repos.d/mergify-net.repo $BASE/rpm/mergify-net.repo
sudo dnf install mergify-net</pre>
<h2>openSUSE</h2>
<pre>sudo zypper addrepo $BASE/rpm/mergify-net.repo
sudo zypper --gpg-auto-import-keys refresh
sudo zypper install mergify-net</pre>
<h2>Arch, Manjaro, EndeavourOS</h2>
<pre>curl -fsSL $BASE/apt/key.asc | sudo pacman-key --add -
sudo pacman-key --lsign-key $KEY
printf '\n[mergify-net]\nSigLevel = Required\nServer = $BASE/arch/\$arch\n' | sudo tee -a /etc/pacman.conf
sudo pacman -Sy mergify-net</pre>
<h2>Alpine</h2>
<pre>sudo wget -O /etc/apk/keys/mergify-net.rsa.pub $BASE/apk/mergify-net.rsa.pub
echo $BASE/apk | sudo tee -a /etc/apk/repositories
sudo apk add mergify-net</pre>
<p>Everything else: the <code>.tar.gz</code> for your CPU on the <a href="https://github.com/rohit267/mergify-net/releases/latest">releases page</a>.</p>
HTML
touch "$OUT/.nojekyll"
echo "built apt and dnf repositories in $OUT"
