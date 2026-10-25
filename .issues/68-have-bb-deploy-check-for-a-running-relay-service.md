# Have bb deploy check for a running relay service

## Context

Raised by the independent review of #64 on 2026-10-22. `bb deploy` waits
for every running wake, and the service's conversations count. With
`relay serve` up, a deploy waits until it times out and then reports the
conversations. The README says to stop the service before a deploy and
start it after, which ends live conversations.

## Acceptance criteria

- [ ] `bb deploy` sees the service's lock for the live data directory and
  says at once to stop `relay.serve` (or, if Kira prefers, restarts it
  after the build, saying which conversations that ends).
- [ ] Kira chooses which of the two; deploying stays Kira's to run.

## Plan

## Log

- 2026-10-25: Dropped as a duplicate of #71, which now carries these
  criteria.
