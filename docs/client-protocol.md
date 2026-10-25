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
  and the `sha256` relay sent with it, which is the digest of the card's
  review (see [Reviews](#reviews)). Nothing else, `sessionId` included.
- Answer: `{"trusted": true, "card": Card}`, the card now clean.
- Errors: -32000 when the connection does not take approvals (the message
  names `relay trust card <id>`, to run in a terminal); -32000 "Nothing was
  trusted; look again." when the card changed since, or `rev` or `sha256`
  is not its current one; -32602 for the params.
- The log records the trust as the operator's, from the chat. The card's
  rev moves, so every subscribed connection is sent it.
- relay-tui: `t` on a card with outside text opens the card's review;
  relay-tui sends the rev and the review's digest.

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
  "trustCommand", "review"}`. `text` is the doc's content as it is,
  `review` the doc as the operator consents to trusting it (see
  [Reviews](#reviews)), and `sha256` the review's digest. A proposal is `{"kind": "proposal", "callId", "tool",
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
  `_relay/items` listed them: `sha256` is the digest of the doc's review.
- Answer: `{"trusted": true, "labels": [string]}`, the doc's labels now.
- Errors: -32000 when the connection does not take approvals (the message
  names `relay trust doc <path>`), when the doc is not one this
  conversation showed, or when it changed since it was listed.
- relay-tui: `t` on a contaminated shown item opens its review.

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
| `relay/review` | `initialize` request, `clientCapabilities._meta` | client | `1`: the client shows relay's reviews and is held to them (see [Reviews](#reviews)) | declares it |
| `relay/review` | a permission request's `params._meta` | relay | the request's review, `{"text", "sha256"}`; relay replaces whatever the agent put there | the review pager |
| `relay/review` | a permission answer's `result._meta` | client | the `sha256` of the review the operator saw; an allow from a client that declared `relay/review` needs it | sends it with an allow |
| `relay/refused` | a permission request's `params._meta` | relay | relay's reason for refusing this client's last allow of the same request; relay drops whatever the agent put there | shows it above the review |

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
clean card has no `sha256`. Every card, clean or not, has `"review"`
(see [Reviews](#reviews)), and a contaminated card's `sha256` is the
review's.

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

## Reviews

What the operator consents to (allowing a call, trusting a card or a
doc) is written by relay, so that every client only has to show a text
as it is. relay sends each such subject with a review, `{"text": T,
"sha256": H}`:

- on a permission request, in `params._meta["relay/review"]`;
- on a card's wire form, as `"review"`, on every card;
- on a shown doc in `_relay/items`, as `"review"`.

`relay.review` makes them. T is plain text, lines joined by `"\n"`, each
line through `relay.text/visible-strict`, or `relay.text/visible-defanged`
for text from a contaminated source. visible-defanged breaks links with
escapes rather than by replacing anything: the colon of every `://`
becomes `\u{003a}` and every dot inside a word (the fullwidth and
ideographic ones too) its own `\u{XXXX}`, except in a plain number or
amount such as `12.50` or `"12.50"`, which reads as written. Every character of T is the space (U+0020),
`"\n"`, or one of Unicode's general categories L, M, N, P or S, which is
Go's `unicode.IsPrint` set. Anything else, the tab and every other space
(no-break, ideographic and so on) included, is written as `\u{XXXX}`,
and a backslash as two, so T can be read back exactly and two subjects
that differ never give the same T. H is the sha256
hex of T's UTF-8 bytes. Nothing is left out for length. Text from a role
or an outsider sits behind the gutter `"  │ "`, one gutter line for each
of its lines (headed `"  [contaminated]"` when its source is), and the
strings in relay's own lines are written as JSON strings, so nothing in
the subject can pass for a line of relay's.

A permission request's T holds, in this order: the role; the tool and
its class (from the role's tools, or "not known to relay"); what the
call does, as the tool's preview with the preview's labels, or a line
saying the tool has none; the whole input as JSON, two spaces an
indent, members in the order of their names; the arguments the
operator's settings fix, when there are any; the conversation's
labels; and every option the agent offers, with its kind and id. The
preview's links are broken when its labels are contaminated, and the
input's when the conversation's labels or the preview's are. For example:

```
Permission request from role "clerk"
Tool "send", class "act"

What it does, by the tool's preview (its labels ["private"]):
  │ Send to Everyone

Input, all of it, as JSON:
  │ {
  │   "body": "hi",
  │   "group": "Everyone"
  │ }
Set by your settings, not by the role: ["group"]

Labels of the conversation: ["private"]

Options:
  "Allow once", kind "allow_once", id "allow_once"
  "Reject", kind "reject_once", id "reject_once"
```

A card's T is its text form (`relay.cards/text-form`) with each
element's text made safe by its own labels and marked as the wire form's
`text` marks it:

```
Card 12: Taxes (checklist, 0 of 2 done, rev 14) [[card:12]]
[ ] 1 Find the T4
[ ] 2 [contaminated] Read https\u{003a}//evil\u{002e}example
```

A doc's T is `Doc "<path>", version <n>` and then its content behind the
gutter, its links broken when its labels are contaminated.

### What relay checks

A client declares that it shows reviews with
`clientCapabilities._meta["relay/review"]: 1` in `initialize`. Once a
connection has declared it, a later `initialize` does not take it back.
On such a connection:

- An answer to a permission request that selects an option of kind
  `allow_once` or `allow_always` must carry `result._meta["relay/review"]`
  equal to that request's H. When it carries none, or another, relay does
  not settle the request and sends the agent nothing. It sends the same
  `session/request_permission` to that connection again, under a new id,
  with `params._meta["relay/refused"]` saying why. An answer to the old
  id is then dropped. After 3 such allows from one connection for one
  request, relay stops sending it again and tells the operator on stderr.
  The request stays open for every connection either way: relay neither
  allows nor refuses a call because of a bad allow. A reject or a cancel
  needs no digest.
- `_relay/cards/trust` and `_relay/trust` must name H.
- An archive keeps `seen`: relay refuses one of a card changed after it.

Trust names H on every connection, since relay sends H as the subject's
`sha256`, and the CLI's `relay trust` names the same digest. A client
that does not declare `relay/review` (a generic ACP client such as
Agmente, or relay-tui before it shows reviews) is handled as before: its
allow needs no digest, because relay cannot know what it showed. That
is a known gap (relay#79).
