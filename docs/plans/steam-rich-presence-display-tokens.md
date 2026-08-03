# steam_display Localization Token Support — Implementation Plan

Source spec: `features/rich-presence/specification.md`, addendum "Addendum:
`steam_display` Localization Token Support" (approved, section starts at the
`---` divider ~line 693, ends at file end ~line 1129). All requirement IDs
below (FR-RPD1..6) refer to that addendum. Planning only — no code changes
made by this document.

> **HISTORICAL NOTE — superseded design.** This plan (and the addendum
> section it was drafted from) originally specified **two** separate
> interpolation keys, `"biome"` and `"dimensionSuffix"`, with token text
> referencing both (e.g. `"Staying in %biome%%dimensionSuffix%"`). That
> design shipped a confirmed live bug: Steam only substitutes a `%variable%`
> if that exact key was set in the same `SetRichPresence` call, and the
> "never send empty string" omission rule meant `dimensionSuffix` was
> correctly never sent for Overworld tiers — but the token text still
> referenced `%dimensionSuffix%` unconditionally, so Steam rendered the
> literal, un-substituted text to real friends (e.g. "Staying in
> Forest%dimensionSuffix%"). This was fixed by replacing both keys with a
> single combined `"location"` key (biome + dimension suffix composed fully
> in code, e.g. `"Forest in the Nether"`), always present whenever a tier's
> token references it. See `features/rich-presence/specification.md`'s
> FR-RPD4 for the full writeup and `RichPresencePublisher.java`'s class
> Javadoc for the code-level "lesson learned" note. Everything below this
> note describes the original, now-superseded two-key design and is kept for
> historical context only — read it with that in mind.

## Existing implementation (repo findings, read directly this pass)

- `features/rich-presence/src/main/java/de/lazuli/features/richpresence/services/LocalPresenceTracker.java`
  — single-method interface, `Optional<String> currentStatus()`.
- `.../LocalPresenceTrackerImpl.java` — resolves `PresenceTier` via
  `PresenceStatusResolver`, short-circuits `TierKind.MAIN_MENU` to
  `Optional.empty()` (FR-RP7), otherwise delegates to injected
  `TierTextFormatter.format(tier)` for the full localized string. Holds no
  other state.
- `.../NoopLocalPresenceTracker.java` — disabled-state twin, `currentStatus()`
  always `Optional.empty()`. Will need a matching `currentTier()` override.
- `.../PresenceTier.java` — record `(TierKind kind, Optional<String>
  biomeTranslationKey, boolean nether, boolean end)`. `biomeTranslationKey` is
  the *untranslated* Minecraft translation key (e.g. `"biome.minecraft.plains"`),
  not yet localized — localization only happens in `TierTextFormatter`.
- `.../TierTextFormatter.java` — `@FunctionalInterface`, one method
  `String format(PresenceTier tier)`. This is the **only** existing seam that
  turns a `biomeTranslationKey` into a localized string, and it does so
  inline as part of building the *entire* sentence — there is currently no
  way to get just the localized biome name alone. This addendum requires
  extending this interface (see Task 1).
- Platform implementations (all three, identical shape):
  `platform/fabric-1.21.11/.../richpresence/MinecraftTierTextFormatter.java`,
  same path under `fabric-26.1`, `fabric-26.2`. Each does
  `tier.biomeTranslationKey().map(Text::translatable).orElseGet(() ->
  Text.literal(""))` then `.getString()` inline inside a `switch`+dimension-
  suffix wrapper — confirmed via direct read of the fabric-1.21.11 copy; the
  other two are structurally identical per this feature's established
  per-platform-duplication pattern (confirmed pattern, not re-read line by
  line — verifier should diff all three).
- `.../TierKind.java` — 10-value enum, `MAIN_MENU` first (sentinel), then
  `PAUSED, SPECTATING, RIDING_MINECART, RIDING_BOAT, NEAR_VILLAGE, EXPLORING,
  STAYING, BUILDING, DIGGING_AROUND`.
- `.../RichPresencePublisher.java` — owns the single debounced `"status"`
  write. `static final String STATUS_KEY = "status"`; `tick()` compares
  `tracker.currentStatus()` against `lastWritten`, writes/clears only on
  change, logs exactly once per transition, returns early without updating
  `lastWritten` if the gateway write is rejected (`accepted == false`).
- `.../RichPresencePublisherTest.java` — `ScriptedTracker` fake implements
  only `currentStatus()`; will need a `currentTier()` override added (script
  per-tick `LocalPresenceTierSnapshot` values too) without breaking the
  existing 8 tests (`writesOnlyOnActualChange`, `logsExactlyOnceOnActualChange`,
  `logsExactlyOnceOnClear`, `clearsOnlyOncePerPresentToEmptyTransition`,
  `neverCallsSetLocalRichPresenceWithConnectKey`,
  `logsWarningAndDoesNotThrowWhenSteamRejectsWrite`,
  `doesNotClearOnFirstTickWhenAlreadyEmpty`).
- `services/src/main/java/de/lazuli/services/steamworks/SteamFriendsGateway.java`
  — interface, `boolean setLocalRichPresence(String key, String value)` and
  `void clearLocalRichPresence()`. No signature change needed (addendum Public
  API item 3).
- `RichPresenceLimits` — confirmed **still does not exist** (per addendum
  FR-RPD5 and cross-check against `docs/plans/steam-rich-presence.md` Task 1,
  which proposes it for the separate `steam-world-hosting` plan, not yet
  merged as of this reading). This plan's Task 2 therefore adds no guard call
  — FR-RPD5 is explicitly "wire through if/when it lands," not a blocking
  dependency.
- `docs/plans/steam-rich-presence.md` — sibling plan (`RichPresenceLimits`,
  `steam_player_group`/`steam_player_group_size`), used here only as a
  formatting/section-convention reference. Confirmed disjoint scope: that
  plan explicitly lists `RichPresencePublisher` under "Not modified"; this
  plan is the one that modifies it.

## Design decisions (resolved this pass, within addendum's declared
planning-phase freedom)

1. **`LocalPresenceTracker` gains a second method, not a folded return type**
   (addendum Open Question 3, "approved as-is," either shape allowed) — pick
   the two-method shape verbatim from FR-RPD2's illustrative code, since it
   is strictly additive (no existing `currentStatus()` caller needs to
   change) and keeps `RichPresencePublisherTest`'s existing assertions on
   `currentStatus()`'s shape untouched.
2. **`LocalPresenceTierSnapshot`** — new `record` in the same package
   (`features/rich-presence/services/`), fields exactly as FR-RPD2:
   `TierKind kind, String localizedBiome, boolean nether, boolean end`.
   `localizedBiome` is `""` (not `null`, not `Optional`) for non-biome-bearing
   tiers, matching the record's own Javadoc contract in the addendum.
3. **`TierTextFormatter` needs a second method to expose the localized biome
   name alone** (a repo-fact not explicitly called out in the addendum, but
   required to satisfy FR-RPD2's "compute `localizedBiome` via the same
   `TierTextFormatter`/biome-translation seam it already uses internally," 
   since today's single `format(PresenceTier)` method only ever returns the
   whole composed sentence, never the bare biome string). Add:
   ```java
   /** @return the localized biome display name, or "" if tier carries none */
   String localizeBiome(PresenceTier tier);
   ```
   to the `TierTextFormatter` interface, implemented in all three
   `MinecraftTierTextFormatter` copies as
   `tier.biomeTranslationKey().map(key -> Text.translatable(key).getString()).orElse("")`
   — reusing the exact same `Text.translatable(key)` call already inlined in
   `biomeArg(tier)` today, just returning the bare string instead of folding
   it into the full sentence. This is the one addendum-adjacent interface
   change not explicitly named in FR-RPD2/FR-RPD3 text; call it out
   explicitly to the verifier as this plan's own inference, not a spec quote.
4. **`RichPresenceTokenMap.tokenFor` returns `Optional<String>` per FR-RPD3's
   Public API shape**, backed by a `Map<TierKind, String>` (or `switch`)
   built once, covering all 10 `TierKind` values including `MAIN_MENU`. No
   `default ->` throw; unrecognized/`MAIN_MENU` explicitly return
   `Optional.empty()` per FR-RPD3's "fail closed" requirement — but since
   `MAIN_MENU` *is* in the table with a real token per the addendum's
   resolved decision, `tokenFor(MAIN_MENU)` returns
   `Optional.of("#Status_MainMenu")`; only a genuinely unrecognized/future
   `TierKind` value (should never happen, `switch` over an enum is exhaustive
   at compile time) needs the fail-closed fallback — implement via an
   exhaustive `switch` expression (compiler-enforced completeness) rather
   than a `Map` with a `getOrDefault`, so a future `TierKind` addition without
   a matching case is a **compile error**, not a silent runtime gap. Note:
   this makes `tokenFor` total in practice; the `Optional.empty()` fail-closed
   path only exists for defensive symmetry with the addendum's wording and is
   effectively unreachable — document this in the class Javadoc.
5. **`RichPresencePublisher.tick()` orchestration** — replace the single
   `tracker.currentStatus()` read with a paired read of both
   `currentStatus()` and `currentTier()` at the top of `tick()` (same tick,
   same debounce trigger — FR-RPD1). Debounce/change-detection continues to
   key off `currentStatus()`'s `Optional<String>` equality exactly as today
   (no behavior change to *when* a write happens); only *what* gets written
   on a change grows to up to 4 keys. Key write order: `status`,
   `steam_display`, then `biome`/`dimensionSuffix` if applicable — order does
   not need to be a hard contract but this is the natural read order and what
   tests should assert against for clarity (no test should depend on order
   itself, only on which keys got called with which values, since Mockito
   `verify` doesn't inherently check ordering unless `InOrder` is used —
   don't introduce an `InOrder` requirement, it adds brittleness FR-RPD1
   doesn't ask for).
6. **Omit-if-empty implementation shape** — a small private helper in
   `RichPresencePublisher`, e.g.
   `private void writeIfPresent(String key, String value)` that no-ops when
   `value == null || value.isEmpty()`, else calls
   `gateway.setLocalRichPresence(key, value)` (with the same
   accepted/rejected logging path `status` already has, or a lighter
   "rejected, log and continue" path — decide during implementation whether
   a rejected `steam_display`/`biome`/`dimensionSuffix` write should abort the
   rest of the tick's writes or just skip logging+continue; recommend:
   log a warning per rejected key exactly like the existing `status` rejection
   path, but do not `return` early — a rejected `biome` write should not
   prevent `steam_display` from still being attempted, since they are
   independent Steam-side keys even though written together). This
   directly implements the addendum's "never write empty string" rule
   uniformly across `steam_display` (never empty by construction, since
   `tokenFor` always returns a non-empty token for the only tiers this
   publisher ever sees — non-`MAIN_MENU`, since `currentStatus()` is already
   empty for `MAIN_MENU` and the tick returns early on no status), `biome`,
   and `dimensionSuffix`.
7. **`dimensionSuffix` composition** lives in `RichPresencePublisher` (or a
   tiny private static helper there), per Architecture: `""` (Overworld),
   `" in the Nether"` (nether), `" in the End"` (end) — computed from
   `LocalPresenceTierSnapshot.nether()/end()`, independent of `TierKind`.

## Files to create

1. `features/rich-presence/src/main/java/de/lazuli/features/richpresence/services/LocalPresenceTierSnapshot.java`
   — new record, FR-RPD2 shape (Design decision 2).
2. `features/rich-presence/src/main/java/de/lazuli/features/richpresence/services/RichPresenceTokenMap.java`
   — new pure class, FR-RPD3 (Design decision 4).
3. `features/rich-presence/steamworks-localization-tokens.vdf` — new data
   file, exact contents below (verbatim from addendum "New file" section,
   including the `MAIN_MENU` row, which is final per addendum Open Question 4).
4. `features/rich-presence/src/test/java/de/lazuli/features/richpresence/services/RichPresenceTokenMapTest.java`
   — new test file (kept separate from `RichPresencePublisherTest` per
   FR-RPD6's "or a new sibling test file" option, since `RichPresenceTokenMap`
   is a pure, gateway-free class and deserves isolated unit tests of its own
   distinct from the gateway-mocking tests in `RichPresencePublisherTest`).

### Exact `.vdf` contents (verbatim, final per addendum)

```
// steamworks-localization-tokens.vdf
//
// NOT loaded or parsed by this mod at runtime. This file exists solely as
// data to be manually copy-pasted into the Steamworks partner site:
// App Admin -> Rich Presence Localization, App ID 5052800.
//
// KEEP IN SYNC: every TierKind in
// features/rich-presence/src/main/java/de/lazuli/features/richpresence/services/TierKind.java
// must have exactly one corresponding token here (mapping defined in
// RichPresenceTokenMap / features/rich-presence/specification.md's
// "Addendum: steam_display Localization Token Support", FR-RPD3). Adding a
// new tier without adding + uploading its token here means steam_display
// will render as a raw, unresolved token name to friends viewing it.
"lang" "english"
"Tokens"
{
    "#Status_Paused"          "Paused"
    "#Status_Spectating"      "Spectating"
    "#Status_RidingMinecart"  "Driving through %biome%%dimensionSuffix%"
    "#Status_RidingBoat"      "Sailing through %biome%%dimensionSuffix%"
    "#Status_NearVillage"     "Near a Village in %biome%%dimensionSuffix%"
    "#Status_Exploring"       "Exploring %biome%%dimensionSuffix%"
    "#Status_Staying"         "Staying in %biome%%dimensionSuffix%"
    "#Status_Building"        "Building in %biome%%dimensionSuffix%"
    "#Status_DiggingAround"   "Digging around"
    "#Status_MainMenu"        "In main menu"
}
```

## Files to modify

1. `.../services/LocalPresenceTracker.java` — add
   `Optional<LocalPresenceTierSnapshot> currentTier();` to the interface,
   with Javadoc referencing FR-RPD2.
2. `.../services/LocalPresenceTrackerImpl.java` — implement `currentTier()`:
   resolve the same `PresenceTier` via `resolver.resolve(signals)` (note:
   this means the tier is resolved **twice** per tick if both
   `currentStatus()` and `currentTier()` are called independently —
   acceptable per Design decision 5's "same tick" framing since
   `PresenceStatusResolver.resolve` is cheap/pure and stateless, but flag as
   a minor perf/consistency risk in Risks below; do not micro-optimize by
   caching across the two calls unless implementation finds resolver cost
   non-trivial). Short-circuit `MAIN_MENU` to `Optional.empty()` identically
   to `currentStatus()`. Otherwise build
   `new LocalPresenceTierSnapshot(tier.kind(), formatter.localizeBiome(tier),
   tier.nether(), tier.end())`.
3. `.../services/NoopLocalPresenceTracker.java` — add `currentTier()`
   returning `Optional.empty()`, matching its existing `currentStatus()`.
4. `.../services/TierTextFormatter.java` — add
   `String localizeBiome(PresenceTier tier);` (Design decision 3).
5. `platform/fabric-1.21.11/src/main/java/de/lazuli/richpresence/MinecraftTierTextFormatter.java`,
   `platform/fabric-26.1/.../MinecraftTierTextFormatter.java`,
   `platform/fabric-26.2/.../MinecraftTierTextFormatter.java` — each
   implements the new `localizeBiome` method identically (Design decision 3).
6. `.../services/RichPresencePublisher.java` — inject/use
   `RichPresenceTokenMap` (either constructed internally as `new
   RichPresenceTokenMap()` since it is stateless/pure, or passed via
   constructor for testability — recommend internal construction, consistent
   with how `LocalPresenceTrackerImpl` internally constructs its own
   `PresenceStatusResolver` today rather than injecting it); add the
   `STEAM_DISPLAY_KEY = "steam_display"`, `BIOME_KEY = "biome"`,
   `DIMENSION_SUFFIX_KEY = "dimensionSuffix"` constants alongside the
   existing `STATUS_KEY`; extend `tick()` per Design decisions 5-7. Update
   class Javadoc (currently states "Never calls `setLocalRichPresence` with
   any key other than `status`" — this sentence is now false and must be
   rewritten to describe the new 4-key set while keeping the FR-RP5
   `"connect"`-key exclusion guarantee framing intact).
7. `.../src/test/java/de/lazuli/features/richpresence/services/RichPresencePublisherTest.java`
   — extend `ScriptedTracker` to also implement `currentTier()` (scripted in
   lockstep with `currentStatus()`, same index), add new test cases per
   FR-RPD6 (see Test strategy below); update the 7 existing tests' `verify(...)`
   calls only where a call now also implies new key writes need explicit
   `verify(gateway, never())`/`verify(gateway, times(1))` coverage — existing
   assertions about `status`/`clearLocalRichPresence`/log message text stay
   unchanged in spirit, but each existing test's `ScriptedTracker`
   construction needs a `currentTier()` script added so it compiles/behaves
   correctly (e.g. `writesOnlyOnActualChange`'s `"Exploring Plains"` →
   `"Building in Plains"` sequence needs matching
   `LocalPresenceTierSnapshot(EXPLORING, "Plains", false, false)` →
   `(BUILDING, "Plains", false, false)` entries).

## Risks

1. **Double `PresenceStatusResolver.resolve(signals)` call per tick** (one
   from `currentStatus()`, one from `currentTier()`) if `RichPresencePublisher`
   calls both independently — `resolve` is currently pure/stateless and
   `signalsSupplier.get()` is documented as "cheap, non-blocking," so this is
   expected to be negligible, but the implementer should confirm
   `PresenceStatusResolver.resolve` has no hidden per-call cost (e.g. object
   allocation is fine; a hidden Minecraft API call would not be) before
   accepting this. If found non-trivial, consider folding `currentStatus()`'s
   body to derive its `String` from the same `PresenceTier` value already
   computed for `currentTier()` inside a single internal helper both public
   methods delegate to — this is an allowed internal restructuring since it
   doesn't change either method's public contract.
2. **`TierTextFormatter` interface extension (`localizeBiome`) is not
   explicitly spelled out by the addendum text** (see Design decision 3) — it
   is this plan's own inference to satisfy FR-RPD2's "same seam" requirement.
   If the implementer discovers a different existing seam already exposes a
   bare localized biome string, prefer that instead; otherwise this is the
   only clean way found in the current codebase given `TierTextFormatter`'s
   present single-method shape.
3. **Triplicated platform formatter change** — `MinecraftTierTextFormatter`
   exists identically in three platform modules (`fabric-1.21.11`,
   `fabric-26.1`, `fabric-26.2`); a copy-paste miss in one is a silent
   per-module regression only caught by that module's own build (no shared
   test currently exercises the platform implementation directly — only
   `RichPresencePublisherTest`'s fakes, which never touch the real
   `net.minecraft.*`-backed formatter). Verifier should diff all three files
   after implementation.
4. **`RichPresenceLimits` non-existence is a moving target** — the sibling
   `docs/plans/steam-rich-presence.md` plan may land `RichPresenceLimits`
   before or during this addendum's implementation. If so, FR-RPD5 requires
   wiring the three new keys through it; if that guard is added inside
   `SteamworksSteamFriendsGateway.setLocalRichPresence` itself (as that
   plan's Task 1 proposes), no `RichPresencePublisher`-side change is needed
   at all — the guard applies transparently to every key already. Flag this
   as a coordination point between the two plans/implementers, not a blocker.
5. **Existing `RichPresencePublisherTest` tests need mechanical updates**
   (adding `currentTier()` scripts to `ScriptedTracker` and each test's
   construction) even though this addendum otherwise doesn't touch their
   assertions — a missed/wrong scripted `LocalPresenceTierSnapshot` value
   could cause an existing test to newly assert a wrong `steam_display`/
   `biome` value rather than fail cleanly; care is needed to keep each
   existing test's tier data consistent with its already-asserted `status`
   string (e.g. `"Exploring Plains"` must pair with
   `LocalPresenceTierSnapshot(EXPLORING, "Plains", false, false)`, not an
   arbitrary tier).
6. **Manual, out-of-band Steamworks partner-site upload is not automatable**
   (per addendum Compatibility section) — `steam_display` will not actually
   render for any real friend until someone manually pastes
   `steamworks-localization-tokens.vdf`'s contents into App Admin → Rich
   Presence Localization for App ID `5052800`. This plan's acceptance
   criteria therefore separate "code + tests done" from "actually visible to
   real friends," matching the addendum's own framing; this is a manual
   release-checklist item, out of scope for automated verification.

## Dependencies

No new external (non-Fabric, non-repo) dependency is introduced by this
plan — every new class (`LocalPresenceTierSnapshot`, `RichPresenceTokenMap`)
is plain-JVM using only `java.util.Optional`/records, and every modified
class already depends on what it needs (`net.minecraft.text.Text` in the
platform formatters, already imported; `de.lazuli.services.steamworks.SteamFriendsGateway`,
already a dependency of `RichPresencePublisher`). No Maven/Gradle coordinate
lookup is required for this plan.

Sequencing dependency: this plan's `RichPresencePublisher` changes are
independent of and do not block on `docs/plans/steam-rich-presence.md`'s
`RichPresenceLimits` work (Risk 4) — either can land first.

## Test strategy

All tests are plain-JVM JUnit 5 + Mockito, matching the existing
`RichPresencePublisherTest` pattern — no Minecraft client/Steam client needed
(both `RichPresenceTokenMap` and the extended `RichPresencePublisher` remain
free of `net.minecraft.*`/native Steam calls; only the fake
`SteamFriendsGateway` mock and `ScriptedTracker` fake are involved).

**New file `RichPresenceTokenMapTest.java`:**
- `tokenFor(kind)` returns the exact expected `"#Status_..."` string for all
  9 `TierKind` values that have one (all except none — `MAIN_MENU` included,
  since it now has `#Status_MainMenu` per the addendum's resolved decision).
  Cross-check every value against the FR-RPD3 table above, one assertion per
  `TierKind` (10 total, covering all enum values including `MAIN_MENU`, since
  the addendum defines exactly 10 rows, matching `TierKind`'s 10 values).
- (Defensive/documentation test, may be trivially skipped if `switch` is
  proven exhaustive at compile time) no test needed for "unrecognized
  `TierKind`" since Java enums cannot have an out-of-band value at runtime
  without reflection abuse — do not write a test that requires reflection to
  fabricate a fake enum constant; rely on the compiler's exhaustiveness
  check instead, documented in Design decision 4.

**Extended `RichPresencePublisherTest.java`** (FR-RPD6):
- Update `ScriptedTracker` to hold a parallel `LocalPresenceTierSnapshot[]`
  script alongside the existing `Optional<String>[]` script, both advanced
  by the same tick-driven index, and implement `currentTier()`.
- Update all 7 existing tests' `ScriptedTracker` construction with matching
  tier data (Risk 5) — expected behavior of each existing assertion
  (debounce/log/clear timing) is unchanged.
- New test: for at least one biome-bearing tier (e.g. `EXPLORING`) in the
  Overworld, verify `setLocalRichPresence("steam_display", "#Status_Exploring")`,
  `setLocalRichPresence("biome", "Plains")`, and
  **`never()` on any `setLocalRichPresence(eq("dimensionSuffix"), any())`**
  call (Overworld → omitted key, per the resolved omit-empty rule).
- New test: same biome-bearing tier in the Nether — verify
  `setLocalRichPresence("dimensionSuffix", " in the Nether")` is called, plus
  `biome`/`steam_display` as above. One more case for the End
  (`" in the End"`), at minimum for one tier — need not repeat for every
  biome-bearing tier given the composition logic is shared/tier-independent,
  but FR-RPD6 explicitly still wants "at least one Overworld and one
  Nether-or-End case" per biome-bearing tier category, not per specific tier;
  interpret this as: one full Overworld+Nether(or End) pair for `EXPLORING`
  is sufficient baseline coverage, and add a second pair for a *different*
  biome-bearing tier (e.g. `RIDING_BOAT`) to guard against a `switch`
  case-order/copy-paste bug in `RichPresenceTokenMap` specifically (not to
  re-verify `dimensionSuffix` composition itself, which is tier-independent).
- New test: for each non-biome-bearing tier represented in this file's
  existing scripting conventions (`PAUSED` at minimum, since `SPECTATING`/
  `DIGGING_AROUND` share the same code path — one representative case plus a
  code comment noting the other two share the same `RichPresenceTokenMap`
  branch structure is acceptable, full enumeration not required): verify
  `steam_display` is set to that tier's token, and
  **`never()` on `setLocalRichPresence(eq("biome"), any())`** and
  **`never()` on `setLocalRichPresence(eq("dimensionSuffix"), any())`**.
- New test: `MAIN_MENU`/no-session-active — `currentStatus()` returns
  `Optional.empty()` (as today), assert `never()` on
  `setLocalRichPresence(eq("steam_display"), any())`,
  `never()` on `biome`/`dimensionSuffix`, and that `clearLocalRichPresence()`
  is still called exactly per the existing FR-RP7 behavior (reuse
  `clearsOnlyOncePerPresentToEmptyTransition`'s existing pattern, just extend
  its assertions rather than adding a wholly separate test if that's a
  cleaner fit during implementation).
- Extend `neverCallsSetLocalRichPresenceWithConnectKey`: keep the existing
  assertion (`never().setLocalRichPresence(eq("connect"), any())`) exactly as
  is — it already generalizes correctly to "regardless of how many keys are
  now written," no change to the assertion itself is strictly required, only
  confirm (via the new tests above) that the added keys don't somehow also
  route through `"connect"`. If FR-RPD6 wants this made explicit, add a
  one-line comment referencing that this test's guarantee already covers the
  addendum's larger key set.
- `logsWarningAndDoesNotThrowWhenSteamRejectsWrite` — decide during
  implementation whether to extend this test to also cover a rejected
  `steam_display`/`biome`/`dimensionSuffix` write (Design decision 6's
  "continue rather than abort" behavior) with its own dedicated case, or
  leave it covering only `status` rejection (already-passing behavior) and
  add a *new*, separate test for a new-key rejection case — recommend the
  latter (new test `logsWarningWhenStatusKeyAcceptedButSteamDisplayRejected`
  or similar) to keep the original test's scope/name accurate.

Full existing `rich-presence` module test suite (`RichPresenceConfigTest`,
`RichPresenceConfigIOTest`, `PresenceStatusResolverTest`,
`RichPresencePublisherTest`) must stay green throughout.

## Acceptance criteria

1. `LocalPresenceTracker` exposes `currentTier(): Optional<LocalPresenceTierSnapshot>`
   (FR-RPD2), implemented correctly by `LocalPresenceTrackerImpl` (mirrors
   `currentStatus()`'s `MAIN_MENU` short-circuit) and by
   `NoopLocalPresenceTracker` (always empty).
2. `RichPresenceTokenMap.tokenFor(TierKind)` returns the exact token name for
   all 10 `TierKind` values per the table below, verified by
   `RichPresenceTokenMapTest`.
3. `RichPresencePublisher.tick()`, on every tick it would already write/clear
   `"status"` (unchanged trigger condition), also writes `"steam_display"`
   plus, only for biome-bearing tiers, `"biome"` and `"dimensionSuffix"` —
   all in the same tick, via the same debounce trigger (FR-RPD1) — verified
   by the extended `RichPresencePublisherTest`.
4. Keys with empty/absent values (`biome`/`dimensionSuffix` for non-biome-
   bearing or Overworld tiers respectively) are never written as `""` — they
   are omitted from that tick's `setLocalRichPresence` calls entirely,
   verified by explicit `never()` assertions in the new tests.
5. `MAIN_MENU`/no active session never triggers a `steam_display`/`biome`/
   `dimensionSuffix` write; existing `clearLocalRichPresence()`/FR-RP7
   behavior is unchanged.
6. `neverCallsSetLocalRichPresenceWithConnectKey` and all other 6 pre-existing
   `RichPresencePublisherTest` tests still pass (updated only mechanically
   for the new `ScriptedTracker` shape, no assertion weakened).
7. `features/rich-presence/steamworks-localization-tokens.vdf` exists with
   exactly the 10-token contents specified above (9 tiers + header comment;
   note table has 10 `TierKind` rows total including `MAIN_MENU` — file
   contains exactly 10 `"#Status_..."` lines), matching `RichPresenceTokenMap`'s
   token strings verbatim (verifier should diff the two side by side).
8. No change to `SteamFriendsGateway`'s method signatures, `ConnectStringCodec`,
   `"connect"`-key semantics, or any `steam-world-hosting`/friends-sidebar code
   (addendum Non-goals).
9. All three platform `MinecraftTierTextFormatter.java` copies
   (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`) implement the new
   `localizeBiome` method identically — verified by the verifier phase
   diffing all three files.
10. Full existing `rich-presence` module test suite remains green.

## Tier-to-token table (for reference, verbatim from FR-RPD3)

| `TierKind` | `steam_display` token | Interpolation keys |
|---|---|---|
| `PAUSED` | `#Status_Paused` | none |
| `SPECTATING` | `#Status_Spectating` | none |
| `RIDING_MINECART` | `#Status_RidingMinecart` | `biome`, `dimensionSuffix` |
| `RIDING_BOAT` | `#Status_RidingBoat` | `biome`, `dimensionSuffix` |
| `NEAR_VILLAGE` | `#Status_NearVillage` | `biome`, `dimensionSuffix` |
| `EXPLORING` | `#Status_Exploring` | `biome`, `dimensionSuffix` |
| `STAYING` | `#Status_Staying` | `biome`, `dimensionSuffix` |
| `BUILDING` | `#Status_Building` | `biome`, `dimensionSuffix` |
| `DIGGING_AROUND` | `#Status_DiggingAround` | none |
| `MAIN_MENU` | `#Status_MainMenu` | none (literal "In main menu"; never actually written by `RichPresencePublisher` since FR-RP7's clear-on-main-menu path takes precedence and no session is active) |
