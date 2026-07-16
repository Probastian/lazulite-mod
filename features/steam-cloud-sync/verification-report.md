# Verification Report — Steam Cloud Sync

## Resolution (post-verification fix)
The FR0.4 deviation flagged below was fixed rather than accepted as a trade-off:
- `CloudSyncable` (`api/src/main/java/de/lazuli/api/cloudsync/CloudSyncable.java`) gained a fourth method, `long localLastModifiedMillis()`, so a Feature-authored adapter reports its own local timestamp without leaking a `Path` across the `api` boundary.
- A new `CloudSyncableReconciler` (`features/steam-cloud-sync/.../services/CloudSyncableReconciler.java`) applies the same timestamp-based last-write-wins comparison `LocalCloudFileReconciler` already applies to Groups 3-5, now for Group 1 too. `CloudSyncCoordinator.reconcileAtStartup()`/`syncOnShutdown()` were updated to use it instead of unconditional import/export.
- `HelloWorldMainMenuCloudSyncAdapter` (all three platform modules) now implements `localLastModifiedMillis()` against its config file's real last-modified time.
- A new `CloudSyncableReconcilerTest` (8 cases) directly exercises the "Steam available" reconciliation algorithm with a fake `CloudFileStore` — closing the test-coverage gap this report identifies below (previously only the Steam-*unavailable* no-op path was tested).
- Full rebuild and test suite reconfirmed after the fix: `./gradlew build` — BUILD SUCCESSFUL; `:features:steam-cloud-sync:test` — 88/88 passing (up from 80, the 8 new cases).

The rest of this report is preserved as originally written, for reference.

## Summary
The implementation is substantially complete, well-tested, and largely faithful to both the specification and the plan. All six requirement groups have corresponding code, the build succeeds, and 80/80 tests pass. One item — the implementer's self-reported FR0.4 deviation for Group 1 — deserves more scrutiny than a rubber stamp, and verification found an additional test-coverage gap tied to it that the implementer did not surface.

## Self-reported claims — verified

**1. Mixin targets (javap findings).** Confirmed exactly as claimed by reading `platform/fabric-26.2/src/main/java/de/lazuli/mixin/WorldSelectionListInvokerMixin.java:1-68` (identical in fabric-26.1) and `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/WorldListWidgetInvokerMixin.java:1-83`. The 26.x hierarchy (`WorldSelectionList extends ObjectSelectionList<Entry> extends AbstractSelectionList<E>`), the 1.21.11 hierarchy (`WorldListWidget extends AlwaysSelectedEntryListWidget<Entry> extends EntryListWidget<E>`), the protected `addEntry`/`clearEntries`, and the protected-`Entry`-type workaround (declaring the `@Invoker` parameter as each list's own public concrete `Entry` subtype) are all present in the actual source and documented consistently in `.claude/context/minecraft.md:60-62`.

**2. 1.21.11 remapJar warning.** A forced (non-cached) `:platform:fabric-1.21.11:remapJar` run printed the exact warning verbatim: `Cannot remap addEntry because it does not exist in any of the targets [net/minecraft/client/gui/widget/EntryListWidget] or their parents.` — build still succeeded. Documented in `.claude/context/minecraft.md:63` with an honest "unverified at runtime" flag. The severity framing (warning not failure, runtime behavior unconfirmed, top-priority flag for manual verification) is reasonable and not overstated.

**4. NFR1 compliance.** Verified independently via grep: zero real `net.minecraft.*` imports in `features/steam-cloud-sync/src/main` (only prose mentions inside permitted `package-info.java` files); exactly two files import `com.codedisaster.steamworks.*` (`SteamRemoteStorageCloudFileStore.java`, `SteamRemoteStorageWorldArchiveStore.java`); `fileDelete` never appears as an actual call anywhere in the module — bookmark/note removal rewrites the whole Cloud file via `write`, never calls delete (FR0.6).

**5. Build/test claims.** Re-ran `./gradlew build` — `BUILD SUCCESSFUL`. Force-reran `:features:steam-cloud-sync:test` and summed the JUnit XML reports: **80 tests, 0 failures, 0 errors**.

**6. Architectural compliance (ADR-0003).** `features/steam-cloud-sync/build.gradle` depends only on `:api` and `:services`, no dependency on `:features:hello-world-main-menu`. All three `SteamCloudSyncClientInitializer.java` files contain the `private static final class HelloWorldMainMenuCloudSyncAdapter implements CloudSyncable` nested class per Decision 2. All three `fabric.mod.json` files correctly list `de.lazuli.SteamCloudSyncClientInitializer` as the third `"client"` entrypoint entry, after `SteamworksClientInitializer`. `docs/adr/0003-...md` is present and coherent.

**7. Requirement groups spot-check.** FR6.3-FR6.5 (size/strategy decision), FR6.6-FR6.7 (fingerprint-conflict warning, quota/`fileForget` LRU eviction), FR6.10-FR6.13 (same-slug collision check, staging-directory + atomic move, cleanup on failure, plus a zip-slip guard beyond what the spec asked for) all check out against actual code, not just file names.

## Item requiring real scrutiny — FR0.4 deviation for Group 1

`LocalCloudFileReconciler.java:49-72` implements genuine timestamp-based last-write-wins for Groups 3-5 (and the Group 6 fingerprint file) exactly per FR0.4. `CloudSyncCoordinator.java:25-45` instead documents that `CloudSyncable` (Group 1) "adopts Cloud state unconditionally at startup" and "pushes unconditionally at shutdown," justified by the interface (the spec's own 3-method shape) exposing no local-timestamp signal.

This is a real, material deviation, not cosmetic:
- The javadoc's safety argument ("no in-session local edit could have happened yet" at startup) doesn't cover the scenario FR0.5 explicitly guards against: if a *previous* session ran with Steam unavailable, or crashed before reaching the shutdown checkpoint, and the local hello-world-main-menu config was edited during that gap, the next session's startup will blindly overwrite that never-synced local edit with stale Cloud data — the exact silent data loss FR0.4/FR0.5 exist to prevent.
- A less-lossy alternative was available without leaking a `Path` across the `api` boundary (e.g. adding `long localLastModifiedMillis()` to `CloudSyncable`), and doesn't appear to have been considered or rejected explicitly.
- This wasn't raised as a planning-time Decision or Risk (`implementation-plan.md`'s Decisions 1-10, Risks 1-9) despite being a real fork from the spec's stated general rule — it only appears as a code comment written during implementation.
- **New finding, not in the implementer's self-report:** `CloudSyncCoordinatorTest.java` only exercises the Steam-*unavailable* path (three tests, all against `NoopCloudFileStore`). Because `CloudSyncCoordinator`'s only constructor internally decides real-vs-noop store construction from a boolean with no injection seam, the "available" Group-1 reconciliation behavior described in its own javadoc is **never exercised by any automated test** — asserted only as a comment. This falls short of the plan's own Acceptance Criteria wording ("a fake `CloudSyncable` registered into `CloudSyncCoordinator` is reconciled per FR0.4 at a simulated startup/shutdown").

Given the actual data category (a small UI-preference config, not world saves), the blast radius is low, and the trade-off is documented rather than silent. Recommendation: don't treat this as settled — either explicitly accept it as a deliberate FR0.4 carve-out for Group 1 (updating spec/plan to record it), or extend `CloudSyncable` with a lightweight timestamp signal and add a real reconciliation test for the Steam-available path.

## Not independently verifiable here
In-game behavior (bookmark toggle rendering, restore progress bar, sync-toggle icon visuals) and real obfuscated-client runtime resolution of the 1.21.11 `addEntry` invoker — both require a real Steam/Minecraft client, unavailable in this environment.
