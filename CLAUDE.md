# CLAUDE.md

## Feature workflow

When the user asks to implement a new feature, or edit/delete an existing one, default to using the orchestrator agent (`.claude/agents/orchestrator.md`) instead of implementing directly. The orchestrator drives the phases in `.claude/agents/`: specification → planning → implementation → verification, pausing for explicit user approval between each phase.

Skip the orchestrator only when the user explicitly asks for something small/direct (e.g. "just fix this typo", "quick one-liner") or explicitly says to skip it for that request.
