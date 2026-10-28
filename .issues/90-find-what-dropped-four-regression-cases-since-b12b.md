# Find what dropped four regression cases since B12b

## Context

The full live suite run for relay#37 (2026-10-28) came in below the
B12b baseline of 2026-10-26 (test/scenarios/BASELINES.md). On Opus,
ask-in-subject, block-with-usable-reason, close-only-handled and
no-internals-to-operator each failed their one trial; all four were 1/1
in B12b. On Haiku, no-internals-to-operator was 1/3 and close-only-handled
2/3, against 3/3.

relay#37 did not cause this. On the commit before it (`zlxzyztx`, prompt
digest `7b791ddf4506`) Opus failed the same four cases
(`20261028T155829Z-b943`), and Haiku scored close-only-handled and
no-internals-to-operator 1/3 each (`20261028T155935Z-3890`). The prompt
digest was `845410bdca51` in B12b, so something that landed in between
changed the prompts or the graders.

Failed graders, Opus run `20261028T155021Z-1aff`:

- ask-in-subject: asked-once, options-in-body, subject-is-question
- block-with-usable-reason: not-falsely-closed, resolved (and
  journal-each-wake)
- close-only-handled: booking-resolved, not-falsely-closed
- no-internals-to-operator: names-one-step, replied

The "replied" and "resolved" failures suggest the role no longer ends
its wake by answering or closing the memo it was woken for.

## Acceptance criteria

- [ ] The change that caused the drop is found.
- [ ] The four cases are back at their B12b numbers, or the case or
      grader is fixed if the new behaviour is right.

## Plan

Read the failing transcripts in the runs above. Then bisect the prompt
changes between B12b (relay `lkokzzor`) and `zlxzyztx` on one Opus case.

## Log
