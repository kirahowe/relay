# Do not panic on Markdown quoted deeper than half the width

## Context

relay-tui. Sent by the relay#95 session on 2026-11-15, from the confirm
round on relay#95. It is older than relay#95 and is not a security
finding.

The reviewer's text:

> Crash, pre-existing. `markdown.go:122` panics on a negative width. 45
> nested `> ` then `---` at width 80 does it (ran). It needs nesting
> deeper than half the width, so a targeted attacker: out of scope.

The relay#95 session's note: on a phone's 40 columns that is 21 levels,
which a model can write without anyone aiming at it, and a panic takes
the dashboard down until it is started again.

## Acceptance criteria

- [ ] A test draws 45 nested `> ` followed by `---` at every width from
  1 to 80 and nothing panics. It fails on today's code.
- [ ] `make check` passes.

## Plan

## Log
