# Make conversation text safe in relay's proxy for every ACP client

## Context

Raised by the coordinating session on 2026-11-14 and checked against the
code by the relay#93 session.

relay passes an agent's conversation text to clients as the agent wrote
it. `relay.hub/agent-line` writes each `session/update` line to every
attached connection unchanged, after `keep-state` has noted the
session's labels. The one thing made safe on the way is the text of a
display block (`relay.acp/with-inert-displays`, called from
`relay.dispatch`), whose docstring says it is for "a client that renders
markdown and knows nothing of relay's _meta". An `agent_message_chunk`
gets nothing of the kind.

relay-tui makes up for it in the client. It reads `agentloop/labels` and
breaks every link in the conversation once the session is contaminated
(`internal/render/defang.go`). A plain ACP client does none of that. The
phone's client (relay#84) would show a link an outsider shaped whole,
and a client that renders Markdown would fetch an image's address with
no click at all. That is the threat model's "links that carry data out",
and the untargeted injection relay assumes reaches roles is enough to
cause it.

As far as the tracker shows, relay-tui is the only client in use, since
the phone trial in relay#84 has not been run. The gap opens with that
trial.

## Proposal

The coordinating session's. relay breaks links in the stream itself,
holding back a partial last word from one chunk to the next. It keeps
its live links whole between angle brackets, as it does in messages and
cards (relay#94). Every client draws what it gets. relay-tui then stops
breaking conversation links itself, which settles the question left
open at the end of relay#95's proposal.

Added by the coordinating session on 2026-11-15: a client that draws
text itself and not through a terminal, such as the Mac client, should
turn its own link detection off for conversation text and make links
only from relay's data.

## What a client needs from this

From the relay#95 session on 2026-11-15, at the coordinating session's
request. It is design input for whoever takes this issue. Kira has not
yet said that this issue comes next.

What relay-tui would drop for a relay session: `LinkIn` and
`fangSource`'s whole-address rule, which is the last place it reads an
address out of text, and `DropHidden` in the reply path. A reply's
hyperlinks would come from `core.Anchored` over the listed `shown`
forms, the same rule as a card, with the target checked against the
`_relay/links` set by `gate`.

What it would keep:

- `Defang` as a second line. It changes nothing in text relay broke.
- `Defang` as the first line for a plain ACP agent that is not relay,
  and for rows after Markdown has dressed a reply, where marks taken out
  can put pieces together.
- `said`, for lines with no mark of their source.
- `gate`.

What relay should send, so that a reply needs no finder in the client:

1. The text in `agent_message_chunk` is already inert once the session
   is contaminated. Hidden characters are handled first, by relay's own
   rule: they are stripped, with the exception for emoji, as
   `relay.text/sanitize` does it, and are not shown as escapes. The
   protocol says which.
2. Whole words only. relay holds back a chunk's last partial word until
   the next space or the end of the turn, so a `shown` form never
   straddles two chunks.
3. The live links of that text, as data in the update's `_meta`: `links`
   as `{"address", "shown"}` and `linksLeftOut`, in the same shape and
   with the same limits as a card's. A client keeps the union for the
   message it is building.
4. Thoughts, and a call's title, input and output, broken the same way,
   with no links listed.
5. The same on replay (`session/load`, and a `session/new` that
   attaches). Text written before the session was contaminated is left
   as written.
6. One fact in `initialize`'s `_meta`, beside `relay/hub`, that says
   relay does this, so a client can tell such a relay from a plain
   agent.
7. The operator's own prompt, echoed back, as written.

With that, the relay-tui halves of relay#126 and relay#130 shrink to the
plain-agent case. That case is to be decided separately: port relay's
rules once more, or accept a weaker second line there and say so.

The coordinating session's note: these seven are the starting contract
it would hold a design to. Point 1's choice to strip and not escape
matches what Kira chose for relay-tui on 2026-11-15.

## Acceptance criteria

- [ ] In a contaminated session, no link outside relay's live links
  reaches any client whole in conversation text, however the agent's
  chunks split it. A property test covers the splitting.
- [ ] A Markdown image in such text reaches no client in a form it would
  fetch.
- [ ] A live link reaches the client whole, between angle brackets.
- [ ] The replay a client gets when it attaches holds the same text the
  live stream did.
- [ ] Text in a session whose labels are clean is passed on unchanged.
- [ ] relay-tui shows the conversation as relay sent it and breaks no
  link a second time.
- [ ] README, docs/client-protocol.md and spec/relay.allium say that
  relay makes conversation text safe.
- [ ] docs/client-protocol.md tells a client that draws text itself to
  turn its own link detection off for conversation text and to make
  links only from relay's data.
- [ ] A pentest case attacks it through a plain ACP client.
- [ ] An adversarial Codex review finds nothing in scope.

## Plan

Related: relay#84 (the phone trial, which this should land before),
relay#91 and relay#95.

## Log

- 2026-11-15, from the relay#93 session. relay#130 is a reason to do
  this one first. relay-tui's own breaker left six addresses whole that
  `relay.text` breaks, each split by a character with no width, and
  Apple's finder followed all six. The coordinating session recommends
  this issue as the root fix, so that the one breaker fuzzed against
  Apple's finder covers every client and a client's own breaker is a
  second line. It is putting that to Kira.
- 2026-11-15, from the relay#93 session. The Proposal and the third
  criterion were written before relay#118. relay no longer keeps a live
  link whole between angle brackets: the text it sends is inert and the
  links travel as data beside it (docs/client-protocol.md). Whoever
  takes this should reword both. The criterion about a client's own link
  detection is my draft of the coordinating session's addition.
