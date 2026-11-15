# Carry a live link as data beside inert text

## Context

Sent by the relay#94 session on 2026-11-14, at the coordinating
session's request. The text is that session's own, and that session
builds it.

relay#94 keeps a set of live links (the web showed one to a scout, or
Kira kept it) and left such a link whole in outside text, between angle
brackets. Three review rounds on relay#94 and three on relay#95 kept
finding the same thing: a link shown as text is followed wherever the
reader's link finder decides it ends, and finders disagree (Apple's,
Ghostty's, kitty's, WezTerm's). Showing one whole needs every finder to
agree exactly, over readers nobody controls. That does not converge.

Kira, 2026-11-14, in the relay#95 session: "I think you have the right
analysis, we should stop trying to fix the symptom and get to the root
of the issue. Links as data is a better direction. I like it, you can
execute in whatever order is most efficient."

So shown text is always inert, and a link is data relay attaches to it.
Only a channel's own mechanism carries the address.

What stays from relay#94: the live set and its fold; how a scout's memo
records the links the web showed it; `relay link`, `relay link drop` and
`relay links`; the link shape as the rule for what may enter the set;
`relay.text/link-in`, which finds the live links a role's text holds;
`clip-shown` and the wrap rule; and resending a card or message that
reads differently once a link is kept or dropped.

What goes: leaving a live link whole in text (`relay.text/defang-but`
and the angle brackets). In outside text every link is broken again, as
before relay#94.

What is new:

- On the wire, a card, a message and a ready item each carry `links`, a
  list of `{"address", "shown"}`: each live link its outside text holds,
  and the broken form the text shows for it, so a client can make that
  text a hyperlink to the address.
- In mail, still text/plain, an item's live links follow it on lines of
  their own, one address to a line, so nothing a role wrote touches an
  address. relay already does this for a link it builds itself.
- On the command line (`relay ready`, `relay cards`, `relay card`,
  `relay inbox`, `relay links`), on a terminal, a link line is the
  broken form inside an OSC 8 hyperlink to the address. Where the output
  is no terminal there is nothing to follow.
- In a push, the body is inert and up to three live links are ntfy
  actions. This is the half of relay#117 that was Kira's to decide; her
  "links as data" covers it, and how the buttons are labelled is still
  worth showing her.

## Acceptance criteria

- [ ] In text an outsider may have shaped, relay shows no link that can
  be followed, in any channel or view, whatever is live. A property
  checks it.
- [ ] A card, a message and a ready item each carry `links`: every live
  link their outside text holds, in order, each once, with the broken
  form the text shows. docs/client-protocol.md states the contract,
  agreed with the sessions on relay#95, relay#96 and relay#97 before it
  is built.
- [ ] A mail lists an item's live links after it, one address to a line,
  alone.
- [ ] On a terminal the command line shows a live link as the broken
  form inside OSC 8; piped, it writes no OSC 8.
- [ ] A push carries up to three live links as actions and says when
  there are more.
- [ ] Keeping or dropping a link sends again every card, message and
  ready item whose `links` changed.
- [ ] Pentest s27 reads the links from the data and from the mail's
  lines, and still finds no address holding the canary that can be
  followed.
- [ ] README, resources/AGENTS.md, spec/relay.allium,
  docs/architecture.md and docs/client-protocol.md say what changed.
- [ ] `bb verify` passes but for relay#99's two tests.
- [ ] An adversarial Codex review finds nothing in scope.

## Plan

## Log

- 2026-11-14: the relay#93 session had put relay#117's question about a
  push to Kira before this issue took it over. Her answer: she has no
  strong view yet on what a push should look like, and the session can
  make the call. The call, which is the relay#93 session's: links as
  actions, as the criteria above have it. The mail app she named for the
  other question is in relay#117.
