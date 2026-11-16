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

- [x] In a session that may hold outside text, every string in a
  `session/update` reaches clients broken, except under a short list of
  keys known to be safe. The list is in one place and the Log names
  each key on it.
  - Receipt: relay.hub/kept-update-keys and kept-meta-keys; the Log
    names each key; docs/client-protocol.md lists them.
- [x] A test sends an update with a field relay does not know, holding a
  link, and finds it broken. It fails on today's code.
  - Receipt: `relay.inert-hub-test`: `a-field-relay-does-not-know-arrives-broken`,
    `a-text-chunk-s-other-fields-arrive-broken` and the property
    `no-link-in-an-update-reaches-a-client-whole`, live and replayed.
    All fail on the old inert-update.
- [x] `available_commands_update`'s descriptions are broken in such a
  session, or the Log says why they need not be.
  - Receipt: `a-field-relay-does-not-know-arrives-broken` checks a
    command's name, description and hint.
- [x] `bb verify` passes, and an adversarial Codex review finds nothing
  in scope.
  - Receipt: bb verify passes across relay.ec, relay-tui.ec,
    agentloop.ec and webtool.ec, fuzz and pentest included. Codex was
    out of quota, so a fresh read-only reviewer agent was given the
    threat model: round 1 found the replay's usage line (fixed), and
    round 2 found nothing in scope (see the Log).

## Plan

Related: relay#104, relay#135.

## Log

- 2026-11-16, relay-ec. relay.hub/inert-update breaks every string in
  an update now, map keys included, whatever its key or kind. Two sets
  in relay.hub list what passes as it came. kept-update-keys:
  - `sessionUpdate`: the kind of update.
  - `toolCallId`, `messageId`: ids a client matches on.
  - `kind`, `status`: a call's enums.
  - `currentModeId`: the mode's id.
  - `locations`: paths, which relay#135 asks about.
  kept-meta-keys holds only `agentloop/labels`, since what labels show
  is relay#140's. Inside a call's content, a block's `type`, a terminal's
  `terminalId` and a diff's `path` are kept. The path is kept because it
  is a path, like locations, so relay#135 covers both. A params field
  next to the update is broken too, except `sessionId`.
- 2026-11-16, relay-ec. available_commands_update's names, descriptions
  and hints are now broken like everything else, since they are not on
  the kept list. agentloop's command names hold no dot, so breaking them
  changes nothing a client would send back.
- 2026-11-16, relay-ec. The text chunk's other fields go the same way,
  and so does an update that passes a held word (relay#136). Breaking
  their strings changes nothing, since they hold labels, numbers, ISO
  times and ids. A dot before a digit stays, so `12:00:00.5Z` is sent
  as it came.
- 2026-11-16, relay-ec. The property
  `no-link-in-an-update-reaches-a-client-whole` builds random updates
  of known and unknown kinds and fields, with links in keys and values.
  Its oracle does not use relay's code: no string outside the kept keys
  may contain `evil.test`. On the old code it found a title sent as a
  list, which went out whole because only a string title was broken.
- 2026-11-16, relay-ec. Review round 1, by a fresh read-only reviewer
  agent given the threat model (Codex was out of quota until
  2026-11-19), found one defect in scope. keep-state kept the latest
  usage_update as the agent wrote it, and a replay sends that line
  before the rest, so a field like `extra` holding a link reached a
  client that reconnected. Nothing reaches it today, because agentloop's
  usage holds only numbers, "USD" and labels. Fixed: emit-lines now
  notes the usage line as it was sent. The property covers the replay
  too, and `the-replay-s-usage-is-the-one-sent-live` fails without the
  fix. The round's other checks found nothing in scope: updates and
  params of odd shapes, kept keys checked against what agentloop puts
  there, double escaping, and with-links. It noted that a display
  block's whole content item passes as it is. That is out of scope:
  only agentloop builds one, from relay's tools, and the agent is
  trusted.
- 2026-11-16, relay-ec. Review round 2, by a fresh reviewer agent:
  nothing in scope. It checked the replay in clean and contaminated
  sessions, a word held across a usage line, labels that change
  mid-stream, the end of a turn and of a conversation, params next to
  the update, and who chooses each kept key's value (agentloop alone).
  Of its out-of-scope notes, two came from an update that is not a map:
  a string threw, and a vector of pairs became a map whose kept key
  passed as it came. inert-update now breaks such an update whole
  (`an-update-that-is-not-a-map-is-broken-whole`). Two notes are left
  as they are because only agentloop, which is trusted, could cause
  them: a field outside params, and an agent notification other than
  session/update, which agentloop does not send.
