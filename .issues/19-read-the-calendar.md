# Read the calendar

## Context

Read-only calendar access through EventKit. Invites are other people's text, so the results carry labels private and contaminated.

## Acceptance criteria

- [ ] A read-only calendar tool using EventKit.
- [ ] Results are labelled private and contaminated.
- [ ] No write path exists.
- [ ] Tests with a fake calendar source.

## Plan

## Log

- 2026-10-17: tools/cal landed on main (vxpzvxzq), with Swift escaping shared with rem (uupprymu). After deploy, build cal, approve Calendar access once in a terminal, and add cal to relay.edn.
