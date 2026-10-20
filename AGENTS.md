# Instructions Architecture : Portage Android (Mergify-Net)

## Contexte
Portage du projet Go `mergify-net` vers Android (Samsung S25, arm64-v8a) sans root via `gomobile bind` et l'API Android `VpnService`.

## Règles strictes pour l'Agent
1. **Ne pas casser le code existant** : Conserver intacts les fichiers `_windows.go`, `_darwin.go` et `cmd/mergify-net/`.
2. **Contraintes `gomobile` (`gobind`)** : Dans le package exposé à Android (`pkg/mobile`), n'exporter dans les signatures publiques QUE des types supportés par `gobind` (`int`, `int64`, `string`, `bool`, `[]byte`, `error`, et des `interface` simples). Aucun `chan`, `uintptr`, `net.IP` ou slice de structs dans les fonctions exportées.
3. **Contournement SELinux / Android 10+** : Sur Android, `net.Interfaces()` (netlink) et `SO_BINDTODEVICE` sont bloqués sans root. Toute création de socket sortant dans `internal/engine/` doit passer par un callback `SocketProtector` (qui appellera `VpnService.protect(fd)` et `Network.bindSocket(fd)` côté Kotlin).
4. **Interface TUN** : Sur Android, le descripteur de fichier TUN (`tunFd int`) est fourni par `VpnService.Builder().establish().detachFd()`. Ne pas ouvrir `/dev/net/tun` sur Android.