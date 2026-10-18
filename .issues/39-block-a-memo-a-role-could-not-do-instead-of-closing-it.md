# Block a memo a role could not do instead of closing it

## Context

In a close-only-handled live trial (2026-10-18), the clerk could not
make a booking it was asked for. It told the hub so and then closed the
memo. The shared instructions say to block a memo you cannot do. Closing
it hides the problem from the dead-letter path. No grader checks this
yet.

## Acceptance criteria

- [ ] A grader on close-only-handled (or a new case) fails when a memo
      the role could not do is closed, with a foil that shows it.
- [ ] Haiku and Opus pass it, or the miss is recorded in BASELINES.md.

## Plan

## Log
