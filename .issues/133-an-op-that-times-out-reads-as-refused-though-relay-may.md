# An op that times out reads as refused though relay may still apply it

## Context

relay-tui. Sent by the relay#100 session on 2026-11-15, from relay#100's
fourth review round. It is how the board behaves on trunk for every op,
and relay#100's chain does not fix it. The reviewer reasoned it out and
did not reproduce it.

The reviewer's text:

> An op that times out at 15 s is shown as "Refused." with "context
> deadline exceeded", and relay may still apply it. relay then counts
> this connection as sent the new rev (relay.hub/card-op-result), so no
> notification follows and the box stays drawn unticked until a reload.
> Base behaviour for every op; not a break of guarantee 3. Confidence
> medium.

The relay#93 session read both ends before filing and did not reproduce
it either.

- relay, `integration` at 3cc67f7a. When an op succeeds,
  `relay.hub/card-op-result` records the card's new rev against the
  connection that sent the op, answers it, and then sends every other
  connection what it lacks. A client that had stopped waiting is counted
  as holding the new rev.
- relay-tui, `integration` at 13c740c5. The wait is `extTimeout`, 15 s,
  in internal/agent/agent.go. The notice is `refusedNotice`, "Refused.",
  in internal/core/board.go.

## Acceptance criteria

The first is the relay#100 session's suggestion.

- [ ] A timed-out op says it got no answer and does not say relay
  refused it. The board then asks relay for the card again. A test with
  the fake hub holds an answer past the timeout.
- [ ] `make check` passes.

## Plan

## Log

