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

Judgment calls, all mine, 2026-11-14:

- The dashboard's own check is membership, where the proposal had angle
  brackets. relay-tui draws a link in outside text as a hyperlink only
  when the address is exactly a member of the set relay sent the
  connection (`_relay/links`). Brackets are easy for an outsider to
  write, and trusting them would trust every text path that skipped
  relay's defang, a role-built surface's strings for one. With the set,
  such a link is still broken.
- Conversation text, which the proposal left open: relay sends the
  client its live links and relay-tui leaves those whole. relay does not
  rewrite the ACP stream. Rewriting it would mean buffering a reply to
  find a link split across chunks, and a plain ACP client would see
  relay's marks. Only a reply keeps its live links. A call's name,
  arguments and output, and the agent's thinking, are broken throughout.
- A live link longer than its row is shown in the broken form, with the
  whole address as the hyperlink's target. A link whose word fits a row
  stays whole on that row. The start of an address is another address,
  and a terminal that drops OSC 8 would follow it; tmux does unless
  hyperlinks is in terminal-features. So where OSC 8 does not get
  through there is nothing to click, and never a cut address. The mock
  shows this beside the other choice, wrapping the address as it is.
- A hyperlink gets its look (underline, link colour) in one last pass
  over every frame (`gate` in relay-tui's internal/render/links.go).
  The same pass drops any hyperlink whose target is not in the set, so
  no code between can turn a link into one nobody judged.
- A note card's marks (`#`, a list marker, `**`) are read before its
  text is made safe, and its rows are checked again as drawn. Taking
  `**` out could join an address from its pieces after the old check.
- `_relay/links/keep` and `_relay/links/drop` answer `{"changed"}` and
  `notes`. They do not repeat the link: the client sent it.
- The workspace pane's items (a document a role showed) still break
  every link. Nothing here asked for them.

Found on the way:

- relay-tui's `Defang` left a `www.` host whole when a scheme came
  later in the same word (`www.evil.test/?u=http://x`), and left an
  unknown scheme's dots whole (`www.x.io://y`). Conversation text has
  no other defang. Fixed in relay-tui ytnytlom, with the cases.
- `go test ./internal/a2ui -run TestSetReadsBack
  -rapid.seed=15639078114469465116` fails in relay-tui ("null write left
  [] at /0"). It is the relay#96 spike's package; the session that owns
  it has the seed.

Built so far:

- relay-tui ytnytlom, oomzupqw: the rendering. Nothing sends the set
  yet, so every link is still broken there.
- relay: `_relay/links`, `_relay/links/changed`, `_relay/links/keep`
  and `_relay/links/drop`, with the hub and `relay acp` tests.

Review, verify and a change of design, 2026-11-14:

- Landing was split in two at the coordinating session's request, since
  two other chains sit on relay-tui's commits. Part one is the set on the
  wire and the dashboard drawing live links. Part two is the list on `L`
  and its questions. Neither has landed.
- Kira saw the mock and answered: build the list and the question as
  mocked, a long link stays in the broken form, and `d` drops a live link
  after one question.
- Codex, read-only, threat model given, on part one:
  1. Round 1, one finding, in scope, fixed in relay kvxoxuwn. A
     `_relay/cards/op` result took relay's state and answered before
     `_relay/links/changed`. `take-state` in relay.hub is now the one
     place the hub takes a state, and it sends the set's change first.
  2. Round 2, two findings, fixed in relay-tui ukrtspwq. A live link
     with a character a terminal's link finder stops at (an apostrophe,
     in Ghostty's) was drawn as it is, so without OSC 8 part of it could
     be followed. An outsider's tracker column name was put in capitals.
  3. Round 3, three findings. A live link with a second address inside
     it (kitty follows the inner one). Text touching the link's bracket
     (`<https://x.example/a>>`, which WezTerm reads past). And a reply in
     a contaminated session still goes through Markdown, which takes
     marks out: that one is my claim worded too widely, since replies
     are dressed by design, their links broken and their rows checked.
- Rounds 2 and 3 found one class three times, and relay#94's rounds
  found it too: a link shown as text is read by whatever link finder the
  reader has, and finders disagree. By relay's rule I stopped after three
  rounds and asked Kira. She asked what the root was. My answer: a link
  travels as text, so whether it can be followed is decided by the
  reader's heuristics and not by relay. Shaping the text until every
  finder agrees is the parser differential of relay#58 over an open set
  of readers. Breaking a link only needs no finder to match; showing one
  whole needs every finder to match exactly.
- Kira's decision, in her words: "I think you have the right analysis,
  we should stop trying to fix the symptom and get to the root of the
  issue. Links as data is a better direction. I like it, you can execute
  in whatever order is most efficient."
- The design that follows, agreed with the coordinating session and the
  relay#94 session: text on a screen is always inert, the broken form or
  a label. A link is data relay attaches to a card, a message or a ready
  item: a `links` list, each `{"address", "shown"}` with its broken
  form, widening the `link` a message item already carries. The address
  travels only by a channel's explicit link mechanism: OSC 8 on a
  terminal, a push's click action, and in a plain-text mail a block
  after the item with each link alone on its line. A client parses no
  address out of text.

Order from here:

1. relay-tui, on top of ukrtspwq: a live link's text is always the
   broken form, and the address lives only in the OSC 8 target. This
   takes out `readsWhole`, the hyphen stand-in and the whole-on-one-row
   logic. Then one more review round, and part one lands.
2. The relay#94 session reworks how relay shows links (inert text, the
   `links` field). relay-tui then reads the list on `L` from that field
   and retires its port of link-in.
3. Part two lands on that.

State when this was written:

- relay.links95, on integration as of vrrnnrur (it has moved since, and
  relay.text changed, so rebase first): ttlkztor, wwmznmqz, rpvmrytu
  (eventkit's date round trip drew a day the calendar skipped; not from
  this work), kvxoxuwn, ooorlxwl (docs for part two).
- relay-tui.links95, on integration wsxozuyu: ytnytlom, oomzupqw,
  xmvurrzo, pzwvpyps, ukrtspwq (part one), xyksywky (part two, the list).
  relay#100's chain sits on pzwvpyps.
- `bb verify` on part one, with my own agentloop and webtool workspaces
  and relay-tui exported at ukrtspwq: agentloop, relay-tui, webtool and
  eventkit pass; relay 1189 of 1190 and pentest 30 of 31, the two misses
  being relay.shell-live-test and s18-shell-walls (relay#99, Apple's
  container service is stopped). s27 passes with the real relay-tui on a
  pseudo-terminal.
- Under a private tmux 3.8: with `hyperlinks` in terminal-features the
  outer terminal is sent the live addresses and no other; without it,
  no hyperlink at all.
- relay#108 was filed for the growing `_relay/links` answer.
