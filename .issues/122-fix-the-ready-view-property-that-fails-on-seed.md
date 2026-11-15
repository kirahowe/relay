# Fix the ready-view property that fails on seed 6561552371814650667

## Context

relay-tui. Found by the relay#100 session on 2026-11-15 while running
relay-tui's core properties deep. It is in the ready view, which
relay#100 does not touch.

    go test ./internal/core -run TestReadyInvariantsHoldForAnyRun \
      -rapid.seed=6561552371814650667 -rapid.checks=50000

fails at once with "a reply is open in a closed view"
(`ready_test.go:337`). The state has `Open` false, `Input` set,
`Sending` 4, `Sent` set and `Message` "Not sent." At the default number
of checks the test passes. A run of 50,000 checks found it after 36,165.

The relay#100 session reproduced it on relay-tui zxlqkvtp (72d0b337)
with its chain on top. The relay#93 session ran the same command on
relay-tui ae0ce7d2, the commit before any of that day's work, and it
fails there in the same way, so the fault is older than relay#95 and
relay#100. (Add `-rapid.nofailfile` to run it without writing a file
under `testdata`.)

Nobody has looked for the cause. Either the reducer leaves a reply open
when the view closes while a send is on its way and then fails, or the
invariant is too strict about that state.

## Acceptance criteria

- [ ] The command above passes, because the reducer or the invariant was
  changed, and the Log says which and why.
- [ ] The failing case is kept as a test of its own, so it runs at the
  default number of checks.
- [ ] `make check` passes.

## Plan

## Log
