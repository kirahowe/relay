# Call a link safe from the dashboard

## Context

relay#94 keeps a link live when relay can say where it came from, and
lets Kira keep or drop one with `relay link`. The dashboard does not show
any of that yet, for three reasons found on 2026-11-14:

1. relay-tui breaks every link it finds whole in outside text, as its own
   check on relay (`cardText` in `internal/render/board.go`: "only a link
   still live in it, which relay would never send, is defanged here").
   relay now sends live links whole, between angle brackets, so the board
   and the ready pane break them again.
2. relay-tui cuts and wraps text to the width of a pane. A link longer
   than the pane would be split, and a terminal follows only the first
   piece, which is an address nobody took. relay's own views now break a
   link a cut falls inside; in a pane 40 to 60 columns wide that would
   break most real links.
3. relay-tui breaks links in conversation text itself, from the session's
   labels (`internal/render/defang.go`), and does not know relay's live
   links.

So until this is done, a scout's links are whole in mail, in a push and
on the command line (`relay ready`, `relay cards`, `relay inbox`), and
still broken in the dashboard.

## Proposal

- Show a live link in the dashboard as an OSC 8 hyperlink: the terminal
  is told the exact address, so a pane can cut or wrap the text it shows
  for it and a click still opens the address relay judged. relay-tui
  writes no OSC 8 today ("Nothing here ever writes an OSC 8 hyperlink"),
  so this changes that rule for one case: a link relay sent between angle
  brackets.
- relay-tui keeps its own check, narrowed: in outside text, any link that
  is not between angle brackets is still broken.
- One key on a ready item or a card lists the links in it, live and
  broken, each in full. Choosing a broken one asks once ("relay cannot
  say where this link came from. Keep it?") and then keeps it.
- relay gets `_relay/links/keep` and `_relay/links/drop`, the operator's
  own, taking the address as the client showed it (`relay.text/mend`
  takes the broken form).
- Conversation text: relay sends the client its live links, and
  relay-tui leaves those whole there too. Or relay marks them before the
  text reaches the client. To be decided when this is built.

## Acceptance criteria

- [ ] A live link relay sent is whole in the board and the ready pane,
  and a click opens exactly that address, whatever the pane's width.
- [ ] Any other link in outside text is still broken in the dashboard.
- [ ] A key on a ready item or a card lists the links in it, live and
  broken, each in full.
- [ ] Choosing a broken link asks before it is kept, and shows relay's
  refusal when there is one.
- [ ] Conversation text leaves a live link whole.
- [ ] docs/client-protocol.md describes the methods and what the client
  is sent.
- [ ] Kira has seen a mock of the list and the question first.

## Plan

Needs relay#94, which is done. What it left for this:

- relay has the commands (`:link/keep`, `:link/drop`) and takes an
  address whole or in its broken form (`relay.text/mend`), so a client
  can send back exactly what it showed. It has no `_relay/` method for
  them yet.
- A card or a message that reads differently once a link is kept or
  dropped is sent again, so the board and the ready view need nothing new
  for that.
- relay sends a live link between angle brackets, `<https://x/a>`, so
  listing a card's live links is finding what sits between them. Nothing
  else in outside text relay sends is a link that can be followed.

## Log
