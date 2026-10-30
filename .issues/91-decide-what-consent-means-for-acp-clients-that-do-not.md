# Decide what consent means for ACP clients that do not declare relay/review

## Context

Follow-up from relay#79, which left this as a known gap. relay checks an
allow or trust against its review digest only for clients that declare
relay/review. A generic ACP client such as Agmente does not, so relay
cannot know what it showed the operator, and it answers as before. Its
allow counts without any proof that the operator saw the review text.

relay#79's note said to revisit this with relay#66. relay#66 is closed,
and its phone trial moved to relay#84, which asks Kira to record what
Agmente shows for an approval. That record is the input to this decision.

A decision here can change when a call is held, so it needs Kira.

## Acceptance criteria

- [ ] Kira has decided what an allow from a client without relay/review
  may do, with what relay#84 showed about Agmente in hand.
- [ ] relay enforces that decision, with tests, and spec/relay.allium,
  README.md and docs/client-protocol.md describe it.

## Log
