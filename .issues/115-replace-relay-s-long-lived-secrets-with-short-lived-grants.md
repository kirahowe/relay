# Replace relay's long-lived secrets with short-lived grants

## Context

Sent by the relay-macos session on 2026-11-14, through the coordinating
session. The text is that session's own, and "I" below is that session.

Kira, 2026-11-14, in the relay-macos session: "let's stop thinking about
api keys and long lived tokens. those days are over. We need to be
thinking in terms of narrowly-scoped, short-lived grants. That is the
only secure way to share secrets anymore. long lived tokens that must be
manually rotated are simply insecure."

relay holds several secrets that last until someone rotates them by
hand. These are the ones the README names:

- the model API key that `:api-key-command` prints
- the Brave Search key
- the WebSocket listener's token, which every remote client sends
- the ntfy token
- the token in the calendar feed's address

I did not check how a `relay connect` sign-in keeps its tokens.

Two things I found while looking at replacements:

- agentloop takes only an API key (`api_key_command`, handed to the SDK
  as a key). A token that expires during a wake needs a command
  agentloop runs again.
- Anthropic's API gives a short-lived token in exchange for an identity
  token that a platform issues to a workload. A personal Mac has no such
  platform, so the model credential is the unsolved one. For a remote
  client, a key the device holds and signs each connection with could
  replace the WebSocket token.

## Acceptance criteria

A first draft.

- [ ] A list of every long-lived secret relay and its companions hold,
  with what could replace each.
- [ ] Kira has chosen which to replace first, and that work has issues.

## Plan

## Log
