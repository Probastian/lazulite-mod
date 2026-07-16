# Feature Guidelines

Every feature should be self-contained.

Required layout:
- api/
- config/
- events/
- gui/
- mixins/
- resources/
- services/
- tests/
- README.md

These are Java sub-packages under the feature's base package (`src/main/java/.../<feature>/api`, `.../config`, etc.), plus standard `src/main/resources/` and `src/test/java/...` for `resources/`/`tests/` — not literal flat directories directly under the feature folder. Every other module in this repo uses the standard Gradle/Java source layout; a feature is no exception. A sub-package may be empty (a `package-info.java` placeholder documenting why) if the feature has nothing to put there yet — e.g. `gui/` and `mixins/` are necessarily empty for any feature whose GUI/mixin code must live in `platform/` instead (see Dependency Rules in `architecture.md`).

**`mixins/` is permanently a placeholder, not just empty for now.** A `@Mixin` class by definition targets `net.minecraft.*` classes, and the layering forbids `net.minecraft.*` imports outside `platform/fabric-*`. So if a feature genuinely needs a mixin, that class cannot live in the feature's own `mixins/` package no matter how the feature grows — it must live under `platform/fabric-<version>/.../mixins/`, registered in that platform module's `*.mixins.json`. Don't go looking for it in the feature folder.

For any `gui/`-touching work (screens, widgets, HUD elements), see `.claude/context/ui-guidelines.md` for the established patterns (when a mixin is actually required vs. a non-mixin overlay widget suffices, reusable-widget discipline, texture asset conventions) before designing new UI from scratch.

Each feature owns:
- configuration
- resources
- localization
- commands
- tests
- documentation

This repo is intended for a large Steamworks-driven mod. Feature authors
should design with Steam Cloud, Steam Friends, join-from-friends, Workshop
support, server discovery, and future Steamworks capabilities in mind.

Features expose stable APIs but never depend on other features directly.
