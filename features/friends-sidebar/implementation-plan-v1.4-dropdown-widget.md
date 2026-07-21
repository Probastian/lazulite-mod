# Implementation Plan — v1.4 DropdownWidget (platform/ui) + Friends Sidebar join-policy integration

Covers both approved specs: `platform/ui/specification.md` (new `DropdownWidget`) and `features/friends-sidebar/specification.md`'s v1.4 amendment (integration). Kept as a single lean plan per the user's request to move quickly; the two pieces are sequenced as Phase A / Phase B below but are one deliverable.

## Existing Implementation (research, this pass)

**Platform modules / mapping differences** (confirmed by direct read):
- `platform/fabric-26.2` and `platform/fabric-26.1`: `FriendSidebarWidget extends AbstractWidget` (Mojang mappings), uses `GuiGraphicsExtractor` for all draw calls (`guiGraphics.fill(...)`, `guiGraphics.text(font, ...)`), `net.minecraft.client.input.MouseButtonEvent` for click events, `Minecraft.getInstance()`. Confirmed byte-identical structure between 26.1 and 26.2 (same imports/class shape).
- `platform/fabric-1.21.11`: `FriendSidebarWidget extends ClickableWidget` (Yarn mappings), uses `net.minecraft.client.gui.DrawContext`, `net.minecraft.client.gui.Click` for click events, `net.minecraft.client.MinecraftClient`, `net.minecraft.text.Text`, `net.minecraft.util.Identifier`.
- These are exactly the two idioms `DropdownWidget` must be duplicated across (26.1/26.2 idiom identical; 1.21.11 idiom differs only in class/method names per spec's Compatibility section).

**`FriendSidebarWidget.java` (26.2, canonical read; 26.1 structurally identical; 1.21.11 same logic under Yarn names) — exact integration points:**
- `DROPDOWN_HEIGHT = ROW_HEIGHT` (line 87), `DROPDOWN_BACKGROUND`/`DROPDOWN_TEXT_COLOR`/`DROPDOWN_DESCRIPTION_LINE_HEIGHT` (lines 88-90) — existing pixel constants, reusable by the new control (owned by the embedding widget, per spec UI-FR6/UI section — not moved into `DropdownWidget`).
- `listTopOffset()` / `listTopOffset(boolean expanded)` (lines 161-168) — currently adds a fixed `DROPDOWN_HEIGHT` when `expanded`. Must change to add the `DropdownWidget`'s actual reported open/closed height for the current frame (v1.4-FR7.14) instead of the fixed constant.
- `renderNow(...)` (lines 222-358): the join-policy block is lines 327-342 — computes `dropdownX/dropdownY/dropdownWidth`, sets `dropdownVisible`, calls `drawJoinPolicyControl(...)`. This block is replaced by: constructing/reusing the `DropdownWidget` field, calling its render method with `(dropdownX, dropdownY, dropdownWidth)`, capturing its returned total-height for `listTopOffset()`. Line 317 (`dropdownVisible = false` in the `!steamAvailable` branch) becomes the v1.4-FR7.6a close-if-open hook — must call the `DropdownWidget`'s close method there, not merely stop rendering it.
- `drawJoinPolicyControl(...)` (lines 417-433), `joinPolicyShortLabel(...)` (435-441), `joinPolicyDescription(...)` (443-449): `drawJoinPolicyControl` is deleted (replaced by `DropdownWidget` render call); `joinPolicyShortLabel`/`joinPolicyDescription` are retained verbatim as the label/description source (spec's Architecture item 1), now used to build `DropdownWidget.Option` list at construction time.
- `mouseClicked(...)` (lines 486-523): the dropdown hit-test/click block is lines 502-506 (`if (dropdownVisible && ... ) { facade.cycleJoinPolicy(); return true; }`). Replaced by forwarding to the `DropdownWidget`'s own click method first; if it reports "consumed", return true; otherwise fall through to existing own-profile-row/friend-row logic unchanged (this fallthrough is also how "outside click while open" (UI-FR3) gets signaled back to `DropdownWidget` — see Decision 2 below).
- `wrapMessage(...)` (lines 393-409) — existing greedy word-wrap helper; `DropdownWidget` needs equivalent wrapping for per-row hover descriptions (UI-FR5) — reuse via a small duplicated copy inside `DropdownWidget` (keeps UI-FR6's "zero feature import" intact; `wrapMessage` itself is `FriendSidebarWidget`-private, not shared) or pass in a `ToIntFunction<String>`-based helper. Planning decision: duplicate a minimal wrap function into `DropdownWidget` (same convention `FriendSidebarWidget`/`FriendContextMenuWidget` already used for other duplicated logic across modules).
- `handleOnly`/`panelOpen` fields (95, 120-125, 240-245, 270-271, 282, 289-301, 491-498, 527, 549-551) — unaffected by this change; `DropdownWidget` composition sits entirely inside the already-`expanded`-gated rendering path, which itself is already reachable/unreachable correctly under both `handleOnly` states today. No new interaction with this mechanism is required.
- `FriendsSidebarFacade.cycleJoinPolicy()` (`features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsSidebarFacade.java:192-196`) advances via `stateMachine.nextJoinPolicy(joinPolicy)` then calls `joinPolicyWriter.accept(next)`. Per spec Public API item 11, this plan adds a new `selectJoinPolicy(JoinPolicy)` method (sets `joinPolicy` directly, calls the same `joinPolicyWriter.accept(...)`) alongside (not replacing) `cycleJoinPolicy()` — cheapest change, avoids touching `FriendSidebarStateMachine.nextJoinPolicy` or any existing test of the cycle behavior. `cycleJoinPolicy()` is left in place but becomes dead code from `FriendSidebarWidget`'s perspective after this change; spec does not require deleting it, and deleting it would require touching/checking `FriendsSidebarFacadeTest` — deferred (Risks).

## Files to create

Per platform module (identical structural twin, ×3):
- `platform/fabric-26.2/src/main/java/de/lazuli/ui/DropdownWidget.java`
- `platform/fabric-26.1/src/main/java/de/lazuli/ui/DropdownWidget.java`
- `platform/fabric-1.21.11/src/main/java/de/lazuli/ui/DropdownWidget.java`

Class shape (illustrative, per spec's own "planning decision" framing — UI-FR1/UI-FR4):
```java
package de.lazuli.ui;

public final class DropdownWidget {
    public record Option(String label, String description) {}

    public DropdownWidget(List<Option> options, int initialSelectedIndex, IntConsumer onSelectionChanged);

    public boolean isOpen();
    public void close();                                   // v1.4-FR7.6a hook
    public int render(<GuiGraphicsExtractor|DrawContext> g, int x, int y, int width, int mouseX, int mouseY);
                                                              // returns total height consumed this frame
    public boolean mouseClicked(double mouseX, double mouseY, int button, int x, int y, int width);
                                                              // hit-tests against the same (x,y,width) the
                                                              // caller last rendered at; returns true if consumed
    public int selectedIndex();
}
```
- Plain helper object (not itself an `AbstractWidget`/`ClickableWidget`), per UI-FR4's explicit "may be a plain helper object" allowance — simplest fit for `FriendSidebarWidget`'s existing manual `renderNow()`/`mouseClicked()` composition, avoids a second `Screens.getWidgets(screen)` registration or a nested-widget-list concept this repo has no precedent for.
- "Outside click" (UI-FR3) is not detected by `DropdownWidget` itself (it doesn't know full screen bounds) — the embedding widget calls `mouseClicked` only when its own click wasn't otherwise consumed by a more specific area, and separately calls `close()` for a click confirmed outside the dropdown's own last-rendered bounds. Decision: `DropdownWidget.mouseClicked(...)` internally computes "inside closed row" / "inside an open option row" / "elsewhere (open, but not on this control)" from the same `(x,y,width)` and its own remembered open-row layout; "elsewhere" while open closes it and returns `true` (consumed, so the caller doesn't also treat that click as a friend-row click) only when the elsewhere-click is still within the sidebar's own bounds passed in; a click entirely outside the sidebar widget never reaches `DropdownWidget.mouseClicked` in the first place (the embedding widget's own `isMouseOver` gate already prevents that, matching today's `mouseClicked` structure) — so `DropdownWidget` only needs to distinguish its own rows from "the rest of the sidebar," not "the rest of the screen." This satisfies UI-FR3 without `DropdownWidget` needing screen-wide bounds.
- 1.21.11 variant differs only in: `DrawContext` in place of `GuiGraphicsExtractor`, `Text` in place of `Component`/plain `String` (existing widgets already pass plain `String` to both idioms' `text(...)` calls per current code, so no `Text`/`Component` wrapping is actually needed inside `DropdownWidget` itself — confirm at implementation time against each idiom's exact `text(...)` overload), `MinecraftClient.getInstance()` in place of `Minecraft.getInstance()`.

## Files to modify

Per platform module (×3), `FriendSidebarWidget.java`:
- Add `import de.lazuli.ui.DropdownWidget;` (same package tier, Platform layer — no cross-feature import concern per spec's Architecture section).
- Add a `DropdownWidget joinPolicyDropdown` field, constructed once (constructor or lazily on first `renderNow()` — planning leans constructor, needs `facade.joinPolicy()` which is available at construction time) with the 3 `JoinPolicy` options mapped via `joinPolicyShortLabel`/`joinPolicyDescription`, and `onSelectionChanged` = `index -> facade.selectJoinPolicy(JoinPolicy.values()[index])` (index order Nobody/Friends/Everyone must match `JoinPolicy` enum declaration order — verify at implementation time; if enum order differs from display order, map explicitly rather than relying on ordinal).
- `listTopOffset(boolean expanded)`: change fixed `+ DROPDOWN_HEIGHT` to `+ (expanded ? currentDropdownHeightThisFrame : 0)` — requires caching the `DropdownWidget.render(...)` return value from earlier in the same `renderNow()` call (mirrors the existing "instance-scoped, per-frame-consistent" discipline the class doc-comment already describes for `expanded`). Simplest approach: a new instance field `lastDropdownHeight`, updated inside the `dropdownVisible` branch, defaulting to `DROPDOWN_HEIGHT` (closed-row height) before first render / while not visible.
- `renderNow()` lines 327-342: replace `drawJoinPolicyControl(...)` call with `lastDropdownHeight = joinPolicyDropdown.render(guiGraphics, dropdownX, dropdownY, dropdownWidth, mouseX, mouseY);`. In the `!steamAvailable` early-return branch (line 317), add `joinPolicyDropdown.close();` before/alongside `dropdownVisible = false;` (v1.4-FR7.6a).
- Delete `drawJoinPolicyControl(...)` (417-433). Keep `joinPolicyShortLabel`/`joinPolicyDescription` (now called once at dropdown-construction time and, if `JoinPolicy` values can change out from under an already-constructed `DropdownWidget` — they don't; policy changes flow back through `onSelectionChanged`, not external mutation — no re-construction needed).
- `mouseClicked()` lines 502-506: replace the direct `dropdownVisible && ... facade.cycleJoinPolicy()` block with `if (dropdownVisible && joinPolicyDropdown.mouseClicked(event.x(), event.y(), event.button(), dropdownX, dropdownY, dropdownWidth)) { return true; }` — falls through to existing own-profile-row/friend-row logic unchanged if not consumed.
- No change needed to `mouseScrolled()` (v1.4-FR7.15 explicitly leaves this an implementation-level call; plan default: leave scroll-while-open behavior as-is, i.e. scrolling still scrolls the friend list even while the dropdown is open — simplest, zero new code; note this in verification as an accepted UX choice, not a bug).

`features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsSidebarFacade.java`:
- Add `public void selectJoinPolicy(JoinPolicy policy) { joinPolicy = policy; joinPolicyWriter.accept(policy); }` alongside existing `cycleJoinPolicy()`.

## Sequencing

1. **Phase A — DropdownWidget (platform/ui spec).** Implement `platform/fabric-26.2/.../ui/DropdownWidget.java` first (Mojang-mapped idiom, matches the module read most recently), then `fabric-26.1` (should be a near-identical copy, per confirmed idiom match), then `fabric-1.21.11` (Yarn-mapped port). Compile each module independently before moving to Phase B (`DropdownWidget` has no dependency on `FriendSidebarWidget`, so it can be built/verified in isolation first).
2. **Phase B — Friends Sidebar integration.** Add `FriendsSidebarFacade.selectJoinPolicy(...)` first (small, testable in isolation, no Minecraft classes). Then edit `FriendSidebarWidget.java` in all three modules, one module at a time, each followed by a compile check, per the Shared-file risk note in the spec (`git diff` before/after each module's edit, since this file is called out as actively-evolving).
3. Do not touch `steam-world-hosting`, `SteamWorldHostingClientInitializer`, `HostGateway`, or the config schema — all unchanged per spec Non-goals.

## Risks

- **R1 — enum-order vs. display-order mismatch.** If `JoinPolicy`'s declared enum order isn't Nobody/Friends/Everyone, mapping `DropdownWidget`'s selected index back via `.values()[index]` silently selects the wrong policy. Mitigation: verify `JoinPolicy.java`'s declaration order at implementation time before wiring the callback; use an explicit `JoinPolicy[]` array literal in the requested display order if it differs from declaration order, rather than relying on `.values()`.
- **R2 — `listTopOffset()` frame-consistency regression.** The existing per-frame-consistent guarantee (spec's Existing Implementation citation, Risk 1 of the v1.3 plan) depends on `lastDropdownHeight` being written before any call site in the same `renderNow()` reads `listTopOffset()`. `refreshScreenSize()` (which calls `listTopOffset()` to compute `maxRows`) runs at the *top* of `renderNow()`, before the dropdown is rendered that frame — so it will read the *previous* frame's height, one frame stale. This is the same one-frame-stale characteristic the existing fixed-constant version doesn't have (constant is always correct). Mitigation: accept a one-frame lag (matches the width-animation's own established "no dependency on `common`... not per-tick-perfect" tolerance elsewhere in this file) — flag explicitly in code comment; do not attempt a same-frame two-pass fix, out of proportion for a lightweight amendment.
- **R3 — scissor/overlap (v1.4-FR7.14).** If Risk 2's one-frame lag means `listTopOffset()` under-reports height on the exact frame the dropdown opens, the friend list's scissor top (line 349) could be one frame too high, causing a single-frame visual overlap between the newly-opened option list and the topmost friend row. Mitigation: draw order already has the dropdown call (line 337) before the scissor/friend-list block (349+) in the same frame, so the dropdown paints first and the friend list's scissor (even if stale) still clips the friend list from drawing over the dropdown — the more likely direction of failure (friend list drawn over dropdown) is not possible given draw order; the less severe direction (dropdown briefly extends over friend-list area for one frame before scissor catches up) is acceptable and matches the spec's own "drawn on top of, not reflowing" framing for the open state.
- **R4 — shared-file conflict.** `FriendSidebarWidget.java` is called out (spec Compatibility) as actively-evolving; re-run `git status`/`git diff` immediately before editing each module's copy, per the standing convention from `implementation-plan-v1.3-join-policy.md`.
- **R5 — 1.21.11 API surface not directly `javap`-confirmed this pass.** This planning pass did not re-verify exact `DrawContext`/`Click`/`Text` method signatures beyond the grep-confirmed import list; implementation must confirm exact `fill`/`text` overloads on `DrawContext` match the calling convention already used elsewhere in that module's own `FriendSidebarWidget.java` (copy the existing idiom rather than inventing a new one).

## Dependencies

None — no new external (non-Fabric) library. `DropdownWidget` uses only Minecraft client classes already present in each platform module's existing dependency set (same classes `FriendSidebarWidget` already imports) plus `java.util.List`/`java.util.function.IntConsumer`, both JDK-standard. No Maven Central lookup required.

## Test strategy

- **Unit-testable portion:** `FriendsSidebarFacade.selectJoinPolicy(...)` — plain-JVM, add a test alongside existing `FriendsSidebarFacadeTest` coverage of `cycleJoinPolicy()` (mirrors that existing test's shape: construct facade with a captured writer callback, call `selectJoinPolicy(EVERYONE)`, assert `joinPolicy()` returns `EVERYONE` and the writer was invoked with `EVERYONE`).
- **Not unit-testable (rendering/click-hit-testing):** `DropdownWidget` itself and its `FriendSidebarWidget` integration — per spec's own UI section ("Manual, per-supported-version in-game verification is required... not unit-testable") and `ui-guidelines.md`'s Testing section, consistent with every other rendering concern in this repo's existing custom widgets. Manual verification required on all three platform modules.
- **Manual verification checklist (per module ×3):**
  1. Closed-state row renders current selection's short label, same footprint as the old cycling control.
  2. Click closed row -> opens, shows 3 option rows in Nobody/Friends/Everyone order, each with hover-revealed description (including Everyone's Mojang-session mitigating note).
  3. Click an option row -> selects it, closes, label updates, config file (`config/friends-sidebar.json`) `joinPolicy` value updates on disk.
  4. Click closed row again while already open -> closes without changing selection.
  5. Click elsewhere in the sidebar (own-profile row, a friend row) while open -> closes without changing selection, and the underlying click still performs its normal action (open profile context menu / friend context menu) — confirms fallthrough works.
  6. Friend list beneath is not visually clipped/corrupted while the dropdown is open (v1.4-FR7.14) — scroll and observe no row bleed-through.
  7. Simulate Steam-unavailable transition (or restart without Steam running) while dropdown is open -> confirms it closes rather than rendering stale/open (v1.4-FR7.6a).
  8. Repeat all of the above in both a `handleOnly=true` embedding (e.g. in-game pause menu) and `handleOnly=false` embedding (title screen), per the existing dual-mode caveat.
- **Compile/static verification:** each of the three platform modules must compile cleanly after Phase A and after Phase B edits (Gradle build per module, or IDE compile check) before moving to the next module, per Sequencing.

## Acceptance criteria

- `de.lazuli.ui.DropdownWidget` exists, identically structured, in all three platform modules (UI-FR7), satisfying UI-FR1-UI-FR6 as implemented (open/closed state, click-to-open, click-option-to-select, close-on-outside-click, close-on-toggle-of-closed-row-while-open, zero feature-type import).
- `FriendSidebarWidget`'s join-policy control is backed by `DropdownWidget` in all three modules; `drawJoinPolicyControl` is removed; `listTopOffset()` reflects the dropdown's actual per-frame height.
- v1.4-FR7.3, FR7.6a, FR7.14 all observably satisfied per the manual verification checklist above.
- FR7.1/FR7.2/FR7.4/FR7.5/FR7.7-FR7.13/NFR8/NFR9 (all unchanged by v1.4) remain true — no config schema change, no enforcement-path change, no new Feature→Feature import.
- `FriendsSidebarFacade.selectJoinPolicy(...)` unit test passes; existing `cycleJoinPolicy()` test (if any) still passes unmodified (method retained, not removed).
- All three platform modules compile and the manual verification checklist passes on at least one module per Minecraft-version family before considering the amendment complete (matches this repo's existing "manual, per-supported-version" precedent).
