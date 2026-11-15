# The live shell test fails while Apple's container service is stopped

## Context

Found on 2026-11-14 while verifying relay#94. On the untouched baseline
(a1947aa8), `relay.shell-live-test/the-shell-runs-in-a-real-container-with-no-way-out`
fails at its first step:

    The shell's image did not build:   See 'container --help' for more information.

`container system status` on this Mac says "apiserver is not running and
not registered with launchd". The CLI is 1.7.1. Every later assertion in
the test fails because the image was never built, so `bb test` and
`bb verify` report it whatever else changed.

Starting the service registers a launchd job, which AGENTS.md leaves to
Kira, so no agent did.

## Acceptance criteria

- [ ] Kira starts the service (`container system start`), or says it
  should stay stopped.
- [ ] With the service stopped, the test says in one line that Apple's
  container service is not running, and `bb verify` names that as the
  reason. It still fails: relay's rule is to fix a failing test, never to
  skip it.

## Plan

## Log
