# Implementation Plan — Keep Sidebar Expanded While Context Menu or Dropdown Is Open

## Implements
`features/friends-sidebar/specification-keep-expanded-on-open-menu.md` (v1.7 amendment), requirements FR1-FR16 and the corresponding Public API / Architecture / Compatibility deltas. All requirement numbers below refer to that spec; this plan does not re-derive root cause or desired behavior, only sequences and scopes the edits.

## Hard Sequencing Precondition (read first)
**Do not begin any work under this plan until `features/friends-sidebar/implementation-plan-dropdown-polish.md` (v1.5, "DropdownWidget polish") is committed.** As of this writing that plan's changes are uncommitted working-tree modifications to exactly the same files this amendment touches:
- `api/src/main/java/de/lazuli/api/friends/FriendsSidebarZOrder.java`
- `platform/fabric-1.21.11/.../friends/FabricFriendsSidebarInjector.java`, `.../friends/FriendSidebarWidget.java`, `.../ui/DropdownWidget.java`
- `platform/fabric-26.1/.../friends/FabricFriendsSidebarInjector.java`, `.../friends/FriendSidebarWidget.java`, `.../ui/DropdownWidget.java`
- `platform/fabric-26.2/.../friends/FabricFriendsSidebarInjector.java`, `.../friends/FriendSidebarWidget.java`, `.../ui/DropdownWidget.java`

This amendment's FR3/FR5 depend directly on `joinPolicyDropdown.isOpen()` already being called inside `renderNow(...)` (introduced/relocated by the polish work), and both amendments edit the same `renderNow(...)` method body and the same injector methods. Implementing concurrently or before commit risks either a merge conflict or building against a pre-polish file shape that no longer matches HEAD.

**At the moment implementation actually starts, before touching any file:**
1. Run `git status` and `git log -1 -- features/friends-sidebar/implementation-plan-dropdown-polish.md`-equivalent check (i.e. confirm the polish work's file changes are committed, not just present in the working tree) — if `FriendSidebarWidget.java`/`FabricFriendsSidebarInjector.java`/`DropdownWidget.java`/`FriendsSidebarZOrder.java` still show as modified/untracked in `git status` on the relevant branch, stop and re-confirm with the user before proceeding.
2. Re-open each of the four shared files (per platform module, plus the single `FriendsSidebarZOrder.java`) fresh and re-locate the anchors below **by method/field name, not by the line numbers cited in the spec or in this plan** — the polish work is very likely to have shifted exact line numbers even though it doesn't change these methods' overall shape. Line numbers below (carried over from the spec, itself carried over from a pre-commit working-tree snapshot) are illustrative orientation only, not a byte-for-byte target.
3. If any anchor described below (method name, field name, call shape) no longer exists or has materially changed shape (not just line-shifted), stop and re-derive the correct anchor from the actual committed file before editing — do not force the old shape onto the new file.

## Existing Implementation (per spec, re-stated only where this plan's sequencing needs it)
- `FriendSidebarStateMachine.isExpanded(...)` — pure hover-bounds check, untouched by this amendment (FR1).
- `FriendSidebarWidget.renderNow(...)` — per-platform, structurally identical across all three modules; contains a coyote-time block computing `boolean hovering = overPanel || overHandle;`, resetting `lastHoverNanos` when `hovering`, and collapsing (`expanded = false`, plus `panelOpen = false` when handle-only) once `(now - lastHoverNanos) >= COYOTE_NANOS` (FR2). This block sits a few lines above the polish work's own `dropdownVisible`/`joinPolicyDropdown.render(...)` edits in the same method — anchor on the `hovering` local variable declaration and the `overPanel`/`overHandle` computation immediately preceding it, not on absolute line numbers.
- `joinPolicyDropdown.isOpen()` — already called inside `renderNow(...)` post-polish (FR3); reusable as-is, no new method needed for the dropdown side.
- `FabricFriendsSidebarInjector` — owns `openMenu`/`openMenuScreen` fields and `openContextMenu(...)`/`closeMenu()` methods; `FriendSidebarWidget` has no visibility into context-menu open/closed state today (FR4) — this is the one genuinely new piece of plumbing.
- `activeSidebar` field on the injector already exists and already receives the `FriendSidebarWidget` instance constructed for the current screen; `closeMenu()` is already called unconditionally at the top of `openContextMenu(...)` (existing reconstruct-flow, FR14) and from the existing outside-click (`onBeforeMouseClick`) and Escape (`onAllowKeyPress`) dismissal paths (FR11/Events section).
- No existing test touches this path; `FriendSidebarStateMachineTest` covers only the untouched `isExpanded` pure-function logic.

## Files to Create
None.

## Files to Modify
Per platform module (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`), identical logical change in each of two files:

1. **`platform/<module>/src/main/java/de/lazuli/friends/FriendSidebarWidget.java`**
   - Add new private field `private boolean contextMenuOpen;` (Public API item 1).
   - Add new public method `void notifyContextMenuOpenChanged(boolean open)` that sets `this.contextMenuOpen = open;` (Public API item 1). No side effects beyond the field write — the method must not itself touch `lastHoverNanos`/`expanded` (those are read/written only inside `renderNow(...)`'s own per-frame pass, per FR9/FR11's "no extra grace period" requirement).
   - In `renderNow(...)`, change the existing `boolean hovering = overPanel || overHandle;` line to `boolean hovering = overPanel || overHandle || contextMenuOpen || joinPolicyDropdown.isOpen();` (Public API item 2, exact four-way `||` per FR5/FR6/FR13). No other line in the coyote-time block changes — `lastHoverNanos`/`expanded`/`panelOpen` logic downstream of `hovering` is untouched.
   - Locate this edit by finding the `hovering` local variable inside `renderNow(...)`, not by the spec's/polish-plan's cited line numbers (see Sequencing Precondition, above).

2. **`platform/<module>/src/main/java/de/lazuli/friends/FabricFriendsSidebarInjector.java`**
   - In `openContextMenu(...)`: after the menu is actually constructed/assigned to `openMenu` (i.e. once the method has committed to opening a menu, not on the unconditional `closeMenu()` call at the top of the method), add `if (activeSidebar != null) { activeSidebar.notifyContextMenuOpenChanged(true); }` (Public API item 3), following this class's existing null-guard convention.
   - In `closeMenu()`: add `if (activeSidebar != null) { activeSidebar.notifyContextMenuOpenChanged(false); }` (Public API item 3), placed so it fires on every path that closes the menu (unconditional call at top of `openContextMenu(...)` before opening a new one, outside-click dismissal, Escape dismissal) — i.e. inside `closeMenu()` itself, not duplicated at each call site, so all three dismissal paths (FR11, FR14) get the notification for free.
   - No change to `openMenu`/`openMenuScreen`/`activeSidebar`/`activeSidebarScreen` field semantics (Public API item 3, last sentence).
   - Locate `openContextMenu(...)`/`closeMenu()` by method name; confirm at implementation time whether `closeMenu()` already correctly handles the "no menu currently open" (idempotent) case — calling `notifyContextMenuOpenChanged(false)` when no menu was open is harmless (FR9) but confirm `activeSidebar` can't be stale/null-unsafe in that path.

No changes to `FriendSidebarStateMachine.java`, `DropdownWidget.java`, or `FriendsSidebarZOrder.java` (Non-goals; these files are named only because they are shared/actively-evolving, not because this amendment edits them).

## Order / Dependencies of Changes
1. Confirm dropdown-polish (v1.5) is committed (Sequencing Precondition) — blocking prerequisite for all steps below.
2. Re-derive current anchors in each of the six touched files (three platforms x two files) per the committed post-polish shape.
3. Per platform module, `FriendSidebarWidget.java` first (new field + method + `hovering` expression) — self-contained, compiles independently of the injector change.
4. Per platform module, `FabricFriendsSidebarInjector.java` second — depends on step 3's `notifyContextMenuOpenChanged(boolean)` existing on that platform's `FriendSidebarWidget` before the injector can call it (compile dependency).
5. Repeat 3-4 identically across all three platform modules (structural-twin requirement, FR10).

## Risks
1. **Line-number drift from dropdown-polish landing first (expected, not hypothetical).** The spec's own line citations (e.g. `renderNow`'s coyote block at "lines 330-347", `joinPolicyDropdown.isOpen()` at "line 458") are pinned to a specific uncommitted working-tree snapshot taken before this plan was written. By the time this amendment is implemented, the polish work will be committed (possibly with further tweaks from its own verification pass) and these exact numbers will very likely no longer match. Mitigation: this plan's anchors are named by method/field identity (`hovering` local in `renderNow`, `openContextMenu`/`closeMenu` methods, `contextMenuOpen` new field) rather than line numbers; re-locate by name at implementation time per the Sequencing Precondition's step 2.
2. **Placement of the "menu opened" notification inside `openContextMenu(...)`.** That method's existing unconditional `closeMenu()` call at its top means a naive "call `notifyContextMenuOpenChanged(true)` at method entry" would fire the true-notification before the new menu is actually constructed/assigned. Mitigation: place the `true` call after `openMenu` is assigned (end of method / after successful construction), not at entry — call out explicitly in verification that a rapid menu-to-different-friend-menu switch never produces a spurious one-frame "closed" flicker in the sidebar's `hovering` state (FR14 requires this to be gap-free within the same frame).
3. **`closeMenu()` being called from multiple dismissal paths (outside-click, Escape, reconstruct-flow) means `notifyContextMenuOpenChanged(false)` will fire from each.** This is intended (FR9/FR11) but implementers should confirm `activeSidebar` is still valid (non-null, matching the currently-active screen) at each of those call sites — same null-guard convention as the rest of the class, not a new risk, but worth a explicit double-check since this is new plumbing added inside an existing, frequently-invoked method.
4. **Dropdown-polish's own verification/implementation pass could still be mid-flight or produce follow-up fixup commits after "committed" but before this amendment starts.** Treat "committed" as "committed and this amendment's own implementer re-runs `git status`/`git log` immediately before editing," not as a one-time check done at planning time (Compatibility, spec Sequencing dependency section).
5. **No automated test coverage for this path**, consistent with existing convention for `FriendSidebarWidget`/injector rendering-and-input logic (`FriendSidebarStateMachineTest` is unaffected and untouched). Manual in-game verification is the only practical safety net — see Test Strategy.

## Dependencies
No new external (non-Fabric) dependencies. All changes use existing fields/methods already on the classpath of each platform module; no `build.gradle` change on any module.

## Test Strategy
- No new automated/unit tests: `FriendSidebarStateMachine` (the only plain-JVM-testable layer touched by this feature area) is explicitly unchanged (FR1, Non-goals) — `FriendSidebarStateMachineTest` needs no update and should only be re-run as a regression sanity check.
- **Manual in-game verification required, per platform module (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`)**, covering:
  1. Open the join-policy dropdown (host a world so the control is visible), move the mouse fully off the sidebar's hover bounds onto the open option list, wait past `COYOTE_NANOS` (250ms) plus a margin — confirm the sidebar stays expanded the whole time (FR5).
  2. Right-click a friend row to open its context menu, move the mouse off the sidebar's hover bounds onto the menu, wait past the coyote window — confirm the sidebar stays expanded (FR6).
  3. Dismiss the context menu (outside click, then separately Escape, then separately selecting an action) while the mouse remains off the sidebar's hover bounds — confirm collapse begins exactly one coyote-time window after dismissal, not immediately and not with a longer grace period (FR11).
  4. Dismiss the context menu/dropdown while the mouse has already moved back over the sidebar's own hover bounds — confirm no behavior change from today (FR12).
  5. Right-click one friend row to open its menu, then (without re-entering the sidebar's hover bounds) right-click a different friend row — confirm the sidebar never collapses during the reconstruct (close-old/open-new) transition (FR14).
  6. If reachable in practice, transition from context-menu-open directly to dropdown-open (or vice versa) without the mouse re-entering the sidebar's hover bounds — confirm no collapse gap even if the two states briefly overlap or hand off within the same frame (FR13).
  7. While the dropdown is open, trigger Steam becoming unavailable (existing `!steamAvailable` force-close path) — confirm the dropdown's forced `close()` correctly stops suppressing collapse from that frame onward, with normal coyote-time behavior resuming (FR16).
  8. Confirm this holds identically on all three platform modules (FR10).

## Acceptance Criteria
- FR5: `joinPolicyDropdown.isOpen() == true` prevents the sidebar's coyote timer from ever expiring, on all three platforms.
- FR6: an open context menu (tracked via the new `contextMenuOpen` field, set by `notifyContextMenuOpenChanged`) prevents the sidebar's coyote timer from ever expiring, on all three platforms.
- FR7/FR8: `FabricFriendsSidebarInjector` notifies `activeSidebar` on every context-menu open/close transition, regardless of which friend row (or own-profile row) the menu is for.
- FR9/FR11: once both the dropdown and context menu are closed, and the mouse is off the sidebar's hover bounds, normal `COYOTE_NANOS`-based collapse resumes with no added or removed grace period.
- FR12/FR13/FR14: no false collapse gap in the edge cases specified (dismiss-while-hovering, menu-to-menu switch, menu/dropdown handoff).
- FR16: the existing `joinPolicyDropdown.close()` call on Steam-unavailable naturally stops FR5 from applying, no additional handling required.
- Public API items 1-3 land identically (same field/method name and shape) across `fabric-1.21.11`, `fabric-26.1`, `fabric-26.2` (FR10).
- No change to `isExpanded(...)`'s signature, `COYOTE_NANOS`, `FriendsSidebarZOrder`, or `DropdownWidget`'s open/close semantics (Non-goals).
- Manual in-game verification (Test Strategy, above) passes on all three platform modules.
