# Break every string in an agent's update unless its key is known safe

## Context

Sent by the relay#118 session on 2026-11-15, a follow-up to relay#104
from the coordinating session's read of it. relay#104 is on relay's
`integration` at rusxpqur (bd962f81). The text is the relay#118
session's:

> relay.hub's inert-update breaks the fields of a session/update it
> knows: title, name, content, rawInput, rawOutput, plan entries. In a
> session that may hold outside text, a string in an update kind or
> field it does not know goes to clients raw, and so do
> available_commands_update's descriptions. Today only agentloop writes
> those, and agentloop is trusted, so nothing outside reaches them. But
> a new agentloop field that copies model text would pass unbroken.
> Make the default deny: break every string in the update except a
> short list of keys known to be safe (sessionId, the ids, kind,
> status, locations), and test that an unknown field holding a link
> arrives broken.

`inert-update` is in src/relay/hub.clj. Its docstring says a call's
locations are left because they are paths. relay#135 asks whether that
is safe.

## Acceptance criteria

A first draft, mine, from the relay#118 session's text.

- [ ] In a session that may hold outside text, every string in a
  `session/update` reaches clients broken, except under a short list of
  keys known to be safe. The list is in one place and the Log names
  each key on it.
- [ ] A test sends an update with a field relay does not know, holding a
  link, and finds it broken. It fails on today's code.
- [ ] `available_commands_update`'s descriptions are broken in such a
  session, or the Log says why they need not be.
- [ ] `bb verify` passes, and an adversarial Codex review finds nothing
  in scope.

## Plan

Related: relay#104, relay#135.

## Log

