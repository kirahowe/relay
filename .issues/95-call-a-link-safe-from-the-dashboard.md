# Call a link safe from the dashboard

## Context

relay#94 lets Kira add a link to relay's live links from the command
line. In the dashboard she should do it where she reads. On a ready item
or a card with a broken link, one key lists its links, and choosing a
broken one asks once and then opens it.

relay-tui also breaks links in conversation text itself, from the
session's labels. It needs relay's live links to leave those whole.

## Acceptance criteria

- [ ] A key on a ready item or a card lists the links in it, live and
  broken, each in full.
- [ ] Choosing a broken link asks before it is added, and shows relay's
  refusal when there is one.
- [ ] Conversation text leaves a live link whole.
- [ ] docs/client-protocol.md describes the method and what the client is
  sent.

## Plan

Needs relay#94. Mock the list and the question before building.

## Log
