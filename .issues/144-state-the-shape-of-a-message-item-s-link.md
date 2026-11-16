# State the shape of a message item's link

## Context

Sent by relay-macos-99 on 2026-11-17, from building Messages against docs/client-protocol.md's "A message's wire form". The text is that session's, lightly cut.

"An item may carry `link`, a link relay built itself" gives no shape. relay-macos accepts a string address or an object with `address`, and follows it only when it has a live link's shape. Proposal: document it, presumably one http(s) address string in relay's link shape, and say whether a client may follow it without checking the live set. It is not in the set, and relay-macos does not check it there.

## Acceptance criteria

- [ ] docs/client-protocol.md gives `link`'s type and shape and says whether a client checks it against the live set, and a test holds relay to that shape.

## Plan

## Log
