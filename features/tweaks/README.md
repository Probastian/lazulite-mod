# features/tweaks

Plain-Java (no Minecraft classpath) home of the Tweaks framework: the 12
`TweakId`/`TweakDefinition` static definitions (`services/TweakDefinitions`),
the runtime `TweakRegistry`, `tweaks.json` load/save
(`config/TweaksConfig`/`TweaksConfigIO`), and 12 Minecraft-agnostic hook
interfaces (`services/*Hook.java`) implemented once per platform module.

See `docs/specs/tweaks.md` and `docs/specs/tweaks-plan.md` for the full
specification/plan.
