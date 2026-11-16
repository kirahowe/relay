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

- [x] Kira starts the service (`container system start`), or says it
  should stay stopped.
  - Receipt: Kira ran `container system start` on 2026-11-15;
    `container system status` reads `running` (client 1.7.1).
  - Receipt: `bb test relay.shell-live-test` then: 1 test, 1 passed,
    25 assertions, 43 s, on relay `integration` xsowxlyw.
- [x] With the service stopped, the test says in one line that Apple's
  container service is not running, and `bb verify` names that as the
  reason. It still fails: relay's rule is to fix a failing test, never to
  skip it.
  - Receipt: kxksvnmz "Say in one line that the container service is
    stopped".
  - Receipt: `bb test relay.shell-live-test` with the service stopped: 1
    test, 1 failed, 1 assertion, where it was seven failures and errors.
    The failure reads "Apple's container service is not running
    (container system status said: apiserver is not running and not
    registered with launchd). Start it with: container system start".
  - Receipt: `bb pentest s18`: `FAIL s18-shell-walls 0.0s` with the same
    line under it, `s18-house-settings` passes, exit 1.
  - Receipt: `bb verify` at kxksvnmz prints the line and "While it is
    stopped, relay (the shell's live test) and pentest (s18-shell-walls)
    cannot pass." before the first step and again under the table, and
    exits 1. agentloop, relay-tui, webtool and eventkit pass. relay: 1186
    tests, 1185 passed, 1 failed. pentest: 30 passed, 1 failed.
  - Receipt: `a-stopped-service-is-said-in-one-line` and the property
    `the-line-is-one-line-whatever-the-program-printed`
    (container_service_test.clj);
    `a-stopped-container-service-is-named-for-the-steps-it-fails`
    (verify_test.clj).

## Plan

1. One place asks whether the service runs and gives the line that says
   it does not: `relay.container-service`, under `test/`.
2. The shell's live test, the pentest runner and `bb verify` use it.

## Log

2026-11-14, the second criterion, built in workspace `i94`. Judgment
calls, all mine:

- `container system status` exits 0 only while the service answers, so
  any other exit is read as stopped. The line quotes the first thing the
  program printed, so a failure of another kind shows for what it is.
- The pentest runner now tells two cases apart. Something a scenario
  needs that is not installed is still a SKIP. Something installed but
  stopped is a FAIL with the reason, and the scenario is not started.
  The service is asked only when a scenario needs it.
- `bb verify` says it before the first step as well as under the table.
  A run takes about ten minutes and cannot pass, so the start is when
  the line is most use.
- Under the table it names only the steps that failed and hold a check
  on a real container. It says they cannot pass, not that nothing else
  failed.
- The helper sits under `test/` because only the checks use it. `relay
  house build` still prints the runner's own words when the service is
  stopped, and `relay status` already says to run `container system
  start`. Neither is changed here.

Not exercised: the path where the service runs. It is stopped on this
Mac and starting it is Kira's. In the live test that path is the old
body, unchanged, under the new `:else`. The first criterion is still
open and is Kira's.

- 2026-11-15, from the relay#93 session, as the coordinating session
  passed it on: Kira will start Apple's container service herself.
- 2026-11-15: Kira started the service, and the live shell test passes
  with it running (receipt above). The path where the service runs is
  now exercised.
- 2026-11-15 approved: Service is started (Kira, in issues tui; restated in the coordinating session's dialog)
