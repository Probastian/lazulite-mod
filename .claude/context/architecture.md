# Architecture

## Layering
API
↓
Services
↓
Features
↓
Platform

## API
Defines stable abstractions. No Minecraft imports.

## Services
Shared cross-feature capabilities:
- Config
- Event Bus
- Logging
- Scheduling
- Networking
- Resource access

Features communicate through services, never directly.

**Graduate-on-second-use rule:** `services/` starts empty and stays that way until a real second consumer exists. The first feature needing one of the above capabilities (e.g. config file I/O) owns a minimal implementation local to itself, scoped to its own needs — do not pre-build a shared service speculatively. Only when a *second* feature needs the same capability does it get extracted into `services/`, generalized just enough to serve both, with an ADR recording the extraction. This keeps `services/` demand-driven instead of guessed at.

## Features
Organize by business capability, not technology.

features/
  minimap/
    api/
    config/
    events/
    gui/
    mixins/
    resources/
    services/
    tests/

Each feature owns its implementation.

## Platform
Contains Fabric/NeoForge/Minecraft-specific adapters.

## Multi-version Strategy
Never maintain one Git branch per Minecraft version.

Prefer:
common/
platform/fabric-1.21/
platform/fabric-1.22/

Common contains business logic.
Platform contains version glue.

Never:
if (MC_VERSION == ...)

Instead:
Feature -> Platform API -> Version Adapter -> Minecraft.

## Dependency Rules

| Layer | Allowed Dependencies |
|-------|----------------------|
| API | none |
| Services | API |
| Features | API, Services |
| Platform | API |

Forbidden:
Feature -> Feature

**Shared-screen extension point (build only when a second consumer needs it):** The first feature to extend a given vanilla screen (e.g. `TitleScreen`) may define its own small `api` hook interface and platform adapter, exactly as `MainMenuHook` did — don't build a generic screen-extension service ahead of need. But once a *second* feature wants to extend the *same* screen, independent `ScreenEvents.AFTER_INIT` registrations from separate platform adapters can collide (overlapping widget placement, undefined ordering). At that point, introduce a `services/`-layer coordinator for that screen (e.g. a layout/stacking registry that hands out non-overlapping positions to registered extensions) rather than letting each feature keep guessing at offsets independently. Treat "a second feature wants the same screen" as the trigger, same as the graduate-on-second-use rule above.

**Composition-root exception:** "Platform -> API" governs business-logic dependencies. A platform module's client/main entrypoint is the composition root: it may import and construct concrete Feature classes purely to wire them up at startup (e.g. building a Feature's service with a Platform-supplied dependency and invoking it). This is not a Feature -> Platform or Platform -> Feature business-logic edge, just bootstrapping. See `docs/adr/0001-platform-composition-root-may-depend-on-feature-classes.md` for the reasoning; treat this note as that ADR's conclusion pulled forward so future features don't need to re-derive it.
