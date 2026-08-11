# Implementation Plan — Steam Cloud Sync

## Summary
Build `features/steam-cloud-sync` (all six requirement groups, plus the new
`api`-layer `CloudSyncable`/hook-interface surface, plus per-platform
composition roots, Version Adapters, and Mixins) on top of the already-shipped
Steamworks bootstrap (`services/steamworks`). Every Cloud read/write is
mediated through one small, feature-owned `CloudFileStore`/`WorldArchiveCloudStore`
seam so that only two classes in the entire feature ever import
`com.codedisaster.steamworks.*`, keeping every data model, IO class, and
business-logic service (NFR1) importable and testable on a plain JVM with
zero Minecraft/steamworks4j-native-call dependency. No implementation code is
written as part of this plan.

This plan resolves, concretely, every item the specification explicitly left
open for planning: the second-client-entrypoint hand-off (Architecture,
inherited item 1), whether `CloudSyncable` needs its own ADR (inherited item
2 — yes, written below as ADR-0003), Mixin-vs-non-mixin for the Singleplayer
synthetic cloud-only rows (Architecture's "third open architectural item"),
the exact Fabric API event names for FR4.2/FR6.2, and the exact steamworks4j
`SteamRemoteStorage` method signatures this plan depends on.

## Existing Implementation
- `services/src/main/java/de/lazuli/services/steamworks/SteamworksService.java`
  is the sole owner of `SteamAPI` init/pump/shutdown; implements
  `api/.../steamworks/SteamAvailability` (`isSteamAvailable()`,
  `steamAppId()`). It exposes **no** accessor for a `SteamRemoteStorage` (or
  any other steamworks4j interface) instance — a feature that needs one
  constructs its own directly (steamworks4j interface objects are plain
  `new Xxx(callback)` constructions once `SteamAPI.init()` has succeeded;
  `services/build.gradle` deliberately declares steamworks4j as `api`, not
  `implementation`, specifically so feature code "will eventually construct
  `SteamFriends`/`SteamRemoteStorage`/etc. objects themselves" — its own code
  comment).
- Each of the three `platform/fabric-<version>/src/main/java/de/lazuli/SteamworksClientInitializer.java`
  files is byte-for-byte identical: resolves the App ID
  (`SteamAppIdResolver`) and native-library directory
  (`FabricLoader.getInstance().getConfigDir().resolve("lazuli").resolve("steamworks-natives")`),
  constructs `SteamworksService.create(...)`, registers
  `ClientTickEvents.END_CLIENT_TICK -> steamworksService::pumpCallbacks` and
  `ClientLifecycleEvents.CLIENT_STOPPING -> steamworksService::shutdown`, and
  logs the result. Each module's `fabric.mod.json` already lists it as the
  **second** entry in the `"client"` entrypoint array (after
  `HelloWorldMainMenuClientInitializer`); Fabric Loader invokes a mod's own
  same-type entrypoints in declared array order — this is already an
  established, relied-upon fact in this repo, not a new assumption (see
  `services/implementation-plan.md`'s own `HelloWorldMainMenuClientInitializer`/`SteamworksClientInitializer`
  ordering commentary).
- `services/implementation-plan.md`'s own Risk 3 / Architecture's "Decision 5"
  explicitly left the *second*-consumer hand-off unsolved: "this plan does
  not solve how a second, future client entrypoint... obtains the same
  already-constructed `SteamworksService` instance rather than constructing a
  second, competing one." No holder/hand-off class exists anywhere in the
  repo today (confirmed: no file matching `*Holder*`/`*Handoff*`/`*Registry*`
  under `platform/` or `services/`). This plan is that second consumer and
  resolves the hand-off below (Decision 1).
- `docs/adr/0001-...md` permits a platform module's `ClientModInitializer`/`ModInitializer`
  entrypoint *itself* to construct/reference concrete Feature classes for
  wiring, but explicitly restricts this to "the small, side-effect-only
  wiring code in a platform module's... entrypoints" — **not** to other
  Platform code such as Version Adapters, which "continue to depend only on
  `api`, never on Feature classes." `docs/adr/0002-...md` generalizes the
  same exception from Feature classes to `services/`-layer classes, with the
  identical scope restriction. Both are load-bearing constraints on this
  plan's design (see Decision 2 and Decision 3 below): every new Version
  Adapter this plan adds must depend only on the top-level `api` module, and
  only the literal `SteamCloudSyncClientInitializer` entrypoint class may
  import a *different* feature's classes (`features/hello-world-main-menu`).
- `.claude/context/architecture.md`'s Dependency Rules table (`Features:
  API, Services`; `Forbidden: Feature -> Feature`) and `feature-guidelines.md`'s
  required folder layout (`api/`, `config/`, `events/`, `gui/`, `mixins/`,
  `resources/`, `services/`, `tests/`, realized as Java sub-packages per
  `features/hello-world-main-menu/implementation-plan.md`'s own Decision 3 —
  this plan follows the identical realization, not re-litigating it) both
  apply unchanged.
- `.claude/context/ui-guidelines.md` (new; read in full for this plan)
  defines Pattern 1 (non-mixin overlay widget, `ScreenEvents.AFTER_INIT` +
  `Screens.getWidgets`/`getButtons`) and Pattern 2 (synthetic list entry,
  needs a Mixin). Group 3's bookmark toggle and Group 6's per-world
  sync-toggle icon (FR6.1) are both Pattern 1. Group 6's cloud-only synthetic
  world rows (FR6.8/FR6.9) are Pattern 2 (Decision 4 below).
- `features/hello-world-main-menu`'s `HelloWorldMainMenuConfigIO`/`HelloWorldMainMenuConfig`
  (read in full) are plain, stateless-besides-the-file value/IO types — no
  live singleton to hand off, just a `Path` + two pure methods. This matters
  for Decision 2 below (the `CloudSyncable` adapter needs no reference to the
  *already-constructed* `HelloWorldMainMenuService` instance; it can
  independently reopen the same config file path).
- steamworks4j 1.10.0 is already the pinned version repo-wide
  (`gradle.properties:41`, `services/build.gradle`), already resolved in this
  repo's Gradle cache
  (`C:\Users\<username>\.gradle\caches\modules-2\files-2.1\com.code-disaster.steamworks4j\steamworks4j\1.10.0\...\steamworks4j-1.10.0.jar`,
  plus its `-sources.jar`/`-javadoc.jar`). This plan introduces **no new
  external Maven dependency** — see Dependencies.
- The real, resolved Minecraft jars for all three supported versions are
  already present in this machine's Gradle/Loom cache (confirmed via `Glob`,
  not assumed): `C:\Users\<username>\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar`
  and the `26.1` equivalent (Mojang-mapped, unobfuscated), and
  `...\minecraft-merged\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\...jar`
  (Yarn-mapped) for 1.21.11. **This planning pass had no Bash/decompiler/`javap`
  tool available** (only `Read`/`Glob`/`Grep`/`Write`/`WebFetch`/`WebSearch`),
  despite the task description assuming Gradle/bytecode-inspection access —
  `Grep` can confirm a class's *presence* inside a jar (its ZIP-entry
  pathname is stored as plain text even though the compiled `.class` bytes
  themselves are DEFLATE-compressed and not text-searchable), but cannot
  recover method signatures/visibility modifiers from the compressed class
  bytes without an actual unzip/decompile/`javap` step. This is recorded
  honestly in Decision 4 and Risk 1 below, rather than presented as a
  completed bytecode confirmation.

## Decisions on the Open Questions (resolved during planning)

### 1. Second-client-entrypoint hand-off: entrypoint ordering (already-established) + a narrow, composition-root-scoped `SteamworksServiceHandoff`, not a general registry
Each platform module gets a new `SteamworksServiceHandoff` class (package
`de.lazuli`, one per platform module — platform modules never share code with
each other, same as every other `de.lazuli` class already duplicated 3x):

```java
public final class SteamworksServiceHandoff {
    private static volatile SteamworksService instance;
    private SteamworksServiceHandoff() {}
    public static void publish(SteamworksService service) { instance = service; }
    public static SteamworksService require() {
        SteamworksService published = instance;
        if (published == null) {
            throw new IllegalStateException(
                "SteamworksServiceHandoff.require() called before SteamworksClientInitializer "
                + "published a SteamworksService — check this module's fabric.mod.json \"client\" entrypoint order.");
        }
        return published;
    }
}
```

`SteamworksClientInitializer` gains exactly **one new line** (Files to
Modify): `SteamworksServiceHandoff.publish(steamworksService);`, added right
after construction. The new `SteamCloudSyncClientInitializer` (Decision 2)
calls `SteamworksServiceHandoff.require()` at the top of its
`onInitializeClient()`. Correctness depends only on
`SteamworksClientInitializer` appearing before `SteamCloudSyncClientInitializer`
in `fabric.mod.json`'s `"client"` array — already the exact mechanism this
repo already relies on for `HelloWorldMainMenuClientInitializer` before
`SteamworksClientInitializer` (see Existing Implementation), so this is reuse
of an established pattern, not a new risk.

**Why not the alternatives:**
- *Folding cloud-sync's own wiring directly into `SteamworksClientInitializer`*
  (skipping the hand-off entirely) was rejected: `SteamworksService`'s own
  bootstrap has 4 already-documented future consumers (Cloud, Friends,
  Workshop, matchmaking, per its own JavaDoc) — hard-wiring today's one
  consumer (Cloud) into the shared bootstrap class would require editing that
  same shared, already-shipped, already-tested class again for every future
  consumer, coupling it to all of them over time. A hand-off contract that
  each future consumer's own entrypoint reads from scales without repeatedly
  touching `SteamworksClientInitializer`.
- A **static field is real, if narrow, global state**, in real tension with
  `coding-style.md`'s "constructor injection over globals" and
  `philosophy.md`'s "Things to Avoid: Global state." This is called out
  explicitly rather than glossed over. It is accepted here as a deliberate,
  narrow exception scoped to exactly one composition-root-to-composition-root
  hand-off within a single platform module/process — the same layer (not
  Feature/Services business logic) ADR-0001/ADR-0002 already carve equally
  narrow exceptions for, and the same layer `LazuliMod.LOGGER` (an existing
  static field in this exact codebase) already occupies. No Feature, Services,
  or `api` code ever references `SteamworksServiceHandoff` — only the two
  platform composition-root classes in the same module do.
- **Reconsider if a 3rd/4th Steamworks-consuming feature arrives** (Friends,
  Workshop, matchmaking): at that point this single-field handoff may be
  worth promoting to something richer (e.g. an ordered list of "Steamworks
  consumer" callbacks constructed once by `SteamworksClientInitializer`
  itself) — deliberately not built now, per this repo's own
  graduate-on-second/third-use discipline (`architecture.md`).

### 2. Group 1 `CloudSyncable` adapter for `hello-world-main-menu`: a private nested class inside `SteamCloudSyncClientInitializer` itself, not a separate Version Adapter file
Per ADR-0001's literal scope (Existing Implementation above), only the
*entrypoint class itself* may reference a different Feature's concrete
classes — a separate top-level "Version Adapter" file importing
`HelloWorldMainMenuConfigIO` would **not** be covered by that exception (ADR-0001
explicitly excludes "Version Adapters... [which] continue to depend only on
`api`"). So the adapter bridging `features/hello-world-main-menu`'s config
into this feature's `CloudSyncable` mechanism (FR1.1/FR1.2) is a small
`private static final class HelloWorldMainMenuCloudSyncAdapter implements CloudSyncable`
declared **inside** `SteamCloudSyncClientInitializer.java` itself (not a
separate file), constructed with the same config path
`HelloWorldMainMenuClientInitializer` already resolves
(`FabricLoader.getInstance().getConfigDir().resolve("hello-world-main-menu.json")`)
and a fresh `HelloWorldMainMenuConfigIO()` (stateless; no need to share the
*instance* `HelloWorldMainMenuClientInitializer` already constructed — see
Existing Implementation). `exportState()`/`importState(byte[])` call
`configIO.serialize(...)`/`configIO.load(...)`/`configIO.parse(...)` against
that same file.

This keeps every Feature-class-crossing reference confined to the literal
composition-root entrypoint body, fully inside ADR-0001's existing grant —
**no new ADR text is needed to permit this specific adapter's existence**
(unlike the broader aggregation pattern below, which does need one).
Flagged in Risks: if a second/third `CloudSyncable` adopter later makes this
entrypoint file unwieldy, extracting per-feature adapters into their own
files would itself need a small ADR update loosening "Version Adapters depend
only on `api`" for this one narrow bridging shape — deliberately deferred,
not decided now.

### 3. New ADR-0003: Platform may aggregate cross-Feature `CloudSyncable`/hook-interface adapters for a third Feature
Written as part of this plan (`docs/adr/0003-cloudsyncable-cross-feature-bridging-via-api-contracts.md`,
full text below under Files to Create). This is genuinely new ground beyond
ADR-0001 (Platform may construct *one* Feature's classes to wire *that*
Feature) and ADR-0002 (Platform may construct a Services class): here,
Platform's composition root constructs an *adapter* bridging Feature A's
(`hello-world-main-menu`) state into an `api`-layer contract
(`CloudSyncable`) defined by Feature B (`steam-cloud-sync`), and aggregates
N such adapters into a `List<CloudSyncable>` handed to Feature B's own
service — "Platform bridges Feature A's exported state to Feature B via an
api-layer contract" (spec's own framing of this gap). Justification for a new
ADR rather than silently stretching 0001/0002, mirroring both ADRs' own
justification for not silently stretching each other: 0001/0002's Context/Consequences
sections are written specifically in terms of "one Feature" / "one Services
class," not "bridging two Features via a third Feature's own contract."

### 4. Group 6 synthetic cloud-only world rows: commit to Mixin (Pattern 2) for all three platform modules — working assumption confirmed as far as tooling allowed, residual gap flagged honestly
The specification's own research already found, for Yarn-mapped 1.21.11,
that `WorldListWidget`'s population methods are private and its inherited
`EntryListWidget.addEntry`/`clearEntries` are `protected`, and that
`Screens.getWidgets`/`getButtons` cannot reach a list widget's *internal*
entry collection (only a Screen's own top-level widget list) — both are
structural, not per-version, facts (`EntryListWidget` is the abstract base
*every* scrolling vanilla list uses; a plain overlay-widget mechanism
operating on a Screen's own widget list was never going to reach a
completely different widget's internal state, regardless of Minecraft
version).

This plan attempted the two further verifications this repo's own
`ui-guidelines.md`/`minecraft.md` discipline calls for, using the tools
actually available (`Read`/`Glob`/`Grep`/`WebFetch`/`WebSearch` — **no**
Bash/decompiler/`javap`, see Existing Implementation):
- **Confirmed via `Glob`:** the real, resolved Mojang-mapped 26.2/26.1
  Minecraft jars already exist in this machine's Gradle/Loom cache
  (paths above) — i.e., the spec's own caveat ("no compiled Mojang-mapped jar
  was reachable during spec research") no longer holds; the jar *is*
  reachable in this environment.
- **Confirmed via `Grep`:** `SelectWorldScreen` exists as a real class inside
  the 26.2 jar (ZIP-entry-pathname match) — trivial, but rules out the class
  having been renamed/removed outright.
- **Attempted but not achievable in this tool environment:** extracting
  actual method visibility/signatures from the compressed `.class` bytes
  inside that jar requires unzip + `javap`/decompile, neither of which this
  planning pass's tool set provides. `Grep`'s content mode itself refuses to
  search the (binary, DEFLATE-compressed) class data ("binary file matches").
- **Corroborating secondary evidence gathered instead (`WebSearch`):** the
  official Mojang name for Yarn's `WorldListWidget` is confirmed to be
  `net.minecraft.client.gui.screens.worldselection.WorldSelectionList`
  (recorded in `minecraft.md`'s table by this plan). Separately,
  `EntryListWidget.addEntry`/`clearEntries` are documented as `protected`
  consistently across Yarn API docs spanning **1.16.5 through 24w06a** (a
  ~7-year, dozens-of-versions span) — strong evidence this is a stable,
  long-standing design choice of the class hierarchy itself (access
  modifiers are a property of the compiled bytecode, unaffected by which
  *name* a mapping assigns, and unlikely to have been loosened in the couple
  of releases since 24w06a leading up to 26.1/26.2).

**Decision:** commit to the spec's own "working assumption" for **all three**
platform modules, not just 1.21.11 — each gets a narrow `@Mixin(WorldSelectionList.class)`
(26.x) / `@Mixin(WorldListWidget.class)` (1.21.11) in its own
`platform/fabric-<version>/.../mixin/` package, using `@Invoker` to expose
the otherwise-inaccessible entry-add/clear methods (never `@Inject`/`@Redirect`
rewriting vanilla logic, per `ui-guidelines.md`'s "prefer the narrowest mixin
shape" guidance), registered in that module's `*.mixins.json`. **This is a
plan-level commitment, not a false claim of bytecode-level confirmation** —
implementation's concrete first step for all Group 6 UI work (recorded again
under Risks) must be running `javap -p` (or a decompiler) against each
platform's real resolved Minecraft jar (paths recorded above — already
present, no download needed) to confirm the exact mapped method names this
plan cannot pin further than "very likely, by structural/historical
analogy," and to log the actual result in `minecraft.md`'s table per that
file's own convention.

The real `WorldEntry`/`Entry` constructor-accessibility question (spec's
sub-item (b)) is handled by *not* depending on it: this plan's synthetic
`CloudOnlyWorldListEntry` is a **new subclass of the abstract `Entry` base
type** the list already uses (a completely ordinary, non-mixin Java subclass
— subclassing a public/protected abstract nested type is a long-standing,
mixin-free modding pattern across the ecosystem; only *inserting* an instance
into the list's private backing collection needs the mixin/`@Invoker`), not
a reuse of the concrete `WorldEntry`/`LevelSummary`-backed type at all. If
implementation's `javap` step finds the abstract `Entry` base type's own
constructor is *also* inaccessible (not just `addEntry`/`clearEntries`), the
mixin's `@Invoker` surface simply grows to cover it too — same mechanism,
larger surface, not a different design.

### 5. FR4.2/FR6.2 event source: `ClientPlayConnectionEvents` (`fabric-networking-api-v1`), not `fabric-lifecycle-events-v1`
Confirmed via a direct `WebFetch` of Fabric API's own GitHub source at the
tag matching this repo's exact resolved 1.21.11 fabric-api version
(`0.141.4+1.21.11`):
`net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents`
(module `fabric-networking-api-v1`, **not** `fabric-lifecycle-events-v1` as
the specification's own illustrative guess suggested) declares:
- `Event<Init> INIT` — `void onPlayInit(ClientPacketListener handler, Minecraft client)`
- `Event<Join> JOIN` — `void onPlayReady(ClientPacketListener handler, PacketSender sender, Minecraft client)`
- `Event<Disconnect> DISCONNECT` — `void onPlayDisconnect(ClientPacketListener handler, Minecraft client)`

(Parameter types shown are Mojang-mapped, matching what Fabric API's own
GitHub source is written against; the Yarn-side equivalent parameter type is
`net.minecraft.client.network.ClientPlayNetworkHandler` — recorded, with the
same honesty caveat as Decision 4, in `minecraft.md`'s table, since it was
not independently re-confirmed by compiling against this repo's actual 1.21.11
mappings.)

`JOIN`/`DISCONNECT` fire uniformly whether the connection is a real
multiplayer server or singleplayer's own integrated server (both go through a
real `ClientPacketListener`/`ClientPlayNetworkHandler`), so **one single event
registration pair covers FR4.2 (singleplayer world entry/exit, multiplayer
join/disconnect) and FR6.2's "world unload/exit" trigger** — `SteamCloudSyncClientInitializer`
registers both once. Distinguishing singleplayer vs. multiplayer for
`LastPlayedPointer.type` (FR4.1) uses vanilla's own well-known
`hasSingleplayerServer()`-shaped client-side check (exact Mojang/Yarn method
name not re-derived to the same rigor as the event class itself in this
pass — a small, low-risk, implementation-time confirmation, called out in
Risks, not a load-bearing planning gap).

`fabric-networking-api-v1` is already transitively available via each
platform module's existing `net.fabricmc.fabric-api:fabric-api:${fabric_api_version}`
dependency — confirmed directly for 1.21.11 (its remapped submodule jar,
`fabric-networking-api-v1-...-5.1.6+6b6d71a53e-sources.jar`, already sits in
this repo's own `.gradle/loom-cache/remapped_mods` per this plan's own
`Glob`), and structurally true for 26.1/26.2 (their single aggregate
`fabric-api-<version>.jar` bundles every submodule for non-remap
consumption, the same way `ClientTickEvents`/`ClientLifecycleEvents` are
already consumed with no extra Gradle coordinate). **No new Gradle
dependency is needed.**

### 6. steamworks4j 1.10.0 `SteamRemoteStorage`/`SteamRemoteStorageCallback` signatures — confirmed against the real GitHub source at the exact resolved tag
Confirmed via `WebFetch` of `github.com/code-disaster/steamworks4j` at tag
`1.10.0` (this repo's own pinned version, `gradle.properties:41`), not the
`master` branch:

```java
// SteamRemoteStorage
public SteamUGCFileWriteStreamHandle fileWriteStreamOpen(String name)
public boolean fileWriteStreamWriteChunk(SteamUGCFileWriteStreamHandle stream, ByteBuffer data)
public boolean fileWriteStreamClose(SteamUGCFileWriteStreamHandle stream)
public boolean fileWriteStreamCancel(SteamUGCFileWriteStreamHandle stream)
public SteamAPICall fileReadAsync(String file, int offset, int toRead)
public boolean fileReadAsyncComplete(SteamAPICall readCall, ByteBuffer buffer, int toRead)
public int getFileSize(String file)
public boolean getQuota(long[] totalBytes, long[] availableBytes)
public boolean fileForget(String file)
public long getFileTimestamp(String file)
public boolean fileDelete(String file)
public boolean fileWrite(String file, ByteBuffer data) throws SteamException
public int fileRead(String file, ByteBuffer buffer) throws SteamException

// SteamRemoteStorageCallback (all default/empty-bodied; only the two below matter here)
void onFileWriteAsyncComplete(SteamResult result)
void onFileReadAsyncComplete(SteamAPICall fileReadAsync, SteamResult result, int offset, int read)
```

Two consequences this plan bakes in:
- **`fileWrite`/`fileRead`/the streamed-write trio are plain, synchronous,
  blocking calls — no callback needed for Groups 1, 3, 4, 5, or Group 6's
  upload path.** Only Group 6's **restore/read** path
  (`fileReadAsync`/`fileReadAsyncComplete`) is asynchronous and needs
  `SteamRemoteStorageCallback.onFileReadAsyncComplete` dispatch, which is
  only ever delivered while `SteamworksService.pumpCallbacks()` keeps running
  on the client tick thread (already-established project convention,
  `services/specification.md:83-85`, cited by this feature's own spec). This
  substantially simplifies the design (Decision 7).
- **`getFileSize` returns `int`** (not `long`) — a hard ~2 GiB ceiling on any
  single Cloud file readable via this path. Irrelevant in practice given
  `maxWorldArchiveSizeMb` defaults to 50, but worth recording so nobody
  designs Group 6 around files anywhere near that ceiling.

### 7. `CloudFileStore`/`WorldArchiveCloudStore`: the only two seams that ever import steamworks4j
To satisfy NFR1 (plain-JVM-testable, zero steamworks4j-native-call
dependency) for **every** business-logic class in this feature — including
Group 6, the one group the specification itself flags as needing real Steam
I/O — this plan introduces two small, feature-internal (not `api`-module;
these never cross the Platform boundary) interfaces in
`features/steam-cloud-sync/services/`:

- `CloudFileStore` — `boolean isAvailable()`, `Optional<byte[]> read(String fileName)`,
  `boolean write(String fileName, byte[] data)`, `OptionalLong fileTimestamp(String fileName)`.
  Used by Groups 1/3/4/5 (`BookmarkedServersService`, `NotesService`,
  `LastPlayedPointerService`, and the `CloudSyncable` reconciliation loop) —
  intentionally minimal, since FR0.6/FR6.7 confirm quota/`fileForget`
  housekeeping is scoped to world archives only, never these small files.
- `WorldArchiveCloudStore` — the streamed-write, async-read, quota, and
  `fileForget` surface Group 6 alone needs: `streamWrite(String file, ByteBuffer data)`,
  `beginAsyncRead(String file, AsyncReadListener listener)`, `int fileSize(String file)`,
  `boolean getQuota(long[] total, long[] available)`, `boolean forget(String file)`,
  `OptionalLong fileTimestamp(String file)`.

Each has exactly two implementations:
- `SteamRemoteStorageCloudFileStore` / `SteamRemoteStorageWorldArchiveStore`
  (real; the **only** two classes in `features/steam-cloud-sync` that import
  `com.codedisaster.steamworks.*`; the latter also implements
  `SteamRemoteStorageCallback` to receive `onFileReadAsyncComplete`).
- `NoopCloudFileStore` / `NoopWorldArchiveCloudStore` (used whenever
  `!SteamAvailability.isSteamAvailable()`, structurally satisfying FR0.1 — no
  scattered `if (steamAvailable)` branching inside any of the six services).

`CloudSyncCoordinator` is the sole constructor of whichever pair is
appropriate (real vs. no-op, gated on `SteamAvailability.isSteamAvailable()`
at construction time, per spec Architecture: "constructs (only if
`isSteamAvailable()`)") and injects the shared instances into each of the six
services' constructors. This means **every one of the six services, every
data model, and every IO class in this feature can be unit-tested with a
hand-written fake `CloudFileStore`/`WorldArchiveCloudStore` — zero
steamworks4j import anywhere near a test class**, the same testability
discipline `SteamworksServiceTest` already established for its own
"deterministic vs. real-environment" split.

### 8. Group 6 threading: a small feature-owned `CloudSyncWorker` (one background thread + one per-tick pump), not a new `services/` capability
Per spec Architecture — Threading: archive **compression** and restore
**decompression/extraction** must run off the render/client thread, but every
actual steamworks4j call (including the async-read kickoff/completion pair)
must run on the same thread `SteamAPI.init()`/`pumpCallbacks()` runs on (the
client tick thread) — this project's established single-thread discipline
for all Steamworks interaction.

`CloudSyncWorker` (feature-owned, `features/steam-cloud-sync/services/`) is a
single-daemon-thread `ExecutorService` (named thread factory,
`shutdown()`/`awaitTermination` wired to `CloudSyncCoordinator`'s own
shutdown checkpoint) plus one small `pumpTickWork()` method the platform
composition root registers on `ClientTickEvents.END_CLIENT_TICK` (the *same*
event family `SteamworksClientInitializer` already registers
`pumpCallbacks()` on — both listeners coexist fine, `Event<T>` supports
multiple registered listeners; this is not the "two features, same screen"
widget-collision case `architecture.md` warns about, which is specific to
overlapping widget placement, not event dispatch). `pumpTickWork()` drains a
small thread-safe queue of "issue this steamworks4j call next" work items
(populated by `CloudSyncWorker`'s background thread as it needs a Steam call
made), so the client tick thread only ever does cheap, bounded steamworks4j
calls, while `WorldSaveSyncService`'s compression and `WorldRestoreService`'s
decompression/extraction run entirely on the background thread.
`RestoreProgress` snapshots are published via a plain `volatile`/`AtomicReference`
field `WorldRestoreScreen` polls once per render frame (per spec: "only reads
the latest `RestoreProgress` snapshot on the render thread... never blocks
it").

Not promoted to `services/` (a shared capability): only this one feature
needs background work today — graduate-on-second-use
(`architecture.md`) applies unchanged.

### 9. Feature-facing UI hook interfaces: four new small `api`-module contracts, symmetric to `MainMenuHook` but consumed in the opposite direction
The specification's Public API section only names one new `api`-module type
(`CloudSyncable`, item 1) and otherwise places UI-facing data types
(`CloudOnlyWorldSummary`, `RestoreProgress`, `RestoreProgressListener`) under
`features/steam-cloud-sync/api/`. But ADR-0001 already establishes "Version
Adapters... continue to depend only on `api`, never on Feature classes" — and
every new Version Adapter this plan needs for Groups 3/6 UI (the bookmark
toggle, the sync-toggle icon, the cloud-only synthetic rows, `WorldRestoreScreen`)
must, by that existing rule, receive its feature-side capability through an
`api`-module interface, not by importing `features/steam-cloud-sync/services/*`
directly. **Consequence: `CloudOnlyWorldSummary`, `RestoreProgress` (+ its
`Phase` enum), `RestoreProgressListener`, and `RestoreHandle` move into the
top-level `api` module** (`de.lazuli.api.cloudsync`), alongside `CloudSyncable`
and four new hook interfaces — a concrete, necessary deviation from the
spec's illustrative placement, required by an already-binding rule, not
invented fresh here:

- `BookmarkSyncHook` — `boolean isBookmarked(String address)`, `void toggleBookmark(String address, String label)`.
  Implemented by `BookmarkedServersService` (Feature → API, allowed); consumed
  (held, not implemented) by platform's `FabricBookmarkToggleInjector`
  (Group 3, FR3.3).
- `WorldSyncToggleHook` — `boolean isSyncEnabled(String worldSlug)`, `void toggleSync(String worldSlug)`.
  Implemented by `WorldSyncPreferenceService`; consumed by
  `FabricWorldSyncToggleInjector` (Group 6, FR6.1).
- `CloudOnlyWorldsHook` — `List<CloudOnlyWorldSummary> listCloudOnlyWorlds(List<String> localWorldFolderNames)`.
  Implemented by a thin `CloudOnlyWorldsFacade` wrapping `CloudOnlyWorldDetector`
  + the pulled fingerprint file; consumed by `FabricCloudOnlyWorldListInjector`
  (Group 6, FR6.8/FR6.9). `localWorldFolderNames` is computed on the platform
  side (`FabricLoader`/`Minecraft` saves-directory listing — a one-line,
  Minecraft-specific call) and passed in as a plain `List<String>`, so no
  Minecraft type ever crosses the hook boundary.
- `WorldRestoreHook` — `RestoreHandle beginRestore(String worldSlug, RestoreProgressListener listener)`,
  `void cancelRestore(RestoreHandle handle)`. Implemented by `WorldRestoreService`;
  consumed by `WorldRestoreScreen` (Group 6, FR6.10–FR6.12).

This is the *same* `MainMenuHook` pattern (`ui-guidelines.md` Pattern 1, step
1: "Define a small `api`-layer hook interface for what the feature needs to
show/control") applied four more times, just with implementation/consumption
flipped (the Feature service implements the interface; the platform Version
Adapter holds/calls it) — a completely standard, equally valid direction for
this same pattern, not a new mechanism.

### 10. `WorldFingerprint.deviceLabel` derivation
A small pure `DeviceLabelResolver` (feature-owned, `features/steam-cloud-sync/services/`)
takes `(String userName, String hostNameOrNull) -> String` (testable with
fixed inputs); the one real call site (`WorldSaveSyncService`'s construction,
inside `features/steam-cloud-sync` itself — no platform involvement needed,
since `System.getProperty("user.name")` and `InetAddress.getLocalHost().getHostName()`
are plain `java.lang`/`java.net`, not Minecraft-specific) supplies the real
values, wrapping the hostname lookup in try/catch defaulting to `null` (→
`DeviceLabelResolver` falls back to just the username, or `"Unknown device"`
if both are unavailable).

## Files to Create

### `api` module (top-level, zero dependencies — same precedent as `SteamAvailability`)
- `api/src/main/java/de/lazuli/api/cloudsync/CloudSyncable.java` — `String cloudSyncId(); byte[] exportState(); void importState(byte[] data);` (spec Public API item 1). JavaDoc explains the "Feature-authored contract other Features implement, aggregated by a third Feature via the Platform composition root" shape and points at ADR-0003.
- `api/src/main/java/de/lazuli/api/cloudsync/BookmarkSyncHook.java` (Decision 9)
- `api/src/main/java/de/lazuli/api/cloudsync/WorldSyncToggleHook.java` (Decision 9)
- `api/src/main/java/de/lazuli/api/cloudsync/CloudOnlyWorldsHook.java` (Decision 9)
- `api/src/main/java/de/lazuli/api/cloudsync/WorldRestoreHook.java` (Decision 9)
- `api/src/main/java/de/lazuli/api/cloudsync/CloudOnlyWorldSummary.java` — record: `worldSlug, displayName, deviceLabel, syncedAtTimestamp` (moved here, Decision 9)
- `api/src/main/java/de/lazuli/api/cloudsync/RestoreProgress.java` — record: `phase, processedBytes, totalBytes`; nested `enum Phase { READING_FROM_CLOUD, EXTRACTING }` (moved here)
- `api/src/main/java/de/lazuli/api/cloudsync/RestoreProgressListener.java` — `void onProgress(RestoreProgress progress); void onComplete(String worldSlug); void onFailed(String worldSlug, String reason);` (moved here)
- `api/src/main/java/de/lazuli/api/cloudsync/RestoreHandle.java` — `record RestoreHandle(String worldSlug)` (opaque identity; moved here)

### `features/steam-cloud-sync` module (new Gradle subproject)
- `features/steam-cloud-sync/build.gradle` — `dependencies { api project(':api'); implementation project(':services') }` (`implementation` for `:services` — this feature's own public surface, e.g. `CloudSyncCoordinator`'s constructor, only ever declares `SteamAvailability`/plain types, never re-exposing steamworks4j types further downstream, unlike `services` itself).
- `features/steam-cloud-sync/README.md`

**`api/` sub-package** (`de.lazuli.features.steamcloudsync.api`, feature-internal data — never crosses the Platform boundary, so stays here rather than top-level `api`, per Decision 9's distinction):
- `BookmarkedServer.java` — record: `id, label, address, addedAt` (FR3.1)
- `LastPlayedPointer.java` — record: `type, name, identifier, timestamp`; nested `enum Type { WORLD, SERVER }` (FR4.1)
- `Note.java` — record: `id, text, context (nullable), x, y, z (nullable Double), createdAt` (FR5.1)
- `WorldSyncPreference.java` — record: `worldSlug, enabled` (FR6.1)
- `SteamCloudSyncConfig.java` — record mirroring the Configuration schema (`schemaVersion, enabled, syncSettings, syncAccessibility, syncBookmarkedServers, syncContinuePointer, syncNotes, maxWorldArchiveSizeMb, allowSelectiveFallback`) + `DEFAULT` constant
- `WorldFingerprint.java` — record: `worldSlug, displayName, deviceLabel, syncedAtTimestamp` (FR6.6)

**`config/` sub-package** (hand-rolled JSON, no external library — same precedent as `HelloWorldMainMenuConfigIO`, generalized per Decision below):
- `CloudSyncJson.java` — a small **shared, internal, generic** JSON value model + recursive-descent parser/writer (objects/arrays/strings/numbers/booleans), used by every IO class below instead of each one hand-rolling its own bespoke parser from scratch. Justified the same way `ui-guidelines.md`'s "graduate on second internal use" reasoning justifies a feature defining one shared widget class for its own repeated internal need (generalized here from widgets to JSON parsing): this single feature needs the same small-JSON-object/array shape **six times** (config, world-sync-preferences, bookmarks, notes, continue-pointer, fingerprint file) — copy-pasting `HelloWorldMainMenuConfigIO`'s single-schema parser six times would be real, avoidable duplication, while a full external JSON library remains undesirable for the same reason `hello-world-main-menu/implementation-plan.md`'s Decision 6 rejected one (no way to verify a guessed library version against a live Gradle resolution in this tool environment either — see Risks). Still zero external dependency.
- `SteamCloudSyncConfigIO.java` — `config/steam-cloud-sync.json` load/parse/serialize (Configuration section; malformed → defaults + warning, never throws)
- `WorldSyncPreferencesIO.java` — `config/steam-cloud-sync/world-sync-preferences.json`
- `BookmarkedServersIO.java` — `lazuli-bookmarked-servers.json`'s **local** copy (`schemaVersion` + `entries`)
- `NotesIO.java` — `lazuli-notes.json`'s local copy
- `LastPlayedPointerIO.java` — `lazuli-continue-pointer.json`'s local copy
- `WorldFingerprintIO.java` — the FR6.6 fingerprint metadata file's local copy

**`services/` sub-package**:
- `CloudFileStore.java`, `NoopCloudFileStore.java`, `SteamRemoteStorageCloudFileStore.java` (Decision 7)
- `WorldArchiveCloudStore.java`, `NoopWorldArchiveCloudStore.java`, `SteamRemoteStorageWorldArchiveStore.java` (Decision 7; the latter implements `SteamRemoteStorageCallback`)
- `CloudSyncWorker.java` (Decision 8)
- `DeviceLabelResolver.java` (Decision 10)
- `CloudSyncCoordinator.java` — constructs the `CloudFileStore`/`WorldArchiveCloudStore` pair (gated on `SteamAvailability`), owns the `List<CloudSyncable>` (constructor parameter, per spec item "`CloudSyncableRegistry`-shaped constructor parameter" — realized literally as `List<CloudSyncable>`, no wrapper type), applies the FR0.4 reconciliation rule per registered `CloudSyncable` at startup and at shutdown (FR1.3/FR1.4), exposes `void reconcileAtStartup()` / `void syncOnShutdown()` / per-group checkpoint methods.
- `BookmarkedServersService.java` — implements `BookmarkSyncHook`; CRUD (`add`/`remove`/`rename`) + FR0.4 sync via `CloudFileStore` (FR3.2/FR3.4)
- `NotesService.java` — CRUD via `CloudFileStore` (FR5.2/FR5.3)
- `LastPlayedPointerService.java` — updates on `ClientPlayConnectionEvents.JOIN`/`DISCONNECT` checkpoints (via `CloudSyncCoordinator`), FR4.3's "newer Cloud pointer → log/toast" comparison (FR4.1–FR4.4)
- `WorldSyncPreferenceService.java` — implements `WorldSyncToggleHook`; local-only CRUD, `markEnabledAfterRestore(String worldSlug)` (FR6.1/FR6.10)
- `CloudOnlyWorldDetector.java` — pure: `(List<String> localWorldFolderNames, List<WorldFingerprint> fingerprints) -> List<CloudOnlyWorldSummary>` (FR6.8)
- `CloudOnlyWorldsFacade.java` — implements `CloudOnlyWorldsHook`; thin composition of `CloudOnlyWorldDetector` + the already-pulled `WorldFingerprintIO` data (Decision 9)
- `WorldSaveSyncService.java` — size check (FR6.3), archive-building via `java.util.zip` (FR6.3), selective-fallback file set (FR6.4), fingerprint/warning logic (FR6.6), quota/`fileForget` bookkeeping via `WorldArchiveCloudStore` (FR6.7); consults `WorldSyncPreferenceService` (FR6.2)
- `WorldRestoreService.java` — implements `WorldRestoreHook`; staging-directory extraction + atomic move-into-place (FR6.12), same-slug collision check (FR6.13), `RestoreProgress` synthesis (FR6.11) via `CloudSyncWorker`

**`events/`, `gui/`, `mixins/` sub-packages** — each a `package-info.java` placeholder, same rationale as `hello-world-main-menu`'s: `gui/`/`mixins/` because FR8-equivalent layering (Dependency Rules table) forbids `net.minecraft.*` outside `platform/`; `events/` because Fabric event *registration* itself is a `net.fabricmc.fabric.api.*` import, which — mirroring `hello-world-main-menu/specification.md:8`'s existing FR8 precedent — also belongs only in `platform/` Version Adapters, not this feature module; this feature has no new cross-feature event bus to define either (`architecture.md:26`; no such bus exists yet).
**`resources/`** — `.gitkeep` placeholder (no bundled assets at the feature-module level; new icon textures live under each platform module's own resources, per `ui-guidelines.md`'s Texture convention — see platform Files to Create).

**`tests/`** (`src/test/java/de/lazuli/features/steamcloudsync/...`, mirroring every package above): unit tests for every data record's derived logic, every `config/*IO` class (round-trip + malformed-input fallback, mirroring `HelloWorldMainMenuConfigIOTest`), `CloudSyncJson` itself (the shared parser — most heavily tested class in this feature, since every IO class depends on its correctness), `CloudOnlyWorldDetector` (pure set-difference logic), `DeviceLabelResolver`, and every service using a hand-written fake `CloudFileStore`/`WorldArchiveCloudStore`/`CloudSyncable` (no mocking framework needed given how small these interfaces are, mirroring `HelloWorldMainMenuServiceTest`'s own fake-hook precedent — Mockito remains available repo-wide if a test author prefers it for a specific case).

### Platform modules — one composition root + Version Adapters + Mixin per module (×3: `fabric-26.2`, `fabric-26.1`, `fabric-1.21.11`)
- `platform/fabric-<version>/src/main/java/de/lazuli/SteamworksServiceHandoff.java` (Decision 1)
- `platform/fabric-<version>/src/main/java/de/lazuli/SteamCloudSyncClientInitializer.java` — new `ClientModInitializer`; composition root. Resolves `SteamworksServiceHandoff.require()`, builds the `CloudFileStore`/`WorldArchiveCloudStore` pair indirectly via `CloudSyncCoordinator`, constructs the private nested `HelloWorldMainMenuCloudSyncAdapter` (Decision 2), the six feature services, the four hook-implementing services wired into the four new Version Adapters (Decision 9), registers `ClientTickEvents.END_CLIENT_TICK -> cloudSyncWorker::pumpTickWork` and `ClientLifecycleEvents.CLIENT_STOPPING -> coordinator::syncOnShutdown`, and registers `ClientPlayConnectionEvents.JOIN`/`DISCONNECT` (Decision 5).
- `platform/fabric-<version>/src/main/java/de/lazuli/cloudsync/FabricBookmarkToggleInjector.java` — Pattern 1; `ScreenEvents.AFTER_INIT` on the Multiplayer server-list screen, one toggle widget per entry, holds a `BookmarkSyncHook` (FR3.3)
- `platform/fabric-<version>/src/main/java/de/lazuli/cloudsync/FabricWorldSyncToggleInjector.java` — Pattern 1; `ScreenEvents.AFTER_INIT` on `SelectWorldScreen`/`WorldSelectionList`'s **real, local** entries only, one toggle icon per entry, holds a `WorldSyncToggleHook` (FR6.1)
- `platform/fabric-<version>/src/main/java/de/lazuli/cloudsync/FabricCloudOnlyWorldListInjector.java` — Pattern 2; locates the Screen's `WorldSelectionList`/`WorldListWidget` child, calls `CloudOnlyWorldsHook.listCloudOnlyWorlds(...)`, uses the Mixin's `@Invoker` to append `CloudOnlyWorldListEntry` rows (FR6.8/FR6.9)
- `platform/fabric-<version>/src/main/java/de/lazuli/cloudsync/CloudOnlyWorldListEntry.java` — plain (non-mixin) subclass of the list's abstract `Entry` base type; renders the FR6.9 cloud icon + `displayName`/`deviceLabel`/`syncedAtTimestamp`; opens `WorldRestoreScreen` on double-click/Play (FR6.10)
- `platform/fabric-<version>/src/main/java/de/lazuli/cloudsync/WorldRestoreScreen.java` — thin `Screen` subclass; FR6.11's honest "Restoring... / Extracting..." copy, manually-drawn progress bar, Cancel button wired to `WorldRestoreHook.cancelRestore`
- `platform/fabric-<version>/src/main/java/de/lazuli/mixin/WorldSelectionListInvokerMixin.java` (26.2/26.1) / `WorldListWidgetInvokerMixin.java` (1.21.11) — narrow `@Accessor`/`@Invoker` mixin exposing `addEntry`/`clearEntries` (Decision 4)
- `platform/fabric-<version>/src/main/resources/assets/lazuli/textures/gui/sync_enabled.png`, `sync_disabled.png`, `cloud_only.png` — three small new icon textures (Group 6; the Group 3 bookmark toggle reuses vanilla's own existing favorite/star sprite already used for pinned servers on the Multiplayer screen — no new texture needed there, per `ui-guidelines.md`'s "prefer reusing an existing vanilla sprite... when one reasonably fits"). Identical bytes duplicated across all three platform modules' own resource trees (no shared-resources mechanism exists in this repo today — flagged as a minor Risk, not a blocker).

### Documentation
- `docs/adr/0003-cloudsyncable-cross-feature-bridging-via-api-contracts.md` (Decision 3; full Context/Decision/Consequences text, following ADR-0001/0002's shape)

## Files to Modify
- `settings.gradle` — add `include 'features:steam-cloud-sync'`
- `platform/fabric-26.2/src/main/java/de/lazuli/SteamworksClientInitializer.java`,
  `platform/fabric-26.1/.../SteamworksClientInitializer.java`,
  `platform/fabric-1.21.11/.../SteamworksClientInitializer.java` — each gains
  exactly one line, `SteamworksServiceHandoff.publish(steamworksService);`,
  right after construction (Decision 1). No other change to these files.
- `platform/fabric-26.2/build.gradle`, `platform/fabric-26.1/build.gradle`,
  `platform/fabric-1.21.11/build.gradle` — each gains
  `implementation project(':features:steam-cloud-sync')`. No new external
  Maven coordinate (steamworks4j/fabric-api already declared; see
  Dependencies).
- `platform/fabric-26.2/src/main/resources/fabric.mod.json`,
  `platform/fabric-26.1/.../fabric.mod.json`,
  `platform/fabric-1.21.11/.../fabric.mod.json` — each gains a **third**
  entry in the existing `"client"` array:
  `"de.lazuli.SteamCloudSyncClientInitializer"`, positioned **after**
  `"de.lazuli.SteamworksClientInitializer"` (order is load-bearing — Decision
  1). No other field changes.
- `platform/fabric-26.2/src/main/resources/lazuli.mixins.json`,
  `platform/fabric-26.1/.../lazuli.mixins.json`,
  `platform/fabric-1.21.11/.../lazuli.mixins.json` — each gains the new
  mixin class name (Decision 4) alongside the existing `"ExampleMixin"`.
- `.claude/context/minecraft.md` — already updated by this plan (two new rows
  in the Known Cross-Version API Differences table: the
  `WorldListWidget`/`WorldSelectionList` naming divergence, and
  `ClientPlayConnectionEvents`' connection-handle parameter-type divergence —
  see Decisions 4/5).
- No change needed to `gradle.properties` (no new external dependency
  version pin — see Dependencies).

## Interfaces
- `api/.../cloudsync/CloudSyncable` — the cross-Feature settings-sync bridge (Groups 1–2).
- `api/.../cloudsync/{BookmarkSyncHook, WorldSyncToggleHook, CloudOnlyWorldsHook, WorldRestoreHook}` — the four new Pattern-1/Pattern-2 UI hook contracts (Decision 9), each implemented by a Feature service and consumed by a Platform Version Adapter — same `MainMenuHook` shape, opposite direction.
- `api/.../cloudsync/{CloudOnlyWorldSummary, RestoreProgress, RestoreProgressListener, RestoreHandle}` — plain data types that cross the Platform/Feature boundary via the hook interfaces above, hence top-level `api`, not `features/steam-cloud-sync/api/` (Decision 9).
- `features/steam-cloud-sync/services/{CloudFileStore, WorldArchiveCloudStore}` — feature-internal-only seams (never referenced by Platform); the sole two points where `com.codedisaster.steamworks.*` is imported inside this feature (Decision 7).

## Services
- `CloudSyncCoordinator` (feature-owned, not a `services/`-module capability — same distinction `hello-world-main-menu/implementation-plan.md` already draws for `HelloWorldMainMenuService`). Owns reconciliation (FR0.4), the `CloudFileStore`/`WorldArchiveCloudStore` construction, and the `List<CloudSyncable>` aggregation.
- `CloudSyncWorker` (feature-owned; Decision 8) — the only new background-thread infrastructure this plan introduces; not promoted to `services/` (graduate-on-second-use, unchanged).

## Feature Classes
Enumerated fully under Files to Create above (`api/`, `config/`, `services/`
sub-packages). All are plain Java; NFR1 requires (and this plan's Decision 7
seam structurally guarantees) zero `net.minecraft.*`/steamworks4j-native-call
import outside the two `SteamRemoteStorage*` adapter classes.

## Tests

### Test Strategy
- Every data record (`BookmarkedServer`, `LastPlayedPointer`, `Note`,
  `WorldSyncPreference`, `SteamCloudSyncConfig`, `WorldFingerprint`,
  `CloudOnlyWorldSummary`, `RestoreProgress`) and every `config/*IO` class is
  tested on a plain JVM exactly like `HelloWorldMainMenuConfigTest`/`HelloWorldMainMenuConfigIOTest` —
  missing file → defaults + created; malformed → defaults + warning, never
  throws; round-trip for representative values.
- `CloudSyncJson` (the shared parser, Decision above) gets its own dedicated,
  the most exhaustively tested class in this feature (nested objects/arrays,
  escaped strings, numbers, booleans, malformed input at every stage) since
  every one of the six `*IO` classes depends on its correctness — a defect
  here would be the single highest-leverage bug in this whole feature.
- `CloudOnlyWorldDetector` — pure set-difference tests: no folders/no
  fingerprints → empty; a fingerprint with no matching folder → one summary;
  a fingerprint *with* a matching local folder → excluded (not cloud-only).
- Each of the six services (`BookmarkedServersService`, `NotesService`,
  `LastPlayedPointerService`, `WorldSyncPreferenceService`,
  `WorldSaveSyncService`, `WorldRestoreService`) is tested against a
  hand-written fake `CloudFileStore`/`WorldArchiveCloudStore` (never the real
  `SteamRemoteStorage*` adapters) — mirrors `SteamworksServiceTest`'s own
  "deterministic via a fake/precomputed seam" category; **no steamworks4j
  class ever appears on this feature's test classpath's actual invocation
  path**, only on its compile classpath (transitively, via `:services`,
  unused by any test).
- `WorldSaveSyncService`'s size-threshold (FR6.3/FR6.4) and quota/`fileForget`
  (FR6.7) math, and `WorldRestoreService`'s `RestoreProgress` percentage
  synthesis (FR6.11) and staging/atomicity behavior (FR6.12, using a real
  `@TempDir` — plain `java.nio.file`, permitted under NFR1 exactly as
  `HelloWorldMainMenuConfigIOTest` already establishes) are the highest-value
  test targets in this feature, given the spec's own emphasis on "conservative
  design against an unknown quota."
- No platform-module test coverage (Mixins, `ScreenEvents` registration,
  `Screen` subclasses are not unit-testable on a plain JVM, per
  `ui-guidelines.md`'s own Testing section) — all six requirement groups'
  actual in-game UI behavior (bookmark toggle, sync-toggle icon, cloud-only
  synthetic rows + restore flow, config load/reconcile at real startup/shutdown)
  is verified manually in-game across all three targets during the
  verification phase, with Steam both open and closed (mirrors the
  Steamworks bootstrap's own verification precedent).

## Dependencies
- **No new external Maven/Gradle dependency.** steamworks4j remains pinned at
  `1.10.0` (`gradle.properties:41`), already `api`-exposed by `services`;
  `java.util.zip` is JDK-provided (Group 6 archives, per spec's own
  hand-rolled-parser precedent). Confirmed already resolved in this repo's
  Gradle cache (`Existing Implementation`), not merely asserted.
- **New internal (inter-module) dependency edges**, all `project(...)`:
  - `features:steam-cloud-sync` → `api` (`api` configuration)
  - `features:steam-cloud-sync` → `services` (`implementation` configuration — see Files to Create's `build.gradle` note)
  - `platform:fabric-26.2` → `features:steam-cloud-sync` (`implementation`)
  - `platform:fabric-26.1` → `features:steam-cloud-sync` (`implementation`)
  - `platform:fabric-1.21.11` → `features:steam-cloud-sync` (`implementation`)
- **No new Fabric API Gradle coordinate:** `ClientPlayConnectionEvents`
  (`fabric-networking-api-v1`) and `ClientTickEvents`/`ClientLifecycleEvents`
  (`fabric-lifecycle-events-v1`) are all already transitively available via
  each platform module's existing `fabric-api` dependency (Decision 5).
- Depends on Decisions 1–10 above being accepted as part of this plan's
  approval — several resolve ambiguity the spec explicitly left open, so
  they are substantive parts of what's being approved, not incidental detail.

## Risks
1. **Group 6 Mixin exact target method names/visibility are a working
   assumption, not a `javap`-confirmed fact, for all three platform modules**
   (Decision 4). This planning pass had no Bash/decompiler/`javap` tool
   available to inspect the real, already-present resolved Minecraft jars
   (paths recorded in Existing Implementation) despite the task expecting
   that access — implementation's concrete first step for any Group 6 UI
   work must be running `javap -p` (or a decompiler) against those exact
   jars, before writing any `@Mixin` code, and logging the confirmed result
   in `minecraft.md`'s table (already seeded with the naming divergence this
   plan *did* confirm: `WorldListWidget` ↔ `WorldSelectionList`).
2. **`ClientPlayConnectionEvents`'s Yarn-side parameter type
   (`ClientPlayNetworkHandler`) was not independently re-confirmed by
   compiling against this repo's actual 1.21.11 mappings** (only the Mojang
   side was directly fetched from Fabric API's own GitHub source at the
   exact resolved tag) — low risk (very well-established, long-standing
   Yarn name), but implementation should still let a real compile confirm it
   rather than trust this plan's recollection, per this repo's own Research
   Rules.
3. **Vanilla's exact singleplayer-vs-multiplayer client-side check** (for
   `LastPlayedPointer.type`, FR4.1) is asserted only at the "there is a
   well-known vanilla API for this" level, not a specific confirmed method
   name — smallest-risk item in this plan, confirm at implementation time
   like every other cross-version specific.
4. **`CloudSyncJson` (Decision, Files to Create) is a materially larger
   hand-rolled parser than `HelloWorldMainMenuConfigIO`'s single-schema one**
   (generic objects/arrays vs. one fixed 2-field shape) — real risk of subtle
   bugs (nested structures, escaping) if not kept conservative. Mitigation:
   the most exhaustive test coverage in this feature is deliberately aimed at
   this one class (Test Strategy); it must fail closed (default + warn) on
   anything not confidently recognized, never attempt best-effort partial
   parsing, mirroring `hello-world-main-menu/implementation-plan.md`'s own
   Risk 3 for its narrower precedent.
5. **No shared-resources mechanism exists in this repo for the three new
   Group 6 icon textures** (Files to Create) — each platform module needs its
   own literal copy of the same PNG bytes; low-risk (three small files) but
   flagged so implementation doesn't go looking for a `common`-module
   resources convention that doesn't exist yet.
6. **`WorldEntry`/abstract `Entry` base-type constructor accessibility**
   (spec's own sub-item (b), Decision 4) is resolved by design (subclass the
   abstract base directly, never reuse the concrete `WorldEntry`/`LevelSummary`-backed
   type) rather than by confirmation — if the abstract base's own constructor
   also turns out inaccessible, the Mixin's `@Invoker` surface simply grows;
   flagged so this isn't rediscovered as a surprise.
7. **`SteamworksServiceHandoff`'s static field is a deliberate, narrow
   exception to this repo's own "constructor injection over globals"/"avoid
   global state" guidance** (Decision 1) — recorded here again as a plan-level
   trade-off the user is explicitly approving, not something to silently
   generalize further without revisiting the reasoning in Decision 1.
8. **Group 6's threading design (`CloudSyncWorker`, Decision 8) is new
   infrastructure with no existing precedent in this repo** (the Steamworks
   bootstrap itself does everything on the single client tick thread, never
   spawning a background thread) — the tick-thread-pump-queue hand-off
   between `CloudSyncWorker`'s background thread and the client tick thread
   is the most structurally novel piece of this whole plan; verify carefully
   in-game (large-world compression/restore under real load) during the
   verification phase, not just via unit tests of the pure math around it.
9. **Steam Cloud filename `/`-as-subdirectory behavior remains unconfirmed**
   (spec Architecture, explicitly flagged there as "not conclusively
   confirmed") — this plan does not attempt to resolve it either (out of
   scope for a planning-time web check); the flat, lowercase, prefixed naming
   convention the spec already recommends is followed as-is by every `*IO`
   class's Cloud file name.

## Acceptance Criteria
Mapped to the specification's functional and non-functional requirements:

- **FR0.1–FR0.6** — `CloudSyncCoordinatorTest`/service-level fakes confirm:
  `NoopCloudFileStore`/`NoopWorldArchiveCloudStore` make every group a
  structural no-op when `SteamAvailability.isSteamAvailable() == false`, with
  local CRUD still working (fake-`CloudFileStore` tests per service); no
  Cloud call happens outside the FR0.3 checkpoints (code review: no
  `CloudFileStore`/`WorldArchiveCloudStore` call site outside
  `CloudSyncCoordinator`'s reconcile/shutdown methods or a group's own
  documented checkpoint method); `fileDelete` does not appear anywhere in
  `features/steam-cloud-sync/src/main` except inside an explicit
  user-triggered removal path (spot-checked via `grep`, FR0.6).
- **FR1–FR2** — `CloudSyncableTest`-style coverage: a fake `CloudSyncable`
  registered into `CloudSyncCoordinator` is reconciled per FR0.4 at a
  simulated startup/shutdown; `HelloWorldMainMenuCloudSyncAdapter`
  (constructed inside `SteamCloudSyncClientInitializer`) round-trips a real
  `HelloWorldMainMenuConfig` through `exportState()`/`importState(...)`
  (verified manually in-game, since the adapter itself lives in `platform/`).
- **FR3.1–FR3.4** — `BookmarkedServersServiceTest` covers add/remove/rename +
  FR0.4 sync via a fake `CloudFileStore`; in-game check confirms the
  bookmark-toggle widget appears per Multiplayer server-list entry and
  reflects `BookmarkSyncHook.isBookmarked(...)`.
- **FR4.1–FR4.4** — `LastPlayedPointerServiceTest` covers the FR4.3
  newer-Cloud-pointer-logs-a-notification case; in-game check confirms a
  `LastPlayedPointer` update on singleplayer world entry/exit and
  multiplayer join/disconnect (`ClientPlayConnectionEvents.JOIN`/`DISCONNECT`),
  and that no world/server auto-launches.
- **FR5.1–FR5.3** — `NotesServiceTest` covers CRUD + optional
  context/coordinates + FR0.4 sync.
- **FR6.1** — In-game: every local world row gets the sync-toggle icon
  (default off, except a just-restored world defaulting on per FR6.10),
  gated on the feature's master `enabled` + `SteamAvailability.isSteamAvailable()`.
- **FR6.2–FR6.5** — `WorldSaveSyncServiceTest` covers the size-threshold
  decision (FR6.3), the selective-fallback file-set decision (FR6.4, exact
  include/exclude set), and asserts the whole-archive-first rationale (FR6.5)
  is reflected in the decision logic (never chooses selective mode for an
  under-threshold world).
- **FR6.6–FR6.7** — `WorldSaveSyncServiceTest` covers fingerprint-mismatch
  warning logic (FR6.6) and quota/`fileForget` bookkeeping (FR6.7) against a
  fake `WorldArchiveCloudStore`.
- **FR6.8–FR6.9** — `CloudOnlyWorldDetectorTest` covers the pure
  set-difference logic; in-game check confirms a cloud-only world (fingerprint
  present, no local folder) renders as a distinct, cloud-icon-marked
  synthetic row, visually distinct from the sync-toggle icon.
- **FR6.10–FR6.13** — `WorldRestoreServiceTest` (using `@TempDir`) covers:
  successful restore → `WorldSyncPreferenceService.markEnabledAfterRestore(...)`
  called (FR6.10); `RestoreProgress` phase/percentage synthesis (FR6.11);
  a forced mid-extraction failure leaves the real saves folder untouched and
  the staging directory fully cleaned up (FR6.12); a pre-existing same-slug
  local folder aborts before any extraction begins (FR6.13). In-game check
  confirms `WorldRestoreScreen`'s honest "Restoring.../Extracting..." copy,
  Cancel button, and post-completion/failure screen transitions.
- **NFR1** — `grep`-spot-check: zero `net.minecraft.*` import and zero
  `com.codedisaster.steamworks.*` import anywhere in
  `features/steam-cloud-sync/src/main` **except** the two
  `SteamRemoteStorageCloudFileStore`/`SteamRemoteStorageWorldArchiveStore`
  files (Decision 7); `gradlew :features:steam-cloud-sync:test` runs with no
  Minecraft jar on its test classpath (spot-checked via `--configuration
  testRuntimeClasspath`, mirroring `services`'s own NFR5 verification).
- **NFR2** — Code review + manual in-game soak test: no uncaught exception
  from any `CloudFileStore`/`WorldArchiveCloudStore` call site reaches the
  tick/render thread or crashes startup/shutdown, mirroring
  `SteamworksService.create(...)`'s own discipline.
- **NFR3** — Every public class/interface created carries a JavaDoc comment
  with at least one `{@code ...}`/`<pre>` usage example (spot-checked
  against the full Files to Create list).
- **NFR4** — `features/steam-cloud-sync` contains all required sub-packages
  (`api`, `config`, `events`, `gui`, `mixins`, `resources`, `services`,
  `tests`) plus `README.md`, matching Decision 3 of
  `hello-world-main-menu/implementation-plan.md`'s already-established
  realization of this same requirement.
- **Compatibility** — `gradlew build` succeeds for all three platform
  modules with the new `features:steam-cloud-sync` dependency, new Mixin
  registration, and new `fabric.mod.json` entrypoint entry in place; manual
  in-game verification across all three targets confirms identical
  player-visible behavior for every group, with Steam both running and
  closed (FR0.1's local-only degradation).

## Open Questions
- None remaining from the specification's five explicitly-flagged
  planning-phase items — all five (second-entrypoint hand-off, `CloudSyncable`
  ADR, Mixin-vs-non-mixin, Fabric API event names, steamworks4j signatures)
  are resolved above (Decisions 1, 3, 4, 5, 6 respectively), along with five
  additional decisions this plan judged necessary to make the first five
  concrete (Decisions 2, 7, 8, 9, 10). Any further questions should surface
  during implementation as concrete compile-time/bytecode-confirmation
  findings (Risks 1–3), not as open design questions.
