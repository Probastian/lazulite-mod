# CLAUDE.md

## Feature workflow

When the user asks to implement a new feature, or edit/delete an existing one, default to using the orchestrator agent (`.claude/agents/orchestrator.md`) instead of implementing directly. The orchestrator drives the phases in `.claude/agents/`: specification → planning → implementation → verification, pausing for explicit user approval between each phase.

Skip the orchestrator only when the user explicitly asks for something small/direct (e.g. "just fix this typo", "quick one-liner") or explicitly says to skip it for that request.

## Main-thread token discipline

The user runs this project on a token budget and wants the main conversation kept thin. Two rules apply to every request in this repo, including small ones:

1. **Delegate to a background Agent by default.** For every user request — including the orchestrator's own phases (spec/plan/implement/verify each run as their own background Agent call) — don't do the work inline in the main thread. Instead:
   - Condense only the context actually needed for that task (relevant file paths, prior decisions, the specific ask) into the subagent prompt. Never paste the full prior conversation.
   - Launch it via the `Agent` tool with `run_in_background: true` (the default) so the main thread stays free.
   - Report the agent's findings back concisely once it completes; don't re-derive or re-verify what it already did unless there's a specific reason to distrust it.
   - Exception: trivial acknowledgements, clarifying questions, or a single tool call that's cheaper to just run directly (e.g. one `Read` to answer "what does this file say") don't need a subagent wrapper — use judgment, but default to delegating.

2. **Nudge toward a new session as the conversation grows.** Track roughly how large the current conversation has gotten (turn count, number of background agents spawned, how much large tool/agent output has accumulated). Once it starts feeling heavy — roughly 30-40 turns, or after a couple of large subagent result dumps have landed in the main thread — proactively tell the user it's a good point to start a fresh session, and briefly summarize the state they'd want to carry over (open threads, pending approvals, key file paths). Don't be naggy about it — mention it once, not every turn.
