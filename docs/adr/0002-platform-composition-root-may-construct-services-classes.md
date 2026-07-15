# ADR-0002: A Platform Module's Composition Root May Construct Services Classes

## Status
Accepted.

## Context
ADR-0001 established that a platform module's composition root (its
`ClientModInitializer`/`ModInitializer` entrypoints) may construct and wire
concrete **Feature** classes, as a narrow, explicit exception to
`.claude/context/architecture.md`'s dependency table (which otherwise
restricts Platform to depending only on `api`).

The Steamworks bootstrap service introduces a new composition-root wiring
need: each `platform/fabric-<version>` module's new
`SteamworksClientInitializer` must `new` the concrete
`de.lazuli.services.steamworks.SteamworksService` — a **Services**-layer
class, not a Feature class — to perform the actual native-library load,
`SteamAPI` init, tick-loop pump registration, and shutdown-hook registration.

ADR-0001, as written, is scoped specifically to Feature classes: its title,
Context, and Consequences sections all discuss `features/<name>` project
dependencies and Feature-layer objects (`HelloWorldMainMenuService`, etc.).
It does not, on its literal text, cover a composition root depending on and
constructing a concrete Services-layer class. Silently stretching ADR-0001's
scope to also cover this case, without a recorded decision, would leave a
future reader unable to tell whether that broadening was a deliberate choice
or an oversight.

## Decision
Generalize ADR-0001's composition-root exception from Feature classes to
Services classes: **a platform module's `client`/`main` entrypoint may
construct a concrete `services/`-layer class, for the sole purpose of
constructing and wiring it at startup**, for the same reasons ADR-0001
already accepted for Feature classes — something has to `new` the object and
drive its lifecycle, and a formal `api`-side registry would only relocate the
same "who wires this" question into `api` (a path this repo already rejected
once, per ADR-0001's own rejection of that alternative for Feature wiring).

This is not a general license for Platform code to depend on Services
internals anywhere it likes — exactly as ADR-0001 draws that line for
Features, this applies specifically to the small, side-effect-only wiring
code inside a platform module's `ModInitializer`/`ClientModInitializer`
entrypoints (the composition root). All other Platform code (Version
Adapters, mixins, etc.) continues to depend only on `api`, and Services
classes continue to never depend on Features or Platform.

## Consequences
- `platform/fabric-*/build.gradle` may declare an
  `implementation project(':services')` dependency for any Services class it
  bootstraps (here, `SteamworksService` via `SteamworksClientInitializer`).
  This is expected and intentional, matching the same "expected and
  intentional" framing ADR-0001 already applies to
  `implementation project(':features:<feature-name>')`.
- Services classes constructed from a composition root should, like Feature
  classes under ADR-0001, keep their public construction path simple and
  dependency-injectable (plain constructor/factory parameters, no
  Fabric/Minecraft types), so this wiring code stays a thin, mechanical
  "construct the object and register its lifecycle callbacks" shape.
  `SteamworksClientInitializer` is the reference example for this
  generalization, the same way `HelloWorldMainMenuClientInitializer` is the
  reference example for ADR-0001.
- This decision generalizes to every future platform-composition-root wiring
  of a shared Services-layer class, not just `SteamworksService`.
- `architecture.md`'s dependency table remains clarified, not changed, in
  exactly the sense ADR-0001 already describes: it governs a layer's
  *business logic* dependencies; a module's composition-root/entrypoint code
  is an explicitly permitted exception whose job is precisely to cross layer
  boundaries for wiring purposes — now confirmed to apply uniformly whether
  the object being wired is a Feature or a Service.
