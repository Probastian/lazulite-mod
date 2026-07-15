# Mod Boilerplate

Repository skeleton for a long-lived, multi-version Minecraft mod.

This is not a mod yet — it's a starting point. The Gradle build, module
layout, and dev workflow are all in place; `platform/fabric-26.2/` still
contains the stock Fabric example code (`template-mod` / `de.probastian.boilerplate`)
as a placeholder to be renamed once a real mod is started.

Repository highlights:
- Feature-first architecture
- Platform abstraction
- Per-feature spec/plan/verification docs, kept alongside the code they describe
- ADR support
- AI-oriented development workflow

## Requirements

- JDK 25
- An IDE with Gradle support (IntelliJ IDEA or Eclipse/VS Code with the
  Java + Gradle extensions)

## Getting started

Clone the repository, then let Gradle set itself up:

```
./gradlew build
```

The first run downloads Gradle, Minecraft, mappings, and Fabric API, so it
takes a while. Subsequent builds are incremental.

Import the project into your IDE as a Gradle project (IntelliJ: *Open* on
the root `build.gradle`; it will pick up all subprojects automatically).

### Running the mod

Loom generates run tasks on the platform module that actually applies the
Fabric plugin:

```
./gradlew :platform:fabric-26.2:runClient
./gradlew :platform:fabric-26.2:runServer
```

The first `runClient`/`runServer` also generates IDE run configurations
(IntelliJ: *Minecraft Client* / *Minecraft Server*; Eclipse: corresponding
launch configs) so you can launch and debug directly from the IDE afterward.

For VS Code, `.vscode/launch.json` has a "Minecraft Client" debug
configuration. It reads a machine-generated `launch.cfg` that isn't
committed (it lives under the gitignored `.gradle/` cache), so run this once
after cloning before using it:

```
./gradlew :platform:fabric-26.2:configureClientLaunch
```

## Project structure

```
api/                      stable abstractions, no Minecraft imports
common/                   shared business logic, version-independent
services/                 cross-feature capabilities (config, events, logging, scheduling, networking, resources)
features/                 self-contained feature modules, each with its own
                          specification.md, implementation-plan.md,
                          verification-report.md and README.md
libraries/                reusable, non-Minecraft-specific utility code
platform/
  fabric-26.2/            Fabric loader glue for Minecraft 26.2
docs/
  adr/                    architecture decision records
.claude/                  AI agent workflow: context docs, agent roles, templates
```

### Layering and dependency rules

```
API
 ↓
Services
 ↓
Features
 ↓
Platform
```

| Layer    | Allowed dependencies |
|----------|-----------------------|
| API      | none |
| Services | API |
| Features | API, Services |
| Platform | API |

Feature-to-feature dependencies are forbidden — features only ever talk to
each other through services.

### Multi-version strategy

Never branch the repo per Minecraft version. Instead:

```
common/
platform/fabric-26.2/
platform/fabric-<next-version>/
```

`common/` (plus `api`, `services`, `features`) holds version-independent
business logic. Each `platform/fabric-<version>/` module is a thin adapter
that wires that logic into a specific Minecraft/Fabric version. Business
logic never contains version checks (`if (MC_VERSION == ...)`); instead the
platform layer swaps the adapter implementation.

To add support for a new Minecraft version, create a new
`platform/fabric-<version>/` module (see the existing one as a template),
add it to `settings.gradle`, and give it its own `gradle.properties`-driven
version numbers.

### Adding a feature

Each feature lives under `features/<name>/` and is self-contained:

```
features/<name>/
  api/
  config/
  events/
  gui/
  mixins/
  resources/
  services/
  tests/
  README.md
```

A feature owns its configuration, resources, localization, commands, tests,
and documentation. It exposes a stable API but never depends on another
feature directly — shared behavior goes through `services/`.

## Development workflow

Every feature moves through the same phases, each gated by explicit user
approval before the next one starts:

1. **Specification** — what and why
2. *Approval*
3. **Planning** — how
4. *Approval*
5. **Implementation**
6. *Approval*
7. **Verification** — whether requirements were fulfilled

This is enforced by the agent setup in `.claude/agents/` (`orchestrator`,
`specification`, `planner`, `implementer`, `verifier`) and the templates in
`.claude/templates/`. The orchestrator never skips a phase, never implements
during planning, and never edits a specification itself.

### Documentation

Every feature's own folder under `features/<name>/` holds:
- `specification.md` — explains *why* (see `.claude/templates/specification.md`)
- `implementation-plan.md` — explains *how* (see `.claude/templates/implementation-plan.md`)
- `verification-report.md` — explains *whether* requirements were met (see `.claude/templates/verification-report.md`)
- `README.md` — the feature's own developer-facing docs

Every public class and method requires JavaDoc, and public APIs need a usage
example inline in that JavaDoc (e.g. a `{@code ...}` block). Documentation is
updated alongside code, not after.

Significant architectural decisions get an ADR under `docs/adr/`.

## Coding style

- Composition over inheritance
- Constructor injection over globals/static state
- Builders for complex objects
- Final classes by default, small focused classes
- Explicit naming, consistent package structure
- Keep business logic independent of Minecraft — wrap verbose Minecraft
  APIs behind adapters (e.g. `Registrar.item(...)` instead of raw
  `Registry.register(...)`)

## Minecraft development notes

Documentation priority when implementing against Minecraft/Fabric APIs:

1. Official Fabric documentation
2. Fabric API source
3. Fabric API JavaDocs
4. Mojang mappings
5. Parchment
6. Sponge Mixin Wiki
7. Minecraft source
8. Minecraft Wiki
9. Community articles

Never invent APIs — verify against the above before implementing rendering,
mixins, registries, networking, commands, data generation, world
generation, or entity AI. Prefer Mojang mappings; use Parchment for
parameter names/docs; never expose Yarn/intermediary names in public APIs.

## License

CC0 — see [LICENSE](LICENSE). Inherited from the Fabric example mod
template; reconsider once this becomes a real, published mod.
