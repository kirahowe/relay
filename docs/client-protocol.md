# relay's client protocol

`relay acp` speaks the [Agent Client Protocol](https://agentclientprotocol.com)
to its client, whether it is a bridge to `relay serve` or runs the hub in
its own process. A plain ACP client needs nothing on this page. This page
lists what relay adds: every `_relay/*` method and notification, and every
`_meta` key relay sends or reads. relay-tui is the client that uses them;
each entry says what in relay-tui depends on it.

The code is the truth, and this page follows it: `relay.hub`
(`dispatch-request`, `client-notification`), `relay.workspace`,
`relay.dispatch` (`extension-handler`), `relay.acp` and `relay.serve`. If
they disagree, fix this page.

## Scope

Each method is about the connection or about one conversation.

- A **connection** method names no conversation and works on a connection
  that has none open. It takes no `sessionId`, and the board's methods
  refuse one like any parameter they do not take (-32602).
- A **session** method is about one live conversation. Its `sessionId`
  names a conversation this connection is attached to. Without one, relay
  takes the only conversation the connection is attached to or, before it
  has one, the conversation of the role `relay acp <role>` was given. A
  `sessionId` the connection is not attached to is refused (-32602).

## Errors

Errors are JSON-RPC errors. The codes that matter:

| code | meaning |
|---|---|
| -32601 | No such method. relay answers this for a `_relay/` method it does not have. A plain ACP agent answers it for every `_relay/` method, which is how a client tells relay from one. |
| -32602 | The params are wrong: an unknown parameter (named in the message), a missing one, or a conversation that is not live or not this connection's. |
| -32600 | The request cannot be taken: a request id already in use on this connection, `_relay/connect` anywhere but first on the socket, or a second `session/new` for a role still opening. |
| -32000 | relay refused it. The message is relay's reason, written for the operator, such as "Nothing was trusted; look again." |

A client should show an error's message escaped, as relay-tui does: it is
relay's text, but it can quote a card number or path the client sent.

## Methods: the connection

### `_relay/connect`

Sent by `relay acp` itself, never by a client. When `relay acp` is a bridge
to `relay serve`, its first line on the service's socket is this
notification, `{"approvals": bool, "role": string or null}`, which tells
the service what the connection may do: answer permission requests and
trust (`--approvals`), and which role `relay acp <role>` was given. relay
takes it only as the first line on the socket. A request anywhere else
gets -32600, and a notification anywhere else is dropped. A connection
over stdio (the hub in `relay acp`'s own process) is set up from its
command line instead.

### `_relay/roles`

- Scope: connection.
- Params: none.
- Answer: `{"roles": [Role]}`, every active role in `relay roles` order.
  A Role is `{"id", "title", "labels": [string], "working": bool,
  "holds": int, "conversation": null or Conversation}`. A Conversation is
  `{"sessionId", "openedAt", "lastActivity", "turn", "turns", "unread":
  bool, "approvals": int}`. Times are ISO-8601 instants. `turn` is
  `"idle"`, `"running"` or `"queued"` (a prompt waits for the role's
  scheduled work). `sessionId` can be null before the first turn. `holds`
  counts the role's calls held for the operator, `approvals` the
  conversation's permission requests asked and not answered.
- After it: `_relay/roles/changed` whenever any of it changes.
- relay-tui: the sidebar and list of roles, the marks beside each role,
  which conversation to attach to, and when to close what a replay left
  open (the turn going idle). relay-tui asks once, right after
  `initialize`.

### `_relay/cards`

- Scope: connection.
- Params: `{"archived": true}` to include archived cards, or `{}`.
  Anything else, `sessionId` included, is refused (-32602).
- Answer: `{"rev": int, "cards": [Card]}`. `rev` is the seq of the log's
  last event. Cards are in their wire form (below).
- Errors: -32000 "relay has not read the board yet. Try again." just after
  start.
- After it: the first call subscribes the connection to
  `_relay/cards/changed`.
- relay-tui: the board, which belongs to the app, not to a conversation.
  It asks once the connection is up (after `initialize` against the hub,
  after `session/new` against a single agent) and again on `r` or after a
  failure. A -32601 answer means the agent has no board: the pane shows
  the conversation's items, which is what keeps plain ACP agents working.

### `_relay/cards/op`

- Scope: connection.
- Params: `{"card": int, "op": string}` and the op's fields: `item`,
  `row`, `column` (ints), `state` (`"open"`, `"done"` or `"skip"`),
  `text`, and `seen` (the rev the client last saw). The ops are
  `retitle`, `archive`, `restore`, `add`, `tick`, `untick`, `edit`,
  `remove` (checklists), `set`, `add_row`, `add_column`, `rename_row`,
  `rename_column`, `remove_row`, `remove_column` (trackers) and `write`
  (notes); `relay.cards/ops` has the fields each needs. Any other
  parameter, `sessionId` included, is refused and named, so a typo such
  as `iten` or `sen` fails instead of passing as success.
- Answer: `{"card": Card, "changed": bool}`. `changed` is false when the
  op would change nothing, and then nothing is recorded.
- Errors: -32602 for the params; -32000 for relay's refusal, such as a
  card or item that does not exist, a limit, or a stale edit: an op that
  changes text is refused when that text was written after `seen`, an
  archive is refused when the card changed in any way after `seen`, and
  a `seen` past every rev in the log is refused too.
- The op is the operator's, with no labels, whether or not the
  connection takes approvals. Every other subscribed connection is sent
  the card in `_relay/cards/changed`; this one gets it in the answer.
- relay-tui: every key that changes a card. It sends `seen` with every
  edit, waits for the answer before taking another op, and on a refusal
  shows relay's reason and reopens the input with what was typed.

### `_relay/cards/trust`

- Scope: connection.
- Params: `{"card": int, "rev": int, "sha256": string}`: the card's rev
  and the `sha256` relay sent with it. Nothing else, `sessionId` included.
- Answer: `{"trusted": true, "card": Card}`, the card now clean.
- Errors: -32000 when the connection does not take approvals (the message
  names `relay trust card <id>`, to run in a terminal); -32000 "Nothing was
  trusted; look again." when the card changed since, or `rev` or `sha256`
  is not its current one; -32602 for the params.
- The log records the trust as the operator's, from the chat. The card's
  rev moves, so every subscribed connection is sent it.
- relay-tui: `t` on a card with outside text. y does nothing until a
  short delay has passed, the question closes if the card changes or the
  screen changes under it, and relay-tui sends the rev and digest of the
  card as it showed it.

## Methods: a session

### `_relay/seen`

- Scope: session.
- Params: `{"sessionId", "turn": int}`.
- Answer: `{}`, once the log records that the operator has read the
  conversation through `turn`.
- Errors: -32602 "There is no live conversation ..."; -32000 when the log
  refuses it.
- relay-tui: sent when the conversation is on screen, attached, scrolled
  to its end and idle, so the unread mark clears on every device.

### `_relay/conversations/end`

- Scope: session.
- Params: `{"sessionId"}`.
- Answer: `{}` once the conversation has ended. A running turn is
  cancelled first.
- Errors: -32602 when there is no live conversation by that id.
- relay-tui: ctrl+s, after asking.

### `_relay/items`

- Scope: session. Answered by the conversation's proxy (`relay.dispatch`),
  never by the agent.
- Params: `{"sessionId"}`.
- Answer: `{"items": [Item]}`, newest first. A shown doc is `{"kind":
  "shown", "ref", "path", "callId", "at", "title", "text", "sources":
  [{"title", "ref"}], "labels", "version", "sha256", "canTrust": bool,
  "trustCommand"}`. `text` is what trusting it vouches for and `sha256`
  its digest. A proposal is `{"kind": "proposal", "callId", "tool",
  "input", "preview", "labels", "status", "at"}` with `"fixedArgs"` when
  the operator's settings fix some of its arguments. `status` is
  `"waiting"`, `"allowed"`, `"rejected"` or `"cancelled"`. `canTrust` is
  false for every item on a connection without approvals.
- relay-tui: the conversation's workspace items. Asked when the
  conversation attaches and whenever a display or proposal arrives. A
  -32601 answer makes the pane fall back to the items relay-tui saw
  itself.

### `_relay/resolve`

- Scope: session, answered by the proxy.
- Params: `{"sessionId", "ref"}`, a reference as the model wrote it, such
  as `[[fact:12]]`.
- Answer: `{"ref", "kind", "status"}`, with `"title"`, `"text"`,
  `"labels"` and `"target"` when relay has them. `status` is `"ok"`,
  `"missing"` or `"external"`.
- Errors: -32602 "_relay/resolve needs a ref."
- relay-tui: the references cited in the latest reply, each asked once.

### `_relay/trust`

- Scope: session, answered by the proxy.
- Params: `{"sessionId", "path", "version": int, "sha256"}`, as
  `_relay/items` listed them.
- Answer: `{"trusted": true, "labels": [string]}`, the doc's labels now.
- Errors: -32000 when the connection does not take approvals (the message
  names `relay trust doc <path>`), when the doc is not one this
  conversation showed, or when it changed since it was listed.
- relay-tui: `t` on a contaminated shown item, after the same delay as a
  card's trust.

## Notifications from relay

| method | scope | params | relay-tui |
|---|---|---|---|
| `_relay/roles/changed` | connection | `{"roles": [Role]}`, the whole roster, sent when any of it changed since this connection was last sent it | replaces the roster whole |
| `_relay/cards/changed` | connection | `{"rev", "cards": [Card]}`, every card whose rev is not the one this connection was last sent, archived cards included so the client can drop them | updates the one board, open conversation or not; a changed card brings the board into view unless the operator is busy in the pane or a proposal waits |
| `_relay/replayed` | session | `{"sessionId", "truncated": bool}`, after the replay that follows `session/load` or an attaching `session/new`; `truncated` says the oldest updates were dropped | marks the conversation loaded, says so at the top when truncated, and stops calls the replay left open once the roster says the turn is idle |
| `_relay/permission/settled` | session | `{"sessionId", "toolCallId", "outcome"}`, sent to every other connection that was asked when one connection answers a permission request, or relay settles it | closes the dialog and notes "answered elsewhere" |

relay sends `_relay/cards/changed` only to a connection that has called
`_relay/cards`, and `_relay/roles/changed` only to one that has called
`_relay/roles`. A replay starts with the session's latest labels (a
`session_info_update` carrying `agentloop/labels`) and usage, and keeps
consecutive text chunks of one kind merged.

relay drops any line from the agent whose method starts with `_relay/`,
and any response from the agent to a request it was not passed. Only relay
speaks these methods to the client.

## `_meta` keys

| key | where | set by | meaning | relay-tui |
|---|---|---|---|---|
| `relay/hub` | `initialize` answer | relay | `1`: this is relay's hub, with the methods above | switches to hub mode; without it, single-agent mode |
| `relay/keeps` | `initialize` answer | relay | whether a conversation outlives this client: true when `relay serve` holds it | says "relay service not running: conversations end when you close this" while false |
| `relay/role` | `session/new` request | client | the role to open or attach to; without it, the `<role>` `relay acp` was given | names the role of each conversation it opens |
| `relay/role` | each `session/list` entry | relay | the role whose conversation it is | not read |
| `relay/attached` | `session/new` answer | relay | `true`: the role's live conversation was attached to, and its replay follows | not read |
| `relay/display` | a text block in a tool call's content | agentloop, from relay's tools | the display, `{"title", "body", "labels", "sources", "ref"}`; relay makes the block's own text inert and passes the display on unchanged | the workspace items; the block's text is not shown as the call's output |
| `relay/preview` | the preview item of a permission request's `toolCall.content`, and its text block | relay | `true`: relay's description of what the call would do, from the tool's `:preview` | the preview above the request's input, always defanged |
| `relay/labels` | the preview's text block | relay | the labels of the preview's text, apart from the session's | defangs the request's input by them too, and labels the proposal |
| `agentloop/labels` | tool calls and `session_info_update`s | agentloop; relay sends the latest first in a replay | the session's labels | the status line, and defanging once contaminated |
| `agentloop/fixed_args` | a held call's tool call | agentloop | the arguments the operator's settings fix | marks them "set by your settings" |

Relay's README describes what relay does with each of these: when it
passes a permission request on, how it builds a preview, and what it does
with a line that breaks the strict JSON rule.

## A card's wire form

Cards use string keys and are ready to show. relay has already made every
text field safe: hidden characters shown as `\u{XXXX}`, and text whose
labels are contaminated defanged, with `"outsider": true` beside it. A
client renders what it gets and works nothing out for itself. `text` is
the card's text form, for a client that does not know its kind. A card
with contaminated text also has `"sha256"`, the digest of that text form,
which is what `_relay/cards/trust` sends back with the card's `rev`. A
clean card has no `sha256`.

```json
{"id": 1840, "kind": "tracker", "status": "active", "complete": false,
 "rev": 1842, "owner": "hub", "addedAt": "2026-10-20T15:03:00Z",
 "title": {"text": "Catch up monthly financials", "outsider": false, "rev": 1840},
 "progress": {"done": 6, "total": 24},
 "labels": ["private"],
 "rows": [{"id": 1, "text": "BMO", "outsider": false, "rev": 1840}],
 "columns": [{"id": 1, "text": "Jun", "outsider": false, "rev": 1840}],
 "cells": [{"row": 1, "column": 1, "state": "done"}],
 "text": "Card 1840: ..."}
```

A checklist has `"items": [{"id", "text", "done", "outsider", "rev"}]`
and a note `"note": {"text", "outsider", "rev"}`. Only cells that are not
open are listed, and a note has no `progress`. An element's `rev` is
when its text was last written, which is what `seen` is checked against.
