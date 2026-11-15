# Show a card's text form when one of its members has the wrong type

## Context

relay-tui. Sent by the relay#100 session on 2026-11-15, from relay#100's
third review round. The reviewer did not count it as a defect of the
change.

The reviewer's text:

> A card with a mistyped card-level member is dropped whole, neither
> native nor its text form (internal/wire/relay.go, card). Seen with
> "progress": 3, "items": {}, "note": "x", "cells": "x", "rev": "1852"
> or 1852.5, "title": "Pack", "labels": "private", "id": 1840.0. A
> duplicate member name makes the whole answer unreadable. All need
> relay to break its contract, and it is the rule for every card kind.

relay#100 made the `surface` member fall back to the text form when it
is mistyped. The other members still fail the typed decode of the whole
card.

## Acceptance criteria

A first draft, for whoever builds this to tighten.

- [ ] A card with any one of the mistyped members the reviewer lists is
  shown as its text form, and the other cards in the same answer are
  shown as usual. A test covers each member.
- [ ] A card too broken to show at all, such as one with no usable id,
  is said to be unreadable in one line. It does not vanish.
- [ ] The Log says whether a duplicate member name still makes the whole
  answer unreadable. That is relay's strict JSON rule today.
- [ ] `make check` passes.

## Plan

## Log
