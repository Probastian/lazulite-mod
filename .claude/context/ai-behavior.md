# AI Behavior

Before implementing:
1. Read relevant specifications.
2. Read architecture context.
3. Research official documentation if Minecraft APIs are involved.

Rules:
- Never redesign approved architecture.
- Never guess APIs.
- Explain trade-offs before major design decisions.
- Preserve consistency.
- Improve documentation alongside code.
- Stop and request a planning update if implementation deviates from plan.

Efficiency:
- Don't re-confirm an external fact (a library's version support, a documented API shape) a second time within the same session unless something specific suggests it changed or the first check was wrong. Re-verifying "just to be sure" with no new signal is wasted research.
- Don't re-read a file in full to "sanity check" it right after you finished writing/editing it yourself — you already know what it says.
- When a subagent's own instructions already tell it to consult a context doc, don't also restate that doc's rules in the delegating prompt.
