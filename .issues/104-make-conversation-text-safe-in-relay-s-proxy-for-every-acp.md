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
