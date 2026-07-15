# Project Philosophy

## Mission
This repository is a long-term software platform for developing a large,
multi-version Minecraft client mod. Every decision should optimize for:
- Maintainability
- Readability
- Discoverability
- Testability
- Multi-version compatibility
- Extensibility

## Core Principles
- Prefer long-term architecture over short-term convenience.
- Feature-first organization.
- Official documentation takes precedence over tutorials.
- Business logic should be independent of Minecraft internals.
- Public APIs require JavaDoc and examples.
- Significant architectural changes require an ADR.

## Architectural Values
- Composition over inheritance.
- Stable abstractions around unstable Minecraft APIs.
- Keep version-specific code isolated.
- Self-contained features.
- Thin platform adapters.
- Small, focused classes.

## Things to Avoid
- Feature-to-feature dependencies.
- Global state.
- Leaking Minecraft classes into common code.
- Premature optimization.
