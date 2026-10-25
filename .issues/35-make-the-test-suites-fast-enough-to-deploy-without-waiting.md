# Make the test suites fast enough to deploy without waiting

## Context

`bb deploy` runs `bb test` and `bb scenarios` on fresh builds before it
asks for the code, and on 2026-10-17 that took long enough that a deploy
started through Claude Code's `!` hit its 120-second limit while still in
`bb test`. Kira wants the suites faster; this is a project for later.

## Acceptance criteria

- [x] Timings measured for `bb test`, `bb scenarios` and `bb pentest`,
      with the slowest tests named.
  - Receipt: the Measurements section below.
- [ ] A target agreed with Kira, and the suites brought under it without
      dropping coverage.

## Measurements

All on Kira's Mac (16 cores), in jj workspaces of main under
`~/code/projects/.next/t35`, with agentloop and acp-tui built beside them.

Before, on main (c28fa0a9):

- `bb test`: 4m 37s wall. The namespace times add up to about 560s, so
  volley's 8 workers barely overlapped. relay.deploy-test alone took 3m
  39s, and volley spreads work only by namespace, so the run waited on
  it. relay.serve-test (55s) was marked serial and ran alone after
  everything else.
- Slowest tests: relay.eval-test/every-oracle-passes-and-every-foil-fails-exactly-its-graders
  58s, relay.scenario-test/every-scenario-passes-against-the-mock-model
  33s, relay.serve-test/a-token-command-that-hangs-or-says-too-much-gives-no-listener
  23s, relay.render-test/the-log-is-one-line-per-event 20s,
  relay.agentloop-qa-test/killing-agentloop-at-random-moments-leaves-the-memos-consistent
  13s.
- `bb scenarios`: 38s, 28 of 28. It repeats
  every-scenario-passes-against-the-mock-model, which `bb test` already
  runs.
- So `bb deploy` spent about 5m 15s in tests.

After this work:

- `bb test`: 89 to 93s over six runs, 875 tests (two more deftests,
  from sharding), 5406 assertions, no failures. `--workers 12` and `16`
  give the same 91s.
- `bb deploy` runs `bb test` only: about 90s of tests.
- `bb pentest --jobs 4` (from `bb verify`): 130s. s5-acp-wrong-request-id
  takes 92s and fails, as does s5-acp-disconnect; both fail the same
  way on pristine main, so they are not from this work (another
  workspace filed them). s8-contaminated-routine takes 65s; no other
  scenario passes 14s. The four s3 mail pentests skip without a
  cleanroom binary.
- `bb verify`: agentloop 110s, acp-tui 58s, webtool 97s, relay 94s,
  pentest 130s.

What sets the 90s now: the parallel phase ends at about 70s, held by
relay.deploy-test and relay.deploy-wakes-test. Alone, the four deploy
namespaces finish in 41s; in the full suite they take 53 to 71s. The CPU
is mostly idle (230s of CPU in an 89s run). jj's writes are the
bottleneck: one `jj new` takes 40ms alone and 220ms with eight running
at once, because jj syncs to disk on every write and macOS serializes
full syncs across the system. Eight writers get barely more done than
one. relay's own log forces every append too. The serial phase after
it is about 22s (relay.serve-websocket-test 20s, relay.connect-test 2s).

## Plan

1. Measure, find the critical path. Done.
2. Split relay.deploy-test so volley runs its parts in parallel. Done.
3. Run the mock scenarios once in deploy and verify. Done.
4. Take the socket tests out of the serial phase. Done.
5. Shard the eval oracle checks. Done.
6. Agree a target with Kira. Going further than about 90s means fewer jj
   writes in the deploy tests (see the last judgment call).

## Judgment calls

- relay.deploy-test became four namespaces (deploy, deploy-wakes,
  deploy-interrupt, deploy-kill) along the file's existing review-round
  sections, with the shared fixture in relay.deploy-world. Each test
  copies a repo made once per set of make-repo! options instead of
  running nine jj commands for each of three repos. A copied jj repo
  works and keeps its commit ids; nothing in `.jj` holds the path.
- `bb scenarios` is out of `bb deploy` and `bb verify`, not out of
  `bb test`. relay.scenario-test plays the same scenario files the same
  way, and in deploy's check workspaces agentloop sits beside relay, so
  the test runs rather than skipping. `bb scenarios` stays as a command.
- Only relay serve's WebSocket tests stay serial. They take a port from
  free-port and then let the service bind it, the "fixed port" case in
  AGENTS.md. The socket tests each have their own socket, run directory
  and data directory, and passed in six full parallel runs.
- The token command test waits for the service's stderr line instead of
  sleeping 11s per case. The service's stderr goes to a file so the test
  can read it while the service runs. The check that the hanging
  command's child was killed waits up to 5s for it to go, since kill-tree
  signals and the process takes a moment to exit.
- The eval oracles are sharded by namespace (three, by place in the
  sorted case files), not threads: run-scenario! sets the relay.bin
  system property for the whole process, so concurrent scenarios in one
  process would race on it.
- Left alone: relay's log fsync (a durability guarantee), colocation of
  the test repos (it costs about 25% per jj write, but the live
  checkouts are colocated), and observe's four jj reads per checkout
  (reads do not slow under load; writes do).
- Not done, for Kira: the deploy tests are bound by jj writes, about 15
  to 20 per test, most of them deploy's own check workspaces (add and
  forget for three repos) and moves. Getting `bb test` well under 90s
  needs fewer of those, which means changing how deploy checks a commit
  (for example exporting it with `git archive` instead of making a jj
  workspace). That is a change to deploy, not to the tests.

## Log

- 2026-10-24: measured main (4m 37s for `bb test`, 38s for `bb
  scenarios`), split deploy-test, dropped the repeated scenarios run,
  split serve-test, sharded the eval oracles. `bb test` 89 to 93s over
  six full runs, all passing. `bb verify` passes every step but
  pentest, whose two failures (s5-acp-wrong-request-id,
  s5-acp-disconnect) fail the same way on pristine main.
