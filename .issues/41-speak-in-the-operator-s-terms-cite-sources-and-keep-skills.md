# Speak in the operator's terms, cite sources and keep skills

## Context

Part of the personal-agent epic Kira started on 2026-10-18. Design
sections D5 (relay side), D6, D10 of the epic design, which is kept with these issues as
.issues/personal-agent-design.md. Kira's principles: assume the model is
fully compromised; nothing acts in the real world for the operator without
explicit consent, given at the time or in advance; roles draft and the
operator reviews; answers cite sources; the operator never needs to know
relay's internals.

## Acceptance criteria

- [ ] Every relay read tool prints each item's [[kind:id]] ref, and a pure resolver checks a ref against the log.
- [ ] The shared role instructions require refs for claims, keeping skills and a charter for each role, and never naming relay's internals to the operator.
- [ ] Skills live at skills/<role>/<slug>.md. The role's system prompt lists them, under the label filters.
- [ ] The hub's default instructions are rewritten to match.
- [ ] Eval cases: no internals in replies, citations present and resolvable, writing a skill, using a skill, and placeholders for unknown facts.

## Decisions

## Log
