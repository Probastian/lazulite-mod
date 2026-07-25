# Main Menu — Bug-Fix Implementation Plan (Post-Launch Fixes 3)

Spec: `features/main-menu/specification-post-launch-fixes-3.md` (approved).

## Existing Implementation

All three bugs live in three parallel, structurally-identical platform modules — `platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2` (package `de.lazuli`) — each with its own copy of:
- `friends/FriendSidebarWidget.java`
- `mainmenu/MainMenuScreen.java`
- `mainmenu/{WorldsPanel,ServersPanel,StorePanel,WardrobePanel}.java`

Confirmed by direct read/grep (not assumed from the spec) that fabric-26.1 and fabric-26.2 are line-identical to each other for the sites this plan touches; fabric-1.21.11 is offset by exactly -1 to -2 lines throughout (e.g. `panelY()` at line 125 vs 124, `mouseClicked`'s inline `h` at line 231 vs 232) but otherwise structurally identical — same method names, same code shape, same order of statements. No copy has diverged in a way that changes the fix approach.

**Bug 1 relevant fields/lines (fabric-26.2 refs; fabric-26.1 identical; fabric-1.21.11 offset -1/-2):**
- `renderNow()`: `animatedWidth` updated line 409, drawn `width = Math.round(animatedWidth)` line 410; drawn `height` computed lines 358-364 (base `totalHeight(...)`, overridden to `screenHeight - topInset` when `friends.size() >= maxRows`).
- Existing "cache what was drawn" pattern already used: `footerWidth`/`footerOptionsY`/`footerQuitY` (fields declared ~192-194, set at lines 559-561) and `dropdownX`/`dropdownY`/`dropdownWidth` (fields ~162-164, set lines 471-479). New cached fields for width/height should follow this exact existing pattern (same section of the class, same field-naming style).
- `isMouseOver()` (lines 756-764): recomputes `height` (line 761, `totalHeight(...)`, no overflow branch) and `width` (line 762, `expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH`, animation-unaware) instead of reading cached drawn values.
- `mouseScrolled()` (line 740, `int width = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;`) has the identical latent pattern. Per spec FR-B1.4 this is optional/not required, but since the fix introduces a cached actual-drawn-width field anyway, reusing it here is a near-zero-cost consistency change — recommend including it in scope (flagged as a plan-level scope decision, not a spec deviation, since FR-B1.4 explicitly leaves it to planning's discretion).

**Bug 2 relevant lines confirmed (fabric-26.2 / fabric-26.1 identical; fabric-1.21.11 offset -1):**
- `panelY()`: line 124 (123-125 method), `return (int) (height * 0.22);`.
- Inline `(int) (height * 0.62)` occurs at **three** call sites, not two: `extractRenderState()` line 152, `mouseClicked()` line 232, **and `mouseScrolled()` line 262** (fabric-1.21.11: 152, 231, 261). The spec's FR-B2.3 names only the first two; the third was found during this planning pass by grepping all three modules and must be included so the fix is complete and the three sites can't drift again.
- `panelX()`/`panelWidth()` (lines 119-129) are the existing accessor-method pattern `panelHeight()` should mirror.

**Bug 3** — per-panel discrepancies are as cited in spec FR-B3.1 through FR-B3.10, confirmed present (via grep for `CARD_SIZE`, `LAST_PLAYED_FORMAT`, `bannerHeight`) in all three platform copies of `WorldsPanel`, `ServersPanel`, `StorePanel`, `WardrobePanel`. No independent re-derivation of root cause needed; spec's file/line citations (fabric-26.2) are the source of truth, with the same -1/-2 line offset expected in fabric-1.21.11 and no offset expected in fabric-26.1.

## Files to Modify

**Bug 1** (per platform: `fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`):
- `platform/<version>/src/main/java/de/lazuli/friends/FriendSidebarWidget.java`
  - Add two new cached instance fields (e.g. `drawnWidth`, `drawnHeight`) alongside the existing `footerWidth`/`dropdownX` cache-field group.
  - In `renderNow()`, after `width`/`height` are finalized (after the overflow-branch override at ~line 363 and after `Math.round(animatedWidth)` at ~line 410), assign the cached fields. Must also cover the early-return handle-only branch (~line 415-427) and the `!steamAvailable` branch (~line 439-451) so the cached fields are always in a state consistent with whatever was actually drawn that frame, including when `renderNow()` returns early — these branches currently don't reach line 410's `width` computation in the handle-only case, so the cache-write placement needs care (see Risks).
  - In `isMouseOver()` (~line 757-764), replace the two recomputed locals with reads of the cached fields (the `isOverHandle` branch at line 758-759 is unchanged per FR-B1.2).
  - Optional (recommended, in-scope per this plan's decision above): apply the same cached-width read in `mouseScrolled()` (~line 740), replacing its own independent `expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH` computation.

**Bug 2** (per platform):
- `platform/<version>/src/main/java/de/lazuli/mainmenu/MainMenuScreen.java`
  - Add a new private method `panelHeight()` mirroring `panelX()`/`panelY()`/`panelWidth()`, returning `height - 2 * panelY()`.
  - Replace all **three** inline `(int) (height * 0.62)` occurrences (`extractRenderState()`, `mouseClicked()`, `mouseScrolled()`) with calls to `panelHeight()`.

**Bug 3** (per platform, per panel — 12 files total across 3 platforms x 4 panels):
- `platform/<version>/src/main/java/de/lazuli/mainmenu/WorldsPanel.java` — FR-B3.1 (2x2 thumbnail grid for expanded row), FR-B3.2 (relative "X ago" last-played time replacing absolute-timestamp formatter), FR-B3.3 (Play/Edit pill spacing/sizing).
- `platform/<version>/src/main/java/de/lazuli/mainmenu/ServersPanel.java` — FR-B3.4 (2x2 grid for saved-view expanded row, reusing Worlds' grid-drawing code/pattern once FR-B3.1 lands), FR-B3.5 (add latency filter dropdown/select wired to `filter.maxPing`), FR-B3.6 (4-column sortable header row including Lock).
- `platform/<version>/src/main/java/de/lazuli/mainmenu/StorePanel.java` — FR-B3.7 (featured-banner swatch element + taller banner to fit it), FR-B3.8 (grid-card swatch resized toward near-full-card square).
- `platform/<version>/src/main/java/de/lazuli/mainmenu/WardrobePanel.java` — FR-B3.9 (slot-selector swatch + equipped-item name, using already-available `state.equippedItemId(slot)`), FR-B3.10 (grid-card swatch resize, same fix as FR-B3.8, shared logic if practical).

No other files (services/api modules, config schemas, network/event code) are touched, per spec's Architecture/Configuration/Events/Networking/Persistence sections — confirmed no cross-file references beyond these classes were found during this pass.

## Order / Dependencies

1. **Bug 2 first.** Purely additive/independent arithmetic change, lowest risk, and its `panelHeight()` shrink changes the `h` passed into every Bug-3 panel's `render`/`mouseClicked` — doing it first means Bug 3 fixes are authored and visually verified against the *final* panel height, not the old one (avoids re-verifying Bug 3 twice).
2. **Bug 1 second.** Fully independent of Bugs 2/3 (different class, no shared state) — order relative to Bug 3 doesn't matter, but doing it before Bug 3 means all three fixes are visually testable together in the same manual pass afterward.
3. **Bug 3 third**, in the sub-order Worlds → Servers → Store/Wardrobe, since FR-B3.4 (Servers' saved-view grid) explicitly reuses whatever grid-drawing approach FR-B3.1 (Worlds) introduces, and FR-B3.10 (Wardrobe grid-card swatch) is the same fix as FR-B3.8 (Store grid-card swatch) — doing Worlds/Store first establishes the pattern the dependent items reuse, avoiding two independently-diverging implementations of the same visual element.
4. Apply each fix to all three platform copies together (not platform-by-platform) before moving to the next fix, so a single mental diff per fix is reviewed/verified across all three copies at once rather than three separate review passes per platform.

## Risks

- **Bug 1 — early-return branches bypass the normal width/height computation.** `renderNow()` has two early returns before reaching the "main" width/height logic: the handle-only collapse-to-handle branch (~lines 415-427, returns before drawing rows/footer) and implicitly the `!facade.isEnabled()` guard at the very top (returns before `refreshScreenSize()` even runs). If cached fields are only written at the end of the "normal" path, `isMouseOver()` could read stale cached values from a previous frame during/after these branches. Mitigation: write the cached fields in every return path that changes what's drawn (or default them to values consistent with `isOverHandle`'s own separate branch, which doesn't consult the cache at all per FR-B1.2, so only the "not handle-only" early return and the disabled-facade case need checking) — verify explicitly during implementation which branches need a cache write and which are already covered by `isOverHandle`'s independent path.
- **Bug 1 — the "stretch to screen edge" render branch (overflow height override, lines 359-364).** This is the exact behavior the fix must replicate in the cache, not work around — the risk is a partial fix that caches `width` correctly but misses re-deriving `height` post-override, silently reintroducing the height half of the original bug. Verification must specifically re-test the `friends.size() >= maxRows` scenario (FR-B1.3c) after the fix, not just the animation-timing scenarios.
- **Bug 1 — `mouseScrolled()` scope creep.** Including the optional `mouseScrolled()` consistency fix (this plan's recommendation) technically exceeds the bug report's original scope (FR-B1.4 marks it optional). Low risk since it reuses the same cached field, but flag explicitly in the PR/commit description as "included for consistency, not independently reported broken," so verification doesn't need a separate repro for it beyond confirming scroll-wheel behavior over the sidebar is unchanged.
- **Bug 2 — third call site not in the spec's FR-B2.3.** The spec names two `(int)(height*0.62)` sites; this plan found a third in `mouseScrolled()` via direct grep across all three modules. Risk: an implementer following only the spec's line citations would miss it and leave one call site un-migrated, silently reintroducing drift between "how tall the panel is for scroll dispatch" vs. "how tall it actually is," which would misroute or drop scroll events at the panel's new (shrunk) bottom edge. Mitigation: this plan's Files-to-Modify section explicitly calls out all three sites per platform; implementation should re-grep for `height * 0.62` in each `MainMenuScreen.java` after editing to confirm zero remaining occurrences.
- **Bug 2 — visual regression in panel-internal content clipping**, per spec FR-B2.4: e.g. `ServersPanel`'s per-row `if (rowY + rowHeight > y + height) break;` truncation logic was written against the old `0.62` ratio and may now clip one fewer/more row than before at common window sizes. Not a design change to make now, but must be visually checked (row counts at a few window sizes/GUI scales) during verification, not just formula correctness.
- **Bug 3 — systemic missing proportional GUI-scale/reference-canvas scaling** (spec's Non-goals + Future Extensions: no scaling utility is designed here). Each FR-B3.x fix must be implemented as a direct, literal fix to the specific discrepancy cited (e.g. resize the swatch, add the missing control) using the existing fixed-pixel-literal style already in these files, *not* as an opportunity to introduce a new scaling abstraction — doing so would silently expand scope beyond "bug-fix, not a redesign" and risks destabilizing the many other pixel literals in these files that aren't part of this bug report. If an implementer's fix naturally suggests a shared scale-factor helper, defer that to a future feature per spec's explicit direction.
- **Bug 3 — cross-panel shared-fix risk (FR-B3.4/FR-B3.8/FR-B3.10).** Servers' saved-view grid explicitly depends on reusing Worlds' new grid-drawing code, and Store/Wardrobe's grid-card swatch resize is meant to share one fix. If these are implemented as copy-pasted near-duplicates that drift slightly (e.g. Worlds' grid tile count/spacing subtly different from Servers'), the shared-code intent from the spec is lost silently. Verification should specifically compare the two grid implementations and the two swatch-resize implementations side by side, not just check each panel in isolation.
- **Cross-platform drift risk.** Fabric-1.21.11 is offset by 1-2 lines from the other two modules everywhere checked in this plan — low risk of a copy/paste line-number error causing the fix to land on the wrong line in that module, but real given three copies must each be independently edited (no shared code path). Mitigation: after editing each module, grep for the original buggy patterns (`height * 0.62`, `expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH` inside `isMouseOver()`, absolute-timestamp `DateTimeFormatter` for last-played) in that module specifically to confirm zero remaining occurrences before moving to the next module.

## Dependencies

No new external (non-Fabric) dependencies are introduced by any of the three fixes. All three bugs are fixed using primitives already in use in this codebase (`GuiGraphicsExtractor`, `AbstractWidget`, `Screen`, `DateTimeFormatter`/`java.time` already imported in `WorldsPanel.java` for the existing absolute-format last-played string, and standard AWT-free Minecraft GUI widget patterns already used elsewhere for dropdowns, per `ServerBrowserFilterState`/existing `EditBox`/`Button` usage in `ServersPanel.java`). No Maven/Gradle coordinate changes are needed; no registry lookup applies.

## Test Strategy

Per spec's UI section and the project's standing manual-verification discipline (`features/main-menu/specification.md` UI section) — these are client-rendered GUI behaviors not covered by existing automated test infrastructure in this repo, so verification is manual, in-game, per platform module, per the existing project convention.

**Bug 1:**
- Repro (a): trigger hover-expand, click a row/dropdown item mid-animation (before the ~120ms width transition completes) — must register.
- Repro (b): hover to expand, move mouse off, click anywhere on the sidebar within the ~250ms coyote window while width is still animating down — must register.
- Repro (c): with a friends list long enough to trigger `friends.size() >= maxRows`, click the footer (Options/Quit) and confirm it still registers, then click a friend row near the bottom of the overflow-extended area — must register (this is the one most likely to be missed by an incomplete fix, per the Risks section above).
- Negative check: click just outside the actually-drawn rect (e.g. a few px right of the drawn edge during a narrow/collapsed frame) — must NOT register, to confirm the widened hit-test isn't now over-permissive.
- If `mouseScrolled()` is included in scope: scroll over the sidebar during animation and confirm scrolling still works and doesn't scroll when off the widget.
- Repeat all of the above on all three platform modules independently (fabric-1.21.11, fabric-26.1, fabric-26.2), each launched separately per the project's per-version manual verification convention.

**Bug 2:**
- At several window sizes/GUI scales, visually confirm the panel's top and bottom gaps to the tab bar's edges are equal (both `~0.22*height`), i.e. the panel reads as vertically centered within the tab bar's full-height span.
- Confirm no `height * 0.62` literal remains in any of the three `MainMenuScreen.java` copies (grep check).
- For each of the four tabs, confirm row-truncation/clipping logic (e.g. `ServersPanel`'s row-overflow `break`) behaves sanely at the new, slightly shorter panel height — no row rendered partially cut off at the new bottom edge, no obviously-wrong row count vs. available space.
- Confirm scroll-wheel dispatch over the Servers panel still works correctly at the panel's new height (validates the third, spec-unlisted call site fix).

**Bug 3** (per panel, per platform):
- WorldsPanel: expanded row shows a 4-tile 2x2 grid (not a single icon); last-played text reads as a relative time string ("3 hours ago" style, not a calendar date); Play/Edit render as two visually separated pill buttons.
- ServersPanel: saved-view expanded row shows the same 2x2 grid as Worlds; browser filter bar has a working latency dropdown that actually filters by `maxPing`; column header row shows four sortable columns including Lock.
- StorePanel: featured banner shows a swatch element alongside text (banner height increased to accommodate it); "All Cosmetics" grid cards show a near-full-card square swatch, not a ~42%-height one.
- WardrobePanel: each slot-selector button shows a swatch of the equipped item plus the equipped item's (truncated) name, not just the slot label; grid cards show the same corrected near-full-card swatch as Store.
- Cross-check the shared-code items (Worlds/Servers grid; Store/Wardrobe swatch resize) render identically/consistently between the two panels that share them.
- Confirm all new fill/text calls use full `0xFF` alpha, per spec's UI section color-literal caution.
- Repeat across all three platform modules.

## Acceptance Criteria

- All FR-B1.1–FR-B1.4 behaviors from the spec are satisfied: `isMouseOver()` reads cached actual-drawn width/height instead of recomputing them; footer/dropdown/row clicks register reliably in all three repro scenarios (FR-B1.3 a/b/c) on all three platform modules; `isOverHandle` branch unchanged.
- All FR-B2.1–FR-B2.4 behaviors satisfied: a single `panelHeight()` method exists per platform, all `(int)(height*0.62)` call sites (three per platform, not two) are replaced by it, resulting top/bottom gaps to the tab bar are equal at `~0.22*height` each, and no panel's internal content is newly clipped at common window sizes.
- All ten FR-B3.1–FR-B3.10 discrepancies are corrected per their spec'd desired behavior, on all three platform modules, without introducing a new scaling abstraction (per spec's Non-goals) and without regressing any panel behavior not named in the spec.
- No new public API surface, no config/schema changes, no networking/event changes — verified by diff review matching spec's Public API/Configuration/Events/Networking/Persistence sections (all state "no changes").
- Every fix is present and independently verified in all three platform modules (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`), not just `fabric-26.2`.
- All new/changed color literals carry full `0xFF` alpha.
