# Stop the hub notifying twice for one stuck memo

## Context

In every close-only-handled baseline trial read (Haiku and Opus,
2026-10-18), the hub sent a memo to the operator about a request it could
not finish and then also blocked its own memo. The block sends a second
notification ("work abandoned") for the same problem. Each extra ping
weighs on the operator, which is what the week of real use is meant to
measure.

## Acceptance criteria

- [ ] One stuck request reaches the operator's phone once.
- [ ] An eval case (or a grader on close-only-handled) fails when it
      reaches the phone twice, with a foil that shows it.

## Plan

Decide between a prompt fix (the hub blocks, or memos, not both) and a
relay fix (a block by the hub that already memoed the operator about the
same memo does not notify again). Check `relay week`'s notification
counts from the real week first, to see how often it happens.

## Log
