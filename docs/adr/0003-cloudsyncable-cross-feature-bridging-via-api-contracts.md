# ADR-0003: Platform May Aggregate Cross-Feature Adapters Implementing a Feature-Defined `api` Contract

## Status
Accepted.

## Context
ADR-0001 established that a platform module's composition root
(`ClientModInitializer`/`ModInitializer` entrypoints) may construct and wire
concrete **Feature** classes, restricted specifically to "the small,
side-effect-only wiring code in a platform module's... entrypoints" — all
other Platform code (Version Adapters, mixins) continues to depend only on
`api`. ADR-0002 generalized the same exception from Feature classes to
`services/`-layer classes.

The Steam Cloud Sync feature (`features/steam-cloud-sync`) introduces a
genuinely new wiring shape neither ADR covers. Its Group 1 (Settings/Preferences
Sync) requirement is: any feature's own service may export/import its config
as bytes via a new `api`-layer contract, `de.lazuli.api.cloudsync.CloudSyncable`
(`String cloudSyncId(); byte[] exportState(); void importState(byte[] data);`),
*defined by* `features/steam-cloud-sync` but *implemented by* an adapter
bridging a **different** feature's state (today: `features/hello-world-main-menu`'s
`HelloWorldMainMenuConfigIO`/`HelloWorldMainMenuConfig`) into that contract —
and the platform composition root aggregates an arbitrary number of such
adapters into a single `List<CloudSyncable>` handed to `features/steam-cloud-sync`'s
own `CloudSyncCoordinator`.

This is different in kind from both existing ADRs:
- ADR-0001/0002 each describe Platform constructing **one** object of a
  single, already-known type (one Feature's service, or one Services class)
  to wire it up.
- Here, Platform constructs an **adapter object per Feature-that-opts-in**
  (a number not fixed at design time — Group 2's future UI/accessibility
  feature is explicitly anticipated to add a second one later, per the
  specification's own Requirements Group 2), each implementing a contract
  *authored by a third Feature*, and aggregates all of them into that third
  Feature's own constructor parameter. `architecture.md`'s "Forbidden:
  Feature → Feature" rule is not violated by this shape (no Feature imports
  another Feature's classes — `features/steam-cloud-sync` only ever imports
  `api` types; `features/hello-world-main-menu` is never imported by
  `features/steam-cloud-sync`), but neither ADR's literal Context/Consequences
  text describes "Platform bridges Feature A's exported state to Feature B
  via an api-layer contract Feature B defines," and silently assuming it's
  covered would leave a future reader unable to tell whether that was a
  deliberate decision or an oversight — the same justification both prior
  ADRs already gave for not silently stretching each other.

The specification for this feature explicitly flagged this gap and asked
planning to resolve whether it needs its own ADR
(`features/steam-cloud-sync/specification.md`, Architecture, "inherited item
2").

## Decision
Generalize the composition-root exception one step further: **a platform
module's composition-root entrypoint may construct any number of small
adapter objects, each bridging a different Feature's own state into an
`api`-layer contract defined by a third Feature, and aggregate them (e.g. as
a `List<...>`) into a constructor parameter of that third Feature's own
service** — for the same underlying reason ADR-0001/0002 already accepted
their own narrower cases: something has to `new` these objects and connect
them, and a formal `api`-side self-registration mechanism would only relocate
the same "who wires this" question into `api` (a path this repo has already
rejected twice — see ADR-0001's own rejection of Option 2 there).

This does **not** relax `architecture.md`'s "Forbidden: Feature → Feature"
rule in the general case. It specifically covers the shape where:
1. The contract (`CloudSyncable`, or any future analogue) is defined in the
   top-level `api` module (zero dependencies), by the Feature that will
   *consume* the aggregated list.
2. Each adapter implementing that contract for a *different* Feature's state
   is constructed and owned by the platform composition root itself — per
   `features/steam-cloud-sync/implementation-plan.md`'s own Decision 2, as a
   private nested class inside the literal entrypoint class body wherever
   practical, keeping every Feature-class-crossing reference confined to
   that one already-licensed composition-root scope (ADR-0001's literal
   text) rather than spreading it across separate "Version Adapter" files
   (which ADR-0001 explicitly does **not** license to touch Feature classes).
3. No Feature ever directly imports another Feature's classes to implement
   this itself — only Platform's composition root does, exactly as
   ADR-0001/0002 already permit for their own narrower cases.

## Consequences
- `features/<name>/build.gradle` may declare `api project(':api')` to expose
  a new aggregation-style contract like `CloudSyncable` for other features to
  eventually be bridged into, without ever declaring a dependency on any
  other `features/<name>` module.
- A platform composition root's entrypoint class may grow adapter-bridging
  logic (as nested classes or, once a second/third adopter makes that file
  unwieldy, small dedicated files still confined to the composition-root
  scope) referencing multiple different Features' concrete classes — this is
  expected and intentional, not a build-graph or layering smell, provided
  each adapter's only job is translating one Feature's state into the
  aggregating Feature's `api`-defined contract.
- This decision generalizes to any future case where a third Feature wants
  to aggregate opt-in participation from an open-ended set of other
  Features via its own `api`-layer contract — not just `CloudSyncable`,
  and not just this feature.
- `architecture.md`'s Dependency Rules table remains unchanged in the sense
  ADR-0001/0002 already describe: it governs a layer's *business logic*
  dependencies; composition-root/entrypoint code remains an explicitly
  permitted, narrow exception whose job is precisely to cross layer/feature
  boundaries for wiring purposes — now confirmed to additionally cover
  "bridging two Features via a third Feature's own `api` contract," not just
  "wiring one Feature" or "wiring one Services class."
