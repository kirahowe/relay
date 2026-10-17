# Make the test suites fast enough to deploy without waiting

## Context

`bb deploy` runs `bb test` and `bb scenarios` on fresh builds before it
asks for the code, and on 2026-10-17 that took long enough that a deploy
started through Claude Code's `!` hit its 120-second limit while still in
`bb test`. Kira wants the suites faster; this is a project for later.

## Acceptance criteria

- [ ] Timings measured for `bb test`, `bb scenarios` and `bb pentest`,
      with the slowest tests named.
- [ ] A target agreed with Kira, and the suites brought under it without
      dropping coverage.

## Plan

## Log
