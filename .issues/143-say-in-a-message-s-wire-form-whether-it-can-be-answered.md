# Say in a message's wire form whether it can be answered

## Context

Sent by relay-macos-99 on 2026-11-17, from building Messages against docs/client-protocol.md's "A message's wire form". The text is that session's, lightly cut.

A ready item has `replyTo`, null when relay wrote the message itself and nobody can answer. A message in `_relay/messages` has nothing like it, so a client offers a reply bar on every message and learns only from `_relay/reply`'s refusal ("there is nobody to answer it"). Proposal: add `replyTo` (the number to give `_relay/reply`, or null) to a message's wire form, the same value its ready item carries.

## Acceptance criteria

- [ ] A message carries `replyTo`, equal to its ready item's, with a test, and docs/client-protocol.md says so.

## Plan

## Log
