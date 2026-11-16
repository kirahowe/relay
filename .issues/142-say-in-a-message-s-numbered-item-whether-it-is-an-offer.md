# Say in a message's numbered item whether it is an offer

## Context

Sent by relay-macos-99 on 2026-11-17, from building Messages against docs/client-protocol.md's "A message's wire form". The text is that session's, lightly cut.

A daily note's item in `_relay/messages` carries `"action": {"op", "n", "done"}`, but nothing says whether it offers help that can be turned down. Ready's `acts` carry `offer` for this. Without it a client cannot tell when "decline" is allowed, so relay-macos never shows "Not now" there and leaves the refusal to relay. Proposal: add `"offer": bool` to `action`, meaning what it means in `acts`.

## Acceptance criteria

- [ ] A message item's `action` carries `offer`, the same value as the ready item's act, with a test, and docs/client-protocol.md says so.

## Plan

## Log
