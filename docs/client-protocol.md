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
  that has none open. It takes no `sessionId`, and the board's and the
  messages' methods refuse one like any parameter they do not take
  (-32602).
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
| -32000 | relay refused it. The message is relay's reason, written for the operator, such as "The board holds at most 30 active cards. Archive one first." |

A client should show an error's message escaped, as relay-tui does: it is
relay's text, but it can quote a card number or path the client sent.

## Methods: the connection

### `_relay/connect`

Sent by `relay acp` itself, never by a client. When `relay acp` is a bridge
to `relay serve`, its first line on the service's socket is this
notification, `{"role": string or null}`, which tells the service which
role `relay acp <role>` was given. relay takes it only as the first line
on the socket. A request anywhere else
gets -32600, and a notification anywhere else is dropped. A connection
over stdio (the hub in `relay acp`'s own process) is set up from its
command line instead.

### `_relay/roles`

- Scope: connection.
- Params: none.
- Answer: `{"roles": [Role]}`, every active role in `relay roles` order.
  A Role is `{"id", "title", "labels": [string], "working": bool,
  "conversation": null or Conversation}`. A Conversation is
  `{"sessionId", "openedAt", "lastActivity", "turn", "turns", "unread":
  bool}`. Times are ISO-8601 instants. `turn` is `"idle"`, `"running"` or
  `"queued"` (a prompt waits for the role's scheduled work). `sessionId`
  can be null before the first turn.
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
  `notes` (strings) is present when relay cleaned hidden characters out of
  the text the op carried: one plain line per change, to show the operator.
- Errors: -32602 for the params; -32000 for relay's refusal, such as a
  card or item that does not exist, a limit, or a stale edit: an op that
  changes text is refused when that text was written after `seen`, an
  archive is refused when the card changed in any way after `seen`, and
  a `seen` past every rev in the log is refused too.
- The op is the operator's, with no labels. Every other subscribed connection is sent
  the card in `_relay/cards/changed`; this one gets it in the answer.
- relay-tui: every key that changes a card. It sends `seen` with every
  edit, waits for the answer before taking another op, and on a refusal
  shows relay's reason and reopens the input with what was typed.

### `_relay/messages`

- Scope: connection.
- Params: `{"limit": int}`, how many of the newest to send, from 1 to
  500 (100 when left out), or `{}`. Anything else, `sessionId` included,
  is refused (-32602).
- Answer: `{"rev": int, "messages": [Message]}`, newest first. `rev` is
  the seq of the log's last event. Messages are in their wire form
  (below).
- Errors: -32000 "relay has not read its messages yet. Try again." just
  after start.
- After it: the first call subscribes the connection to
  `_relay/messages/changed`.
- This is the client channel: every message relay tells the operator is
  here, whichever other channels relay.edn routes it to, and whatever
  became of those deliveries.
- relay-tui: not used yet. It reads `_relay/ready` instead, which
  carries the messages with everything else prepared for the operator.

### `_relay/ready`

- Scope: connection.
- Params: `{"limit": int}`, how many of the newest items to send, from 1
  to 500 (60 when left out), or `{}`. Anything else, `sessionId` included,
  is refused (-32602).
- Answer: `{"rev": int, "items": [Item]}`, newest first. `rev` is the seq
  of the log's last event. This is the client channel's view of
  everything prepared for the operator: what relay told her, drafts a
  role made with an edit tool that is not relay's own, entries on the
  agent's calendar that have not ended, findings a role put on the
  board, and file moves with their undo. An alert about a trouble that
  is over (the model runner works again, a source can be checked, a tool
  that was not set up works) is left out, and leaves a client's view as a
  `gone` id in `_relay/ready/changed`.
- An Item is `{"id", "kind", "at", "seq", "title", "lines": [string],
  "from": string or null, "outsider": bool}` and what its kind adds.
  `seq` is the log's number of the event that made the item; items with
  the same `at` are newest first by it, then by `id`. `id` is
  stable and names the kind and its number (`"message:7"`,
  `"prepared:12"`, `"event:3"`, `"card:9"`, `"move:4"`). `kind` is
  `"message"`, `"draft"`, `"event"`, `"finding"` or `"move"`; a client shows any other kind by
  its title and lines. `at` is when it was made, as an instant. `title`
  and every line are already safe to show: hidden characters are visible,
  and links are broken when an outsider may have written the text, with
  no mark of its own; refs read as words. `from` is the one line saying
  what outside text the item drew on, such as `"Drew on: a mail from
  x@y.org; the web page office-holidays.html"`, and is null for the
  operator's own things and relay's own messages. It names only what the
  session that made the item read directly in its wake, never what was
  merely in its prompt, and never a label. `outsider` is true when it
  drew on outside text.
  - `message` adds `"message"` (its number), `"messageKind"` (`note`,
    `alert`, `digest` or `reply`), `"urgency"`, `"thread"`, `"replyTo"`
    (the number to answer it under, or null when relay wrote it itself and
    nobody can answer) and `"replies"`, `[{"at", "text"}]`, the operator's
    answers. A daily note also has `"acts"`, `[{"n", "op", "line",
    "done", "offer"}]`: for each numbered item, the note's own number
    `n`, what it offers (`"revert"` or `"dismiss"`), the index in `lines`
    of the line that begins with that number, `done` (null, `"reverted"`,
    `"dismissed"` or `"declined"`, whichever note or command did it), and
    `offer`, true when the item has an offer of help that can be turned
    down on its own. `_relay/messages/act` takes `n`. A message whose
    sections have headings also has `"sections"`, `[{"heading", "lines"}]`:
    the same lines as `lines`, in order, under their headings, so a client
    showing it whole keeps them. `heading` may be null.
  - `draft` has, in `lines`, where the tool said it put the draft and a
    start of its text.
  - `event` adds `"start"` and `"end"`, instants.
  - `finding` adds `"card"`, the card's number.
  - `move` is a files_move call. Its first line is a summary ("41 files
    in notes, into 13 folders") and each later line is one file, `from →
    to`, relative to the folder the role was granted, which the summary
    names by its short name (when a call spans several granted folders,
    each line starts with its folder's name). `title` is the file's name,
    or the count. No line holds a full path or names the role. `from` says
    where what the role read before it came from. It adds `"move"` (the call's number), `"role"`,
    `"undo"` (the number to give `_relay/undo`, or null when nothing is
    left to undo) and `"undone"` (true once all of it is put back).
  - A client that does not know a kind shows its title and lines.
- Errors: -32000 "relay has not read what is ready yet. Try again." just
  after start.
- After it: the first call subscribes the connection to
  `_relay/ready/changed`.
- relay-tui: the ready view (`R`). It asks once the connection is up, so
  the keys row can count what is there.

### `_relay/reply`

- Scope: connection.
- Params: `{"inReplyTo": int, "text": string}`: the number of a message
  (an item's `replyTo`) and the operator's answer, at most 4000
  characters. Anything else is refused (-32602).
- Answer: `{}`, once relay has recorded the reply, with `"notes"` (a
  list of plain lines) when relay cleaned the text first: the reply is
  cleaned like anything the operator pastes (`relay.text/sanitize-input`).
  The reply is the operator's own (a client channel is the operator's
  hand), so it takes the same path as a verified mail reply: a
  `message/replied` event on the message (`replies` on its item, and every
  subscribed connection is sent the item changed), and a memo from the
  operator to the role that wrote the message, quoting it and saying which
  message it answers. When the message was a memo to the operator, that
  memo is closed as answered. The role answers with `tell_operator` and
  `in_reply_to`. A daily note is relay's own but can be answered too:
  the memo goes to the role that writes it (`:daily-note :role`), and
  "revert 1", "not 2" or "drop 2" in the reply is done as
  `_relay/messages/act` would, with relay's answer in the thread, delivered to the client
  alone. A reply that says nothing else makes no memo.
- Errors: -32000 with relay's reason: no such message, a message relay
  wrote itself other than the daily note ("there is nobody to answer
  it"), or a role that is gone.
- relay-tui: `a` on a message in the ready view (selected or open
  whole; Enter opens it, it does not reply), then Enter to send. One reply at a time is in flight, and a refusal gives the
  words back.

### `_relay/undo`

- Scope: connection.
- Params: `{"move": int}`: a move item's `undo`. Anything else is refused
  (-32602).
- Answer: `{"lines": [string], "summary": string}` once relay has put the
  move back: the same lines `relay undo` prints, and a short plain
  `summary` for a status line ("Put back 3 of 4 files."). It runs the same code as `relay undo`:
  each moved thing that is as the move left it goes back exactly, and
  the folders the moves made are removed once empty. A moved file that
  was changed or moved again, or whose old place holds something new,
  stays, and `lines` names it, relative to the granted folder, with why.
  The item then comes changed, with `undone` once all of it is back.
- Errors: -32000 with the reason when nothing could go back, touching
  nothing: each file that could not, and why, relative to the granted
  folder; or the move was already undone, there is no such move, or
  relay stopped during the move (the reason then lists the moves to
  check by hand).
- relay-tui: `u` on a move item in the ready view; the status line shows
  `summary`.

### `_relay/messages/act`

- Scope: connection.
- Params: `{"message": int, "item": int, "op": string}`: a daily note's
  id, the number of one of its items, and, optionally, `op`: `"revert"`,
  `"decline"` or `"dismiss"`. Anything else is refused (-32602).
- Answer: `{}` once relay has done it: an item whose `action.op` is
  `"revert"` has its change undone (what was there before is put back);
  one whose op is `"dismiss"` is dropped for good, from this note and
  every later one, or, with op `"decline"`, keeps its place and loses its
  offer, in this note and every later one. Without `op` relay does what
  the item offers. Declining an item with no offer is refused and changes
  nothing.
- Errors: -32602 for the params; -32000 for relay's refusal, such as an
  item the note does not have, one done already, or adding or removing a
  role, which is never undone.
- The act is the operator's, with no labels. The message's `rev` moves
  and `_relay/messages/changed` sends it again, with the item's
  `action.done` set.
- relay-tui: `v` (revert), `n` (no to the offer) and `d` (drop) on a
  numbered item of a daily note opened whole in the ready view. The item numbers are the note's
  own, and the ready item lists them as `acts` (see `_relay/ready`).

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
- relay-tui: X while moving, then y to confirm.

### `_relay/items`

- Scope: session. Answered by the conversation's proxy (`relay.dispatch`),
  never by the agent.
- Params: `{"sessionId"}`.
- Answer: `{"items": [Item]}`, newest first. A shown doc is `{"kind":
  "shown", "ref", "path", "callId", "at", "title", "text", "sources":
  [{"title", "ref"}], "labels", "version"}`. `text` is the doc's content
  as it is, and `labels` say where it came from.
- relay-tui: the conversation's workspace items. Asked when the
  conversation attaches and whenever a display arrives. A
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

## Notifications from relay

| method | scope | params | relay-tui |
|---|---|---|---|
| `_relay/roles/changed` | connection | `{"roles": [Role]}`, the whole roster, sent when any of it changed since this connection was last sent it | replaces the roster whole |
| `_relay/cards/changed` | connection | `{"rev", "cards": [Card]}`, every card whose rev is not the one this connection was last sent, archived cards included so the client can drop them | updates the one board, open conversation or not; a changed card brings the board into view unless the operator is busy in the pane |
| `_relay/messages/changed` | connection | `{"rev", "messages": [Message]}`, oldest first: every message made, or whose deliveries changed, since this connection was last sent messages | not used yet |
| `_relay/ready/changed` | connection | `{"rev", "items": [Item], "gone": [id]}`: every item new or different from what this connection was last sent, and the ids of those that left (an event that ended, a card archived, an item that fell past the newest 60) | applies them to the view it holds, which is the same as asking again |
| `_relay/replayed` | session | `{"sessionId", "truncated": bool}`, after the replay that follows `session/load` or an attaching `session/new`; `truncated` says the oldest updates were dropped | marks the conversation loaded, says so at the top when truncated, and stops calls the replay left open once the roster says the turn is idle |

A prompt's text is cleaned before the agent sees it: the text of text
blocks, embedded resources (`text` and `uri`) and resource links. Image and
blob data are not touched. When something changed, relay sends the
attached connections one `session/update` with `agent_message_chunk`
and `_meta` `{"relay/notice": true}`, whose text is one plain line per
change, when the turn starts. It is kept in the replay.

relay sends `_relay/cards/changed` only to a connection that has called
`_relay/cards`, `_relay/messages/changed` only to one that has called
`_relay/messages`, `_relay/ready/changed` only to one that has called
`_relay/ready`, and `_relay/roles/changed` only to one that has called
`_relay/roles`. A replay starts with the session's latest labels (a
`session_info_update` carrying `agentloop/labels`) and usage, and keeps
consecutive text chunks of one kind merged.

relay drops any line from the agent whose method starts with `_relay/`,
and any response from the agent to a request it was not passed. Only relay
speaks these methods to the client.

relay never asks a client for permission. agentloop sends no
`session/request_permission`; if an agent sends one anyway, relay answers
it as cancelled (or, in the hub, with -32601) and no client sees it.

## `_meta` keys

| key | where | set by | meaning | relay-tui |
|---|---|---|---|---|
| `relay/hub` | `initialize` answer | relay | `1`: this is relay's hub, with the methods above | switches to hub mode; without it, single-agent mode |
| `relay/keeps` | `initialize` answer | relay | whether a conversation outlives this client: true when `relay serve` holds it | says "relay service not running: conversations end when you close this" while false |
| `relay/role` | `session/new` request | client | the role to open or attach to; without it, the `<role>` `relay acp` was given | names the role of each conversation it opens |
| `relay/role` | each `session/list` entry | relay | the role whose conversation it is | not read |
| `relay/attached` | `session/new` answer | relay | `true`: the role's live conversation was attached to, and its replay follows | not read |
| `relay/display` | a text block in a tool call's content | agentloop, from relay's tools | the display, `{"title", "body", "labels", "sources", "ref"}`; relay makes the block's own text inert and passes the display on unchanged | the workspace items; the block's text is not shown as the call's output |
| `agentloop/labels` | tool calls and `session_info_update`s | agentloop; relay sends the latest first in a replay | the session's labels | the status line, and defanging once contaminated |

Relay's README describes what relay does with each of these, and what it
does with a line that breaks the strict JSON rule.

## A card's wire form

Cards use string keys and are ready to show. relay has already made every
text field safe: hidden characters shown as `\u{XXXX}`, and text whose
labels are contaminated defanged, with `"outsider": true` beside it. A
client renders what it gets and works nothing out for itself. `text` is
the card's text form, for a client that does not know its kind.

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

## A message's wire form

Like a card, a message uses string keys and is ready to show: hidden
characters are shown as `\u{XXXX}`, refs read as words, and text an
outsider may have written has its links broken, with `"outsider": true`
beside it. No text is stamped: a role's message that drew on outside
text carries `"drewOn"`, one line to show once, such as `"Drew on: the
web page office-holidays.html"`. An item may carry `"link"`, a link relay
built itself (a GitHub item's), to show as a live link. Nothing internal
is sent: no role, no origin and no label names.

```json
{"id": 2207, "kind": "note", "urgency": "normal", "at": "2026-10-26T14:03:00Z",
 "rev": 2209, "thread": 2207, "inReplyTo": null,
 "subject": {"text": "Draft ready: spring concert email", "outsider": false},
 "sections": [{"items": [{"text": "It is in your Hear Here drafts.", "outsider": false,
                          "refs": ["[[doc:hear-here/spring.md]]"]}]},
              {"list": true,
               "items": [{"text": "[Venue]", "outsider": false, "refs": []}]}],
 "deliveries": [{"channel": "mail", "status": "delivered"}],
 "replies": [{"id": 2230, "at": "2026-10-26T15:10:00Z", "channel": "mail",
              "text": {"text": "The Old Hall, please.", "outsider": false}}]}
```

An item a daily note numbers carries `"action": {"op": "revert" |
"dismiss", "n": int, "done": null | "reverted" | "dismissed" |
"declined"}`: what the operator can do with it through
`_relay/messages/act`, by `n`, and whether it was done, from this note or
any other that has the same item.

`kind` is `note`, `alert`, `digest` or `reply`; `urgency` is `low`,
`normal` or `high`. A section may have a `heading`, in the same form as
`subject`. `rev` is the seq of the last event that changed the message
(its creation or a delivery). `deliveries` lists the configured channels
it was routed to, each `pending`, `delivered` or `failed`; the client
channel is not listed, since every message is the client's. `replies` are
the operator's own answers to the message, verified by the channel they
came through (an email reply from their address that passed DKIM), oldest
first; a reply also changes `rev`.
