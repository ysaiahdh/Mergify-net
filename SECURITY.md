# Security

## Reporting a problem

Please **do not open a public issue** for a security problem.

Report it privately instead:

- Use **Report a vulnerability** on the repository's [Security tab](https://github.com/ysaiahdh/Mergify-net/security/advisories/new), or
- email **ysaiah.ayzter@yahoo.com**.

Please include what you found, the steps to reproduce it, and your OS and `mergify-net version`. I will
answer as soon as I can and credit you in the fix if you want.

## Supported versions

Only the [latest release](https://github.com/ysaiahdh/Mergify-net/releases/latest) gets fixes.

## What matters most

mergify-net runs with administrator or root rights, so these are the parts to look at:

- **macOS:** `sudo mergify-net install` copies the binary to `/usr/local/bin` and adds
  `/etc/sudoers.d/mergify-net`, which lets your user run exactly `mergify-net tray` and `mergify-net cleanup` as
  root without a password. Its state lives in the root-owned `/var/run/mergify-net`. Ways to turn that into
  more than it should allow are in scope.
- **Linux:** the binary is given `CAP_NET_ADMIN` and `CAP_NET_RAW` with `setcap`.
- **Windows:** the installer and the elevated tray.
- Anything that lets a normal user run code as root or admin, read or write files through the root
  process, or leave the machine without a working network after a crash.

mergify-net is not a VPN. Your traffic goes straight out of each adapter and is not encrypted by mergify-net.
That is by design and not a vulnerability.
