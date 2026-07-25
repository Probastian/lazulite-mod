# Cross-World Stats

Background tracker that accumulates vanilla's own per-world player statistics
(`StatisticsManager`/`stats/<uuid>.json`) cumulatively across every world the
local player has ever played, keyed by the local Steam account. See
`specification.md` and `implementation-plan.md` for the full design.

This module (`features/cross-world-stats`) contains only Minecraft/Steamworks-
free code: `api/CrossWorldStatsConfig`, `config/CrossWorldStatsConfigIO`, and
`services/{CrossWorldStatsAggregator, CrossWorldStatsService,
NoopCrossWorldStatsFacade}`. The per-version vanilla `Stat`/`StatType`
registry mapping and the Fabric API merge-hook event registration live in each
`platform/fabric-<version>/.../crossworldstats/CrossWorldStatsMergeHook.java`.

No UI of its own -- this feature's public read contract
(`de.lazuli.api.crossworldstats.CrossWorldStatsFacade`) is intended for a
future `features/main-menu` Statistics tab to consume.
