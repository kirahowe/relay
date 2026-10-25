# Speed up the slowest remaining test namespaces

## Context

Follow-up to relay#35, which took `bb test` from 4m 37s to about 60s.
Kira accepted about 60s as the target for now, and asked for this issue
so the next namespaces can be picked up later.

volley runs namespaces in parallel on 8 workers and runs serial
namespaces alone after the rest, so the run takes about as long as its
longest namespace plus the serial phase. Adding workers does not help
(12 and 16 workers gave the same time on relay#35). Measured on
2026-10-25, in full parallel runs:

- relay.scenario-test 34s, nearly all of it in
  every-scenario-passes-against-the-mock-model, which plays 28 scenarios
  one after another.
- relay.agentloop-qa-test 31s, of which
  killing-agentloop-at-random-moments-leaves-the-memos-consistent is
  about 14s (3 chaos trials against a real agentloop).
- relay.render-test 20s, of which the-log-is-one-line-per-event is
  about 18s.
- relay.serve-websocket-test 20s, serial, so it runs alone after
  everything else.
- relay.eval-oracles-0/1/2-test 18 to 20s each.

## Ideas and tips

- **render-test is cheap to fix.** The property spends its time
  generating events, not rendering them: drawing 30 events takes about
  18ms, while rendering them takes under 0.4ms. `gen-event` builds
  `(gen/map gen/keyword gen/string-alphanumeric)`, and random keywords
  are slow under babashka's interpreter. A smaller data map (a few fixed
  keys with generated values), or a lower trial count, should take it
  from about 18s to a second or two without weakening what it checks.
- **scenario-test can be sharded the way relay#35 sharded the eval
  oracles**: a few namespaces, each playing every Nth scenario file.
  Threads will not do, because run-scenario! sets the relay.bin system
  property for the whole process (see relay.eval-oracles). Time each
  scenario first and balance the shards by time if a few dominate.
- **agentloop-qa's chaos test** runs 3 trials one after another. Moving
  it into a namespace of its own lets it overlap with the rest of that
  namespace's 18 tests; going from 3 trials to 2 would trade some
  coverage. Check where a trial spends its time before choosing.
- **The serial phase** is relay.serve-websocket-test (about 20s, half of
  it the token-command test, which waits out relay's real 10-second
  token-command timeout) and relay.connect-test (about 2s). These are
  serial because they take a free TCP port and then let the service bind
  it. If the service could bind port 0 and report the port it got, those
  tests could run in parallel, and the whole 20s would come off the end
  of the run.
- **Look for logic in a shell first.** The large win in relay#35 came
  from turning deploy's orchestration into a pure state machine
  (relay.deploy-flow), so its tests needed no jj. A namespace that is
  slow because it drives real processes to test decisions is a sign of
  the same thing.
- Measure with `/usr/bin/time -l bb test` and the namespace times in its
  output. jj and other tools that sync to disk on every write get slow
  when many run at once, so a test can be fast alone and slow in the
  full suite.

## Acceptance criteria

- [ ] The namespaces above measured again, and the slowest tests named.
- [ ] A new target agreed with Kira, and the suite brought under it
      without dropping coverage.

## Plan

## Log
