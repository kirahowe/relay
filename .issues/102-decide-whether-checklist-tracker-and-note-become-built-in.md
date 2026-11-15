# Decide whether checklist, tracker and note become built-in surfaces

## Context

Part of relay#93. The epic says A2UI replaces the closed set of card
kinds and the card wire form. The first build does less. A role-built
surface is a fourth card kind beside checklist, tracker and note, and
the three keep their events, ops and wire form (the coordinating
session's decision on 2026-11-14, recorded in relay#93). Until someone
decides otherwise, relay has two ways to put a checklist on the board.

The choices as they look now:

- Leave the three kinds as they are, with surfaces as the fourth.
- Make the three kinds surfaces that relay builds itself, so a client
  draws one thing. The log is append-only, so the fold goes on reading
  the `card/added` and `card/changed` events already in it.
- Stop making the three kinds and let roles build their own.

## For Kira

Wait until role-built surfaces have had real use, then choose.

## Acceptance criteria

- [ ] Role-built surfaces have been in real use, and what that showed is
  recorded here.
- [ ] Kira has decided, and the decision is recorded here and in
  relay#93.

## Plan

## Log
