# Keep the seed when core-test's any-sequence property fails in bb verify

## Context

Reported by the relay#94 session on 2026-11-14, through the coordinating
session. `relay.core-test/the-rules-hold-under-any-sequence-of-commands`
failed once in a full `bb verify` on the relay#94 chain rebased onto
kxksvnmz. The run's output was filtered, so the seed was lost. A second
full run passed, and so did `bb soak 20000` (seed 1794707077239). The
property generates no link commands, so the failure is probably not
relay#94's, but nobody has shown that.

## Acceptance criteria

- [ ] The failure is reproduced and fixed with a test, or the test runner
  keeps every failing seed where filtering the output cannot lose it,
  such as a file under the run's output, so the next failure can be
  replayed.

## Plan

## Log
