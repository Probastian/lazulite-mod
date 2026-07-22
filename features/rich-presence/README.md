# features/rich-presence

Detects what the local player is currently doing (dimension, biome,
movement/building behavior, pause/menu state, vehicle, village proximity) and
publishes a human-readable, translated status string to Steam's own Rich
Presence `"status"` key via `SteamFriendsGateway.setLocalRichPresence`.

See `specification.md` and `plan.md` in this directory for the full design.

Plain-JVM-testable core: `PresenceStatusResolver` (tier precedence),
`RichPresencePublisher` (debounced write). Platform modules
(`platform/fabric-*`) own every `net.minecraft.*`-typed signal read
(`PresenceSignalGatherer`) and the `Text`/`Component`-based string formatting
(`MinecraftTierTextFormatter`).
