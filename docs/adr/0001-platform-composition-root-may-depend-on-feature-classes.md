# ADR-0001: A Platform Module's Composition Root May Depend on Feature Classes

## Status
Accepted.

## Context
`.claude/context/architecture.md`'s dependency table states:

| Layer | Allowed Dependencies |
|-------|----------------------|
| API | none |
| Services | API |
| Features | API, Services |
| Platform | API |

Read literally, `Platform -> API` is the *only* edge Platform is allowed to
have. In practice, something has to construct a feature's business-logic
objects and trigger their entry point at the right time (e.g. at client
startup). For the "Hello World Main Menu" feature, that "something" is each
`platform/fabric-<version>` module's new `client` entrypoint
(`HelloWorldMainMenuClientInitializer`, a
`net.fabricmc.api.ClientModInitializer`), which must:

- `new` a platform-specific `FabricMainMenuHook` (implements the `api`
  module's `MainMenuHook`),
- `new` the feature's `HelloWorldMainMenuConfigIO`,
- `new` the feature's `HelloWorldMainMenuService`, passing it the hook, the
  config I/O, a config file `Path`, and a warning logger, and
- call `service.applyToMainMenu()`.

`HelloWorldMainMenuConfigIO` and `HelloWorldMainMenuService` are Feature-layer
classes (`features/hello-world-main-menu/...`), not `api` types. Constructing
them from a platform module is therefore, strictly, a `Platform -> Feature`
dependency edge that the table above does not list.

Two ways to resolve this were identified in this feature's specification:
1. Treat "Platform -> API only" as governing *business-logic* dependencies,
   and accept that a platform module's client entrypoint (the composition
   root) may reference concrete Feature classes purely for
   bootstrapping/wiring, as is common practice (e.g. Spring Boot's `main()`,
   Guice modules, manual dependency-injection composition roots in general).
2. Introduce a formal registration mechanism entirely on the `api` side (e.g.
   a Feature registers itself into a lifecycle callback exposed by `api`), so
   Platform never needs to import the Feature at all.

## Decision
Adopt option 1: **"Platform -> API only" governs business-logic dependencies.
A platform module's client entrypoint (composition root) may depend on and
directly reference concrete Feature classes, for the sole purpose of
constructing and wiring them together at startup.**

This is not a general license for Platform code to depend on Feature
internals anywhere it likes -- it applies specifically to the small,
side-effect-only wiring code in a platform module's `ModInitializer` /
`ClientModInitializer` entrypoints (the composition root), which by design
knows about concrete types on both sides of the boundary so it can connect
them. All other Platform code (Version Adapters like `FabricMainMenuHook`,
mixins, etc.) continues to depend only on `api`, never on Feature classes,
and Features continue to never depend on other Features or on Platform.

Option 2 was rejected: it would require `api` to define a registration/lookup
mechanism (a form of either global mutable state or an additional Fabric
Loader dependency pulled into `api`), which this feature's implementation
plan separately rejected for the same reason when choosing constructor
injection over a `MainMenuHookRegistry` (see
`features/hello-world-main-menu/implementation-plan.md`, Decision 1). Solving
the same problem twice with two different registries/lookups for what is
fundamentally one "who wires this together" question would be inconsistent;
plain composition-root wiring solves both at once.

## Consequences
- `platform/fabric-*/build.gradle` may declare an
  `implementation project(':features:<feature-name>')` dependency for any
  feature it bootstraps. This is expected and intentional, not a build-graph
  smell to eliminate.
- Feature classes constructed from a composition root should keep their
  public constructors simple and dependency-injectable (plain constructor
  parameters, no Fabric/Minecraft types), so that this wiring code stays a
  thin, mechanical "new the objects and call one method" shape rather than
  growing real logic. `HelloWorldMainMenuClientInitializer` is the reference
  example.
- This decision generalizes to every future feature with its own client
  entrypoint: the pattern (platform composition root directly wires a
  feature's service) is expected to repeat, not be reinvented per feature.
- `architecture.md`'s dependency table is clarified, not changed: the table
  describes dependencies between a layer's *business logic*; a module's
  composition-root/entrypoint code is understood to be a narrow, explicitly
  permitted exception whose job is exactly to cross layer boundaries for
  wiring purposes.
