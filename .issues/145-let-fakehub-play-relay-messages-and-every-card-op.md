# Let fakehub play _relay/messages and every card op

## Context

Sent by relay-macos-99 on 2026-11-17, from building Messages against docs/client-protocol.md's "A message's wire form". The text is that session's, lightly cut. This one is relay-tui's.

fakehub does not answer `_relay/messages`, and of the card ops it plays only tick, untick, archive and input. relay-macos tests those against hand-written lines in its core tests, with no round trip through a hub. A fakehub that played them would let a client test against the same stand-in for every method. (relay-macos also asked for `relay/inertText` and a chunk's `relay/links`; relay-tui qxlyolmz added both to fakehub on 2026-11-16.)

## Acceptance criteria

- [ ] fakehub answers `_relay/messages` and plays every card op relay has, with tests in relay-tui.

## Plan

## Log
