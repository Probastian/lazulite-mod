# features/steam-world-hosting

Makes every singleplayer world the player opens automatically joinable by their
Steam friends, tunneled over Steam's peer-to-peer networking (legacy
`ISteamNetworking`) rather than a forwarded port or a manual "Open to LAN"
click.

This module holds only the plain-JVM, `net.minecraft.*`/steamworks4j-free
business logic:

- `api/SteamWorldHostingConfig` — the feature's local settings.
- `config/SteamWorldHostingConfigIO` — load/parse/serialize `config/steam-world-hosting.json`.
- `services/ConnectStringCodec` — the `"+lazuli_join <steamId64>"` Rich Presence
  connect-string encode/decode (FR2.3).
- `services/HostGateway` — the plain-JVM `canJoin(friendSteamId64)` friend-gate
  predicate (FR1.3/FR1.5).
- `services/HostingLifecycle` — the on/off + Rich-Presence-string hosting state
  (FR1.1/FR1.2/FR2.1/FR2.2).
- `services/HostingPresenceScanner` — the rate-limited "which friends are
  hosting" scanner, implementing `FriendHostingStatusReader` (FR4.2).
- `services/Noop*` — the disabled/Steam-unavailable variants (FR0.2/FR0.3).

Everything below the `Connection`/`ServerConnectionListener` layer (the custom
Netty `Channel`/`ServerChannel` implementations, the Steam P2P poller threads,
and all mixins) lives per-platform under `platform/fabric-<version>/`, never
here — it directly subclasses/targets version-specific `net.minecraft.*`/Netty
types and cannot be shared common code.
