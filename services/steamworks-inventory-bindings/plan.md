# Implementation Plan — Steamworks Inventory/Microtransaction Bindings Fork

## Summary
Fork `code-disaster/steamworks4j` to add a real `ISteamInventory` Java/JNI
binding pair plus one additive `SteamUser`/`SteamUserCallback` callback
(`onMicroTxnAuthorizationResponse`), rebuild native glue for all four of
upstream's existing platform targets, publish the fork jar, and repoint this
mod's Gradle dependency graph at it. This is standalone infrastructure work in
an external fork repository, not a Minecraft feature — no gateway class, no
Store panel UI, no publisher-backend `ISteamMicroTxn` work (specification
Non-goals). No implementation code is written as part of this plan.

Two facts dominate sequencing and cannot be relaxed by this plan (spec Open
Questions 1–2, restated here as this plan's Risks 1–2): (a) the user does not
yet hold Steamworks partner/SDK access and must purchase it before any
native-glue or native-build task can start; (b) Linux/macOS toolchain
availability is unconfirmed even though all four native targets (Windows x64,
Linux x64, macOS x86_64, macOS arm64 — spec Open Question 3) are in scope.
Every task below that touches C++/native code is marked **[SDK-GATED]** to
make this dependency structurally visible in the task list itself, not just
narratively.

## Existing Implementation
- **This mod's current Steamworks dependency**: `gradle.properties:44-47`
  pins `steamworks4j_version=1.10.0`, resolved from Maven Central
  (`com.code-disaster.steamworks4j:steamworks4j:1.10.0`, MIT license,
  confirmed live at `https://central.sonatype.com/artifact/com.code-disaster.steamworks4j/steamworks4j/1.10.0`
  per the existing comment). Consumed two ways, both needing a coordinate
  swap once the fork jar exists:
  - `services/build.gradle:8` — `api "com.code-disaster.steamworks4j:steamworks4j:${steamworks4j_version}"` (exposed as `api` because `SteamworksService`'s public surface leaks steamworks4j types, e.g. `SteamException`).
  - Jar-in-Jar `include` line, identical shape in all three platform modules:
    `platform/fabric-26.2/build.gradle:35`, `platform/fabric-26.1/build.gradle:35`,
    `platform/fabric-1.21.11/build.gradle:36` —
    `include "com.code-disaster.steamworks4j:steamworks4j:${steamworks4j_version}"`.
  - `gradle.properties:1-13` also documents a load-bearing constraint this
    plan must not violate: `org.gradle.parallel=false`, specifically because
    Loom's `processIncludeJars` (the Jar-in-Jar mechanism bundling
    steamworks4j today) races on a shared NIO zip filesystem under parallel
    project execution. The fork's jar must keep the same "one jar, one native
    resource layout per OS/arch" shape upstream's jar already has, or this
    existing constraint's rationale may need re-verification at
    implementation time (not a plan-blocking risk, but a compatibility note
    to carry forward).
- **This mod's own steamworks-consuming code is unaffected by this
  initiative's scope**: `services/src/main/java/de/lazuli/services/steamworks/`
  (e.g. `SteamworksSteamFriendsGateway.java`, `SteamworksService.java`) is not
  modified by this plan — FR6/Goals require the fork to be a byte-for-byte
  drop-in for every interface this mod already uses (Friends, RemoteStorage,
  UGC, Matchmaking, Apps, Utils, User's existing surface), and no new
  `SteamInventory`-consuming gateway is built here (spec Non-goals) — that is
  explicitly Future Extension / the next initiative's job.
  `SteamworksSteamFriendsGateway.java:361-362`'s existing no-op
  `UserCallback` implementer is noted only as later-relevant context for
  wherever a future gateway wires up `onMicroTxnAuthorizationResponse` —
  this plan does not touch that file.
- **Upstream fork target layout** (per spec Architecture, accepted as
  correctly researched, not re-verified independently by this plan):
  `java-wrapper/src/main/java/com/codedisaster/steamworks/` (new/changed Java
  files), `jnigen/` (JNI glue generator, extended for `SteamInventoryNative`),
  `build-natives/` (premake-based per-OS native build scripts, run unchanged),
  `loader/` (native library loading, no changes needed — no new native
  library *file*, only new exported symbols in the existing per-OS bridge
  library).
- **`.github/workflows/build.yml`**: Ubuntu-only (`ubuntu-24.04`), builds only
  this mod's own Gradle project by resolving the already-published
  steamworks4j jar as an ordinary dependency — it does not build steamworks4j
  from source today and will not need to build the fork from source either;
  the only CI-relevant change this initiative produces is the dependency
  coordinate swap once the fork jar is published (spec Native Build/CI
  Implications). No CI file changes are planned beyond that swap, itself
  covered under Files to Modify below.
- **Existing test precedent for steamworks4j-adjacent Java-only logic**:
  `services/src/test/java/de/lazuli/services/steamworks/SteamNativeLibraryNamesTest.java`
  and `SteamworksServiceTest.java` already demonstrate this repo's pattern
  for testing steamworks4j-adjacent logic without a live Steam client —
  cited here as the precedent this initiative's own (external-repo) unit
  tests should mirror in spirit, though those tests themselves live in the
  fork repository, not this repo (see Test Strategy).

## Decisions on Items the Specification Left to Planning

### 1. Fork repository and remote setup
Fork `code-disaster/steamworks4j` on GitHub to `Probastian/steamworks4j` (per
spec Architecture's own illustrative naming), then locally add
`code-disaster/steamworks4j` as a git remote named `upstream`
(`git remote add upstream https://github.com/code-disaster/steamworks4j.git`)
to support future `git fetch upstream && git rebase upstream/master` (or
merge, left as a maintenance-process decision per spec Open Question 7 — not
resolved further by this plan). This is ordinary GitHub fork mechanics; no
further planning decision is needed here beyond naming.

### 2. `SteamUserCallback` addition mechanism — confirm before committing to `default` method
Spec Public API flags this as needing confirmation against upstream's actual
current source (interface supporting `default`, vs. abstract class needing a
different non-breaking-addition strategy) before committing. This plan
sequences that confirmation as an explicit early task (Task 3 below) that
must happen immediately after the fork exists and before any `SteamUser`
Java-side edit is written, precisely because the answer determines which of
two different edit shapes (interface `default` method vs. abstract-class
protected-with-default-body method) is correct. Both shapes are described so
implementation is not blocked regardless of which is true:
- **If `SteamUserCallback` is a Java `interface`** (spec's own expectation,
  matching `SteamworksSteamFriendsGateway.UserCallback`'s no-op
  implementer style): add `default void onMicroTxnAuthorizationResponse(int
  appId, long orderId, boolean authorized) {}` exactly as the spec's Public
  API illustrates — zero breakage to existing implementers.
- **If it is instead an abstract class**: add a concrete (non-abstract)
  method with an empty body directly on the class, achieving the same
  "existing subclasses don't have to override it" non-breaking property
  without needing `default` (which doesn't apply to classes). No other
  planning-level difference.

### 3. Fork distribution mechanism — commit prebuilt native binaries, publish via JitPack
Per spec Architecture's own recommended-pending-confirmation shape, this plan
adopts it as final: native binaries are built locally (by whoever has SDK +
toolchain access, per Task 1/Risk 2) and committed as jar resources into the
fork repository alongside source (mirroring how upstream itself ships
prebuilt binaries without requiring consumers to own the SDK); the fork's
Java jar is then published via JitPack (`https://jitpack.io`), which only
needs to compile the thin Java layer and package the already-committed native
resources — it never runs the SDK-gated native build itself, sidestepping the
blocker the spec identifies for JitPack specifically. Resulting Gradle
coordinate shape (JitPack's standard convention for a GitHub repo, exact tag
TBD once a release is cut): `com.github.Probastian:steamworks4j:<tag-or-commit>`,
resolved by adding JitPack's Maven repo (`maven { url 'https://jitpack.io' }`)
to this mod's repository list. **This is a fork-of-a-fork coordinate that
cannot be independently registry-verified today** (the artifact does not
exist until the fork publishes a tag) — unlike a genuine new third-party
Maven Central dependency, there is no existing registry entry to check
against; the exact tag string is confirmed at implementation time when the
first release is actually cut, and that confirmation step is carried in
Files to Modify/Sequencing below rather than guessed here.

## Files to Create / Modify (fork repository — external, not this mod's repo)
All paths below are relative to the fork repository root, created only after
Tasks 1–2 (gating prerequisites) are complete:
- `java-wrapper/src/main/java/com/codedisaster/steamworks/SteamInventory.java` — new public class (FR1, FR2), mirroring `SteamUGC.java`'s existing shape/size (spec Public API).
- `java-wrapper/src/main/java/com/codedisaster/steamworks/SteamInventoryNative.java` — new JNI-declaring class (FR1), mirrors `SteamUGCNative.java`'s convention.
- `java-wrapper/src/main/java/com/codedisaster/steamworks/SteamInventoryCallback.java` — new interface (`onResultReady`, `onStartPurchaseResult`, `onRequestPricesResult`, spec Public API).
- `java-wrapper/src/main/java/com/codedisaster/steamworks/SteamInventoryResult.java` — new opaque-handle POJO (mirrors `SteamUGCQuery`/`SteamPublishedFileId`-style existing handle-wrapper pattern per spec Architecture "Result-handle lifecycle note"); final shape (whether `AutoCloseable`) is an implementation-time decision informed by how existing handle-owning types are shaped, per spec Architecture.
- `java-wrapper/src/main/java/com/codedisaster/steamworks/SteamItemDetails.java` — new POJO (item def ID, item instance ID, quantity, flags, spec Public API).
- `java-wrapper/src/main/java/com/codedisaster/steamworks/SteamUserCallback.java` — modified, additive only (Decision 2): one new method, no existing method signature changed.
- `java-wrapper/src/main/java/com/codedisaster/steamworks/SteamUser.java` — modified: expose whatever accessor the `MicroTxnAuthorizationResponse_t` callback payload needs to reach `SteamUserCallback` (appId, orderId, authorized), mirroring how other existing `SteamUser` callback payloads are already surfaced.
- `java-wrapper/src/main/java/com/codedisaster/steamworks/SteamUserNative.java` — modified: register the new native callback dispatch, mirroring the existing pattern for other `SteamUser` callbacks.
- `jnigen/` glue definitions — extended to declare `SteamInventoryNative`'s methods and generate matching C++ stubs against Valve's real `isteaminventory.h`; also extended for the `MicroTxnAuthorizationResponse_t` callback registration against `isteamuser.h` (**[SDK-GATED]**).
- `build-natives/` — no new build scripts; existing premake-based Windows/MSVC, Linux/gcc, macOS/Xcode scripts re-run unchanged against the updated generated glue, once per target platform (**[SDK-GATED]**).
- Fork-repo-level `README.md`/`CONTRIBUTING`-equivalent — new short section documenting: (a) this fork's purpose/scope relative to upstream, (b) the `upstream` remote + rebase-cadence convention (Decision 1, deferred cadence specifics per spec Open Question 7), (c) how to obtain the Valve SDK and rebuild natives locally, (d) the JitPack publish-on-tag process (Decision 3).
- A new git tag/release once the above is complete, triggering JitPack's on-demand build (Decision 3).

## Files to Modify (this mod's own repository)
- `gradle.properties` — replace or supplement the `steamworks4j_version=1.10.0` line (currently `gradle.properties:44-47`) with the fork's published version/tag string, following the existing convention of a comment documenting *why* this pin was chosen (mirrors the current comment's own style); add a `maven { url 'https://jitpack.io' }` repository declaration wherever this project's existing repository list lives (root `build.gradle`, not yet inspected in detail by this plan — confirm exact location at implementation time).
- `services/build.gradle:8` — repoint `api "com.code-disaster.steamworks4j:steamworks4j:${steamworks4j_version}"` at the fork's JitPack coordinate (Decision 3), keeping the `api`-not-`implementation` rationale unchanged (steamworks4j types still leak through `SteamworksService`'s public surface).
- `platform/fabric-26.2/build.gradle:35`, `platform/fabric-26.1/build.gradle:35`, `platform/fabric-1.21.11/build.gradle:36` — each `include "com.code-disaster.steamworks4j:steamworks4j:${steamworks4j_version}"` line repointed at the same fork coordinate, keeping the Jar-in-Jar shape otherwise unchanged (FR7).
- `.github/workflows/build.yml` — no functional change expected (CI never built steamworks4j from source, spec Native Build/CI Implications); confirm at implementation time that the JitPack Maven repo is reachable from the Ubuntu CI runner (ordinary public internet access, no credential expected) and add the repository declaration alongside wherever the `gradle.properties` change above lands, if not already covered by a root-level repository block.
- No `settings.gradle` change expected (no new Gradle subproject is added by this initiative to this mod's own repo).

## Dependencies
- **New external dependency**: the fork's own published artifact via JitPack,
  `com.github.Probastian:steamworks4j:<tag>` (Decision 3). This is not a
  pre-existing, independently registry-verifiable coordinate today — it is
  the very artifact this initiative produces — so it cannot be checked
  against Maven Central or any other registry in advance the way an ordinary
  new third-party dependency would be. The exact tag is confirmed once the
  first fork release is cut (Sequencing Task 9); this plan records that as an
  explicit verification step rather than asserting a version number now.
- **No other new external dependency.** The existing `com.code-disaster.steamworks4j:steamworks4j:1.10.0` Maven Central coordinate (confirmed live at `https://central.sonatype.com/artifact/com.code-disaster.steamworks4j/steamworks4j/1.10.0`, per the existing `gradle.properties:44-47` comment) is retired, not supplemented — this initiative does not add both a fork and the original.
- **Hard non-Gradle dependency**: a Steamworks partner/developer account and
  the Valve Steamworks SDK download (`sdk/public/`, `sdk/redistributable_bin/`)
  — gates every native-glue/native-build task (Task 2 below); entirely outside
  this plan's or this repo's control (spec Open Question 1).
- **Hard non-Gradle dependency**: native toolchain access for all four target
  platforms — MSVC (Windows, available on the primary dev machine per this
  session's environment), gcc (Linux), Xcode (macOS x86_64 + arm64). Linux and
  macOS toolchain access is not yet confirmed to exist anywhere the user
  controls (spec Open Question 2) — gates Tasks 6b/6c/6d below specifically.

## Sequencing (order of execution)
1. **[GATING, no code]** Obtain Steamworks partner/developer account access
   (Valve's one-time partner fee) and download the Steamworks SDK
   (`sdk/public/`, `sdk/redistributable_bin/`) per Valve's own partner-portal
   process. **Nothing below that touches native code (Tasks 5–8) can start
   until this is done.** Purely the user's own action; not something this
   plan or any agent can perform or verify.
2. Fork `code-disaster/steamworks4j` → `Probastian/steamworks4j`; add
   `upstream` remote (Decision 1). Confirm the fork's base commit tracks
   upstream's current release, not this mod's currently-pinned 1.10.0 (spec
   Compatibility — the fork should track forward, not freeze at 1.10.0).
3. Read `SteamUserCallback.java`'s actual current source in the freshly
   forked repo to resolve Decision 2 (interface vs. abstract class) before
   writing the callback addition.
4. Write the new Java-side files (`SteamInventory.java`, `SteamInventoryNative.java`,
   `SteamInventoryCallback.java`, `SteamInventoryResult.java`,
   `SteamItemDetails.java`) mirroring `SteamUGC.java`'s shape, and the
   additive `SteamUserCallback`/`SteamUser.java`/`SteamUserNative.java` edits
   resolved by Task 3 — pure Java, does not itself require the SDK to write
   (though it will not compile against real native method bodies until Task
   5's JNI glue exists; stub/declare-only is expected at this stage).
5. **[SDK-GATED]** Extend `jnigen` to declare `SteamInventoryNative`'s methods
   and generate C++ glue against Valve's real `isteaminventory.h`; extend the
   existing `isteamuser.h`-facing glue for the new callback. Requires Task
   1's SDK.
6. **[SDK-GATED]** Rebuild natives per platform via the existing premake
   toolchain, one target at a time, each independently gated on that
   platform's toolchain being available:
   - 6a. Windows x64 (MSVC) — toolchain confirmed available on the primary
     dev machine per this session's environment; do this platform first.
   - 6b. Linux x64 (gcc) — toolchain availability not yet confirmed (Risk 2);
     may require acquiring a Linux build environment (VM, WSL with a full
     native toolchain, or a borrowed/cloud machine) before this step can
     start.
   - 6c. macOS x86_64 (Xcode) — toolchain availability not yet confirmed
     (Risk 2); may require acquiring access to a Mac (physical, VM, or
     cloud-hosted macOS CI runner) before this step can start.
   - 6d. macOS arm64 (Xcode) — same access requirement as 6c, likely doable
     from the same machine/access grant.
   - If 6b–6d's toolchain access cannot be resolved promptly, ship v1 with
     only the platforms actually built, and document the rest as an explicit,
     named gap (not silently absent) per Risk 2's mitigation — this is a
     scope-reduction fallback, not this plan's primary path.
7. Confirm all pre-existing wrapped interfaces (Friends, RemoteStorage, UGC,
   Matchmaking, Apps, Utils, User's existing surface) still build and behave
   identically after Tasks 4–6 (FR6) — a full rebuild of the fork's own
   existing test/example surface, not a new test suite.
8. Set up JitPack publishing for the fork repo (Decision 3): commit the
   prebuilt binaries from Task 6 as jar resources, verify JitPack's build log
   for a tagged commit succeeds (JitPack only compiles Java + packages
   already-committed resources — no SDK needed at this step).
9. Cut the first fork release/tag; confirm the exact resulting JitPack Maven
   coordinate resolves in a scratch Gradle project before touching this mod's
   own repo (closes the Decision 3 "coordinate not yet finalized" gap named
   above).
10. Repoint this mod's own repo at the new coordinate: `gradle.properties`,
    `services/build.gradle:8`, and all three platform `build.gradle` `include`
    lines (Files to Modify above); confirm `.github/workflows/build.yml`'s
    Ubuntu runner can resolve the JitPack repository.
11. Run this mod's own existing build/test suite (`gradlew build` across all
    three platform modules) to confirm the coordinate swap alone doesn't
    regress anything already working (FR6 from this mod's own side).
12. Run the manual JNI-glue smoke test (Test Strategy below) against Valve's
    public test App ID `480`.
13. Document the fork's rebase/update process (spec Goals) in the fork
    repo's own README (already drafted as part of Task 8's deliverables) and,
    in this mod's own repo, a short note (e.g. in `services/steamworks-inventory-bindings/`
    itself, alongside this plan) recording the final coordinate/version and a
    pointer to the fork repo for whoever picks up the consuming Store panel
    feature next.

## Risks
1. **Steamworks partner account/SDK acquisition timeline is entirely outside
   this plan's control and gates most other work** (Task 1; spec Open
   Question 1). No native-glue writing (Task 5), no native rebuild (Task 6),
   and therefore no publishable fork jar (Task 8) can happen before this
   completes. Mitigation: Tasks 2–4 (fork setup, Java-side stub files) can
   proceed in parallel/ahead of Task 1 finishing, so the SDK-gated wait isn't
   fully serial — but nothing past Task 4 can close out until Task 1 does.
2. **Native toolchain availability for Linux/macOS is unconfirmed** (Tasks
   6b–6d; spec Open Question 2), and the primary dev machine is Windows per
   this session's environment — meaning Linux and macOS builds will very
   likely require acquiring a build environment the user does not currently
   have readily available (a VM, a borrowed/cloud Mac, a Linux box or WSL
   with a full native toolchain installed, or a macOS/Linux CI runner).
   Mitigation: size this explicitly as its own acquisition task, not a
   sub-line-item of "rebuild natives"; if unresolved by the time Task 6
   starts, fall back to shipping a reduced platform set for v1 (documented
   gap, not silent omission) per Sequencing Task 6's fallback note, revisited
   later per spec Future Extensions.
3. **JitPack coordinate does not exist until a tag is cut** (Decision 3) —
   this plan cannot cite a verified version string the way a genuine
   third-party Maven Central dependency would be verified, because the
   artifact is this initiative's own output. Mitigation: Sequencing Task 9
   explicitly confirms the coordinate resolves in a scratch project before
   Task 10 touches this mod's real build files, so a bad/unresolvable
   coordinate is caught before it reaches this repo's own `gradle.properties`.
4. **No real Steam App ID with Inventory Service items exists yet** (spec
   Open Question 6) — verification in this pass is capped at proving the JNI
   glue itself is sound against Valve's public test App ID `480`, not genuine
   ownership/purchase behavior. This is an accepted, permanent limitation of
   this pass's "done," not a defect to work around (see Test Strategy /
   Acceptance Criteria).
5. **Upstream `SteamUserCallback`'s actual shape (interface vs. abstract
   class) is not yet confirmed** (Decision 2) — low risk, resolved as an
   explicit early task (Sequencing Task 3) before any dependent code is
   written, so it cannot silently produce a wrong non-breaking-addition
   mechanism.
6. **`NFR4` (license-compliant redistribution of prebuilt binaries) is a
   judgment call this plan does not fully resolve** — upstream itself does
   not distribute prebuilt binaries for its `steamworks4j-server`/
   `sdkencryptedappticket` modules for licensing reasons (spec NFR4). This
   plan's Decision 3 commits to distributing prebuilt `SteamInventory`-related
   binaries via the fork; whoever executes Task 1 (obtaining partner/SDK
   access) should re-read Valve's SDK redistribution terms at that time to
   confirm this is actually permitted before Task 8 publishes anything
   containing compiled SDK-derived binaries — flagged here as a concrete
   go/no-go check at Task 8, not assumed resolved by this plan.

## Test Strategy
Consistent with the specification's own stated real ceiling (Open Question 6,
Testing/Verification Strategy) — no genuine end-to-end ownership/purchase
verification is possible in this pass, because no real App ID with Inventory
Service items exists.

- **Unit-testable (fork repo, plain JVM, no native code)**: any pure
  Java-side glue logic beyond direct JNI passthrough introduced by
  `SteamInventory.java`/the `SteamUser` callback addition (e.g. native
  result-status int → Java enum conversion, POJO field mapping) — written and
  run inside the fork repository, mirroring this mod's own
  `SteamNativeLibraryNamesTest.java`/`SteamworksServiceTest.java` precedent
  for testing steamworks4j-adjacent logic without a live Steam client. This
  is the fork repo's own test suite, not something added to this mod's
  `services/src/test`.
- **Manual smoke test (requires the real SDK + a live Steam client, not
  automatable in CI)** — the recommended minimal fork-level verification
  before declaring this initiative ready (spec Testing/Verification
  Strategy): (a) initialize `SteamAPI` against test App ID `480`, (b)
  construct `SteamInventory`, (c) call `GetAllItems`, (d) confirm
  `SteamInventoryResultReady_t` fires on the next `runCallbacks()` pump and
  `GetResultItems` returns without a native crash. This proves the JNI glue
  itself is sound, independent of this mod's own build — it does not prove
  any real ownership/price/purchase behavior, since App ID `480` has no
  Inventory Service items configured.
- **Not attempted in this pass**: `StartPurchase`/`MicroTxnAuthorizationResponse_t`
  full purchase-flow verification, and any genuine ownership-check
  verification — both require a real App ID with configured Inventory
  Service items, which does not exist yet (Risk 4). Deferred until that
  becomes available, at which point the consuming Store panel feature's own
  spec/plan is the right place to verify it, not this fork-level pass.
- **This mod's own regression check**: after the coordinate swap (Sequencing
  Task 10), run `gradlew build` across all three platform modules
  (`fabric-26.2`, `fabric-26.1`, `fabric-1.21.11`) to confirm the fork jar is
  a drop-in replacement — no existing wrapped-interface behavior (Friends,
  RemoteStorage, UGC, Matchmaking, Apps, Utils) regresses. No new test file
  is added to this mod's own `services/src/test` by this initiative, since
  no new consuming code is added to this mod's repo (spec Non-goals).
- **No automated CI coverage of the native glue itself** is expected or
  proposed, consistent with `.github/workflows/build.yml`'s Ubuntu-only,
  dependency-resolving build having zero native-rebuild responsibility (spec
  Native Build/CI Implications) — unchanged by this plan.

## Final Status — Windows-only release shipped (2026-07-22)
**This initiative is complete for its Windows-only scope and is now wired into
the mod's build.** Fork: `github.com/Probastian/steamworks4j`, tag
`v1.10.0-inventory.1`, published via JitPack
(`com.github.Probastian.steamworks4j:steamworks4j:v1.10.0-inventory.1`). This
mod's `gradle.properties` (`steamworks4j_version`), `build.gradle` (JitPack
repo added), `services/build.gradle`, and all three platform `build.gradle`s
are repointed at this coordinate. Full `./gradlew build` succeeds across all
three platform modules with no regression; the built mod jars correctly bundle
the new `SteamInventory*` classes and rebuilt `steamworks4j64.dll` (verified by
inspecting `platform/fabric-26.2/build/libs/*.jar`'s nested jar contents). The
manual JNI-glue smoke test (Task 12) passed: `SteamAPI` init, `SteamInventory`
construction, `getAllItems`, async `SteamInventoryResultReady_t` delivery, and
clean shutdown all succeeded against Valve's public test App ID 480, with zero
crashes — the full verification bar this pass committed to (see Acceptance
Criterion 6). The fork's README now documents its scope, native-build status,
build/verify/publish process, and upstream-rebase process (Task 13).

**Remaining known gap, tracked, not silent**: Linux x64 and macOS
(x86_64/arm64) native builds are not done — toolchain access (gcc/Xcode) was
not available this session. `v1.10.0-inventory.1`'s binaries are Windows-only.
This means: consuming the fork on Linux/macOS builds/players is not yet
possible; the Store panel feature (main-menu) can proceed now on Windows, but
full cross-platform Store support needs a follow-up fork release once Linux/
macOS toolchain access is arranged (see fork README's "Native build status"
section for exactly what's needed — no code changes, just running the
existing `build-natives/build-linux*.lua`/`build-osx*.lua` scripts on a
machine with the right toolchain, then cutting a new tag, e.g.
`v1.10.0-inventory.2`, and re-repointing `steamworks4j_version`).

Also still open, deferred to whoever consumes this next (the main-menu Store
panel feature, or a dedicated follow-up): real ownership/purchase testing
against a genuine Steam App ID with configured Inventory items (spec Open
Question 6/Risk 4 — App ID 480 has none), and the `SteamworksSteamInventoryGateway`-
style consumer class this fork's Architecture section already sketches the
shape of but explicitly does not build.

## Progress Status (updated 2026-07-22, superseded by Final Status above)
- **Done**: Tasks 1 (SDK purchased/downloaded, v1.64), 2 (fork created at
  `github.com/Probastian/steamworks4j`, `upstream` remote added), 3 (confirmed
  `MicroTxnAuthorizationResponse_t`/FR4 was already present upstream in the
  fork's base commit — no edit needed), 4 (Java-side files committed,
  `08f0941`), 5 (jnigen-generated + hand-written C++ glue for
  `SteamInventoryNative`, verified field-for-field against the real SDK
  headers), and **6a only** (Windows x64 native rebuilt via the
  premake+MSBuild toolchain — now installed on the primary dev machine via
  Visual Studio Build Tools — 0 errors/0 warnings, all pre-existing wrapped
  interfaces confirmed still building, all 10 expected JNI exports verified
  via `dumpbin`). Committed/pushed to branch `add-steam-inventory-bindings`,
  commit `cc64f66`.
- **Explicitly deferred, by user decision (not a silent gap)**: Tasks 6b–6d
  (Linux x64, macOS x86_64, macOS arm64 native builds) — toolchain access
  (gcc/Linux, Xcode/macOS) is not available on the current machine and the
  user has decided to pick this up in a later session rather than acquire a
  VM/cloud runner/borrowed hardware now. **This is a known, tracked gap**,
  consistent with Acceptance Criterion 2's own "documented gap, not silently
  absent" allowance and Risk 2's mitigation path. Nothing in Tasks 7–13
  (JitPack publishing, mod-repo coordinate repoint, smoke test, rebase-process
  docs) has been started yet and all still assume/prefer the full 4-platform
  set before Task 8 cuts a release — **revisit this plan before publishing a
  release to decide whether to publish Windows-only now (documenting Linux/
  macOS as a known gap in the fork's own README and this mod's own
  `gradle.properties` comment) or wait until all 4 platforms are built.**

## Acceptance Criteria
This initiative is "ready" for the main-menu Store panel feature to begin
consuming it (per spec Non-goals/Future Extensions — that consuming feature's
own gateway/UI work is explicitly out of scope here) once all of the
following hold:
1. The fork repository (`Probastian/steamworks4j` or equivalent) exists, has
   `upstream` set to `code-disaster/steamworks4j`, and contains
   `SteamInventory.java`/`SteamInventoryNative.java`/`SteamInventoryCallback.java`/
   `SteamInventoryResult.java`/`SteamItemDetails.java` plus the additive
   `SteamUserCallback`/`SteamUser.java`/`SteamUserNative.java` changes (FR1,
   FR2, FR4).
2. Native binaries have been rebuilt for at least Windows x64 (confirmed
   toolchain), with Linux x64/macOS x86_64/macOS arm64 either also rebuilt or
   explicitly, visibly documented as a known gap pending toolchain access
   (Risk 2) — not silently absent (FR5, spec Open Question 3).
3. All pre-existing wrapped interfaces (Friends, RemoteStorage, UGC,
   Matchmaking, Apps, Utils, User's existing surface) still build and behave
   identically in the fork (FR6) — confirmed via the fork's own existing
   build/example/test surface.
4. The fork jar is published and resolvable via the chosen distribution
   mechanism (JitPack per Decision 3, or a documented fallback if JitPack
   proves unworkable at Task 8/9) — confirmed by a scratch-project dependency
   resolution succeeding (Sequencing Task 9), independent of this mod's own
   build (FR7).
5. This mod's own `gradle.properties`, `services/build.gradle`, and all three
   platform `build.gradle` Jar-in-Jar `include` lines are repointed at the new
   coordinate, and `gradlew build` succeeds across all three platform modules
   with no regression to existing Friends/RemoteStorage/UGC/Matchmaking/Apps/
   Utils behavior.
6. The manual JNI-glue smoke test (Test Strategy) passes against test App ID
   `480`: `SteamAPI` initializes, `SteamInventory` constructs, `GetAllItems`
   is called, `SteamInventoryResultReady_t` fires on the next pump, and
   `GetResultItems` returns without a native crash. **This is explicitly the
   full verification bar for this pass** — no genuine ownership/price/purchase
   verification is required or expected, since no real App ID with configured
   Inventory Service items exists yet (Risk 4, spec Open Question 6).
7. The fork's rebase/update process against upstream is documented (fork
   repo's own README), and this mod's repo carries a short pointer (e.g.
   alongside this plan) recording the final published coordinate/version for
   whoever starts the Store panel feature next.

## Open Questions
- Exact JitPack tag/coordinate string — cannot be finalized until Sequencing
  Task 9 (fork release actually cut); tracked as Risk 3, not left unresolved
  silently.
- Whether Linux/macOS toolchain access will be obtained via VM, cloud
  runner, or borrowed hardware — a concrete acquisition decision left to
  whoever executes Task 1/Task 6, not resolvable by this planning pass
  (Risk 2).
- Whether Valve's SDK redistribution terms actually permit committing
  prebuilt `SteamInventory`-touching binaries into a public fork repo (NFR4,
  Risk 6) — must be re-confirmed by whoever has partner-portal access at
  Task 1/Task 8, not assumed by this plan.
- Rebase strategy specifics (rebase vs. merge, cadence) — explicitly left as
  a maintenance-process decision for whoever owns the fork long-term, per
  spec Open Question 7; not resolved further here.
