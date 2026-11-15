# Bound the live links a client is sent

## Context

Sent by the relay#95 session on 2026-11-14.

`_relay/links` answers with the whole set of live links, and relay-tui
holds it for as long as it is connected (relay#95). The set only grows:
a scout's memo can add up to 100 links, and nothing leaves it except a
drop by the operator. After months of scouting the answer could run to
tens of thousands of addresses, sent whole to each client that connects.

It is not a safety problem. A client only checks membership, and a link
that is missing from its set is drawn broken. It is a cost that grows
without anyone having decided it should. The coordinating session raised
it on 2026-11-14 while reading relay#95's relay side. The relay#94
session raised a cap on live links per host for another reason.

## Ways to bound it

To choose between when this is taken up:

- Send a client only the links that a card, a message or a ready item it
  can be sent still holds.
- Let a scout's link lapse when nothing has shown it for some time. A
  link the operator kept never lapses.
- The cap on live links per host.

## Acceptance criteria

- [ ] The `_relay/links` answer has a stated bound.
- [ ] A link relay sends a client whole is always in the set that client
  holds.
- [ ] A link the operator kept is never dropped by the bound.

## Plan

## Log
