# Bound what _relay/cards answers when it includes archived cards

## Context

Found by Codex in relay#97's review and reported by the relay#97 session
on 2026-11-14.

`_relay/cards` with `{"archived": true}` answers with every archived card
in one message. Nothing limits how many there are, and a client reads
lines of at most 10 MiB (relay-tui, `maxLine` in
`internal/agent/screen.go`). The active board is bounded: 30 cards, each
kind with its own limits, and a surface of at most 128,000 bytes as
sent. The archive is not.

This holds for every kind of card and did before surfaces. A surface can
weigh more than a checklist, so fewer archived cards are needed to reach
the line's limit.

## Acceptance criteria

- [ ] An answer that includes archived cards is always within what a
  client reads in one line, however many cards the archive holds.
- [ ] docs/client-protocol.md says how a client gets the rest.

## Plan

## Log
