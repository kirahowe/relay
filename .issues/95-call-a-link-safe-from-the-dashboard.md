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

- [x] A live link relay sent is a hyperlink in the board and the ready
  pane, over its broken text, and a click opens exactly that address,
  whatever the pane's width. (Until 2026-11-15 this read "is whole".
  Kira chose links as data on 2026-11-14: no address is shown as text.)
  - Receipt: relay-tui zxlqkvtp 72d0b337, internal/render/links_test.go:
    `TestALiveLinkIsAHyperlinkWhereOutsideTextIsDrawn` (a card, a note
    card, a ready item), `TestALiveLinkIsWrappedAsAnyOtherTextIs`, and
    `TestAnyLiveLinkIsInertTextAtAnyWidth` at widths 1 to 120.
  - Receipt: internal/ui/links_e2e_test.go,
    `TestALiveLinkReachesTheTerminalAsAHyperlink` and
    `TestALinkLongerThanItsRowOpensTheWholeAddressFromEachRow`: every
    cell of the link in the terminal emulator holds exactly the address,
    at 100 and at 60 columns.
  - Receipt: pentest `s27-live-links` PASS in the `bb verify` of
    2026-11-15: the real relay-tui on a pseudo-terminal gives the
    terminal the address the web showed as its one hyperlink target.
- [x] Any other link in outside text is still broken in the dashboard.
  - Receipt: relay-tui `TestNothingFromOutsideIsFollowedButALiveLink`
    (kxsoqlxu c1716fc8), a property over the board, the ready view, a
    conversation, the roster, a notice and the status line at widths 20
    to 140; `make check` passed.
  - Receipt: s27's check "the dashboard: no address is written as text,
    the live one included" passed in the same `bb verify`.
- [ ] A key on a ready item or a card lists the links in it, live and
  broken, each in full.
- [ ] Choosing a broken link asks before it is kept, and shows relay's
  refusal when there is one.
- [x] A live link in conversation text is a hyperlink too, over its
  broken text. (It read "leaves a live link whole" until 2026-11-15.)
  - Receipt: the case "a reply in a contaminated session" in
    `TestALiveLinkIsAHyperlinkWhereOutsideTextIsDrawn`, relay-tui
    zxlqkvtp 72d0b337: a bare address, one in angle brackets and a
    Markdown link each become one hyperlink to the live address.
- [ ] docs/client-protocol.md describes the methods and what the client
  is sent.
- [x] Kira has seen a mock of the list and the question first.
  - Receipt: the mock at
    https://claude.ai/code/artifact/c936a717-3ec8-4754-bfc0-93ef5793c9a9
    and her three answers on 2026-11-14, in the Log: build as mocked, a
    long link in the broken form and clickable, `d` drops after one
    question. Its frames with a link shown whole are out of date since
    her later decision.

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

Step 1 and the rounds on the new design, 2026-11-15:

- relay-tui zxlqkvtp: a live link's text is its broken form and the
  address goes to the terminal in the OSC 8 target and nowhere else.
  `readsWhole`, the stand-in for a hyphen and the rule that kept a link
  whole on one row are gone, so a link span wraps and cuts as any text
  does. `gate` still drops a hyperlink whose target is not in the set,
  and breaks any text in a span that a terminal could follow.
- Judgment call: no angle brackets round the broken form. relay's new
  wire form (relay#118) shows a live link as `hxxps://x[.]example/a`
  with nothing round it, so the dashboard reads the same before and
  after that lands. The underline and the link colour mark it.
- The property test reads each row as a terminal with no OSC 8 does,
  the hyperlinks' text included, where it used to skip a whole link's
  span. A second property draws addresses from the link shape's own
  characters, with `www.`, a second scheme and `[.]` mixed in, at
  widths 1 to 120. Writing the address back as the span's text fails
  four tests.
- s27 holds the real relay-tui to it: the terminal is given the one
  live address as a target, and nothing it writes holds `http://`,
  `https://` or `www.`.
- Round 4, Codex, on the new design: one finding in scope and nothing
  else. A role's title in the roster was drawn with no link broken, so
  a contaminated role that may add roles could put an address on screen
  as text. It is older than this issue. relay's half is relay#120.
  relay-tui's half is pzkztxvr.
- Judgment call: I had every string relay-tui draws from the wire
  traced (a read-only agent's table, which I checked) and closed the
  same gap in the other lines that carry no mark of their source: the
  status line's running tool, retry reason and message, the hub's
  notice, and the ready view's from line, refusal and undo line. Their
  links are broken always, with no flag to read. Left alone, each for a
  reason: the banner (the agent's own name and model), the operator's
  own replies, text a session wrote before it read anything from
  outside, and a workspace item relay did not label.
- Codex's quota ran out after round 4 ("try again at Nov 19th, 2026
  12:23 PM"). Rounds 5 and 6 were each a fresh read-only agent given the
  same prompt, threat model and diffs, and no account of my reasoning.
- Round 5 confirmed pzkztxvr, found no break of G1, G3, G4 or G6, and
  found one defect in scope, low. `fang` ran `Defang` only on words a
  second test (`linkable`) took for links, and that test wanted an
  ASCII letter straight before `://`. In a reply written under
  contamination, `https`, a zero-width joiner, then `://evil.test` was
  drawn as it came, where the Defang this work replaced had broken the
  `://`. The reviewer found no terminal that opens it. Fixed in
  relay-tui kxsoqlxu: every word goes through Defang, and the check on
  rows as drawn is built from Defang's own pattern, so the two cannot
  disagree. Defang now knows relay's `fxp://`, so a card with a broken
  ftp link is drawn as relay sent it.
- Round 5 also showed that three of the eleven sites pzkztxvr fixed had
  no test that failed without the fix (two notice rows and a refusal
  with no reply open). Each has one now. I checked by taking the fix
  out of every site in turn: ten fail a test, and the eleventh, a
  branch of `readyStatus`, cannot be reached.
- Round 5's other findings are older than this issue and are filed.
  relay#126: `ssh:user@host`, `file:/path` and `https:/x` are left
  whole by relay-tui's Defang, and partly by relay's. I will fix
  relay-tui's half with part two. `mailto:`, `tel:` and `sms:` wait for
  Kira's answer on relay#121. relay#127: Markdown quoted deeper than
  half the width panics.
- Not fixed, noted: `unsafeRow` reads a hyperlink's text and what
  follows it apart, so a live link ending in `www` with a full stop
  after it draws a row that reads `www.`. Only closing marks can follow
  a link in its word, so nothing after it can be a host.
- For step 2, from the coordinating session: a ready item's `outsider`
  flag is per item, and relay's own daily note can hold one line that
  drew on outside text. A client that decides by the flag alone would
  show that line's link whole. relay breaks that line itself as of
  relay#118. The list on `L` should be held to a property that an item
  with `outsider` false shows nothing to follow that relay did not
  list.
- `bb verify` on 2026-11-15, run three times as the chain grew, the last
  on relay ttlkztor to kvxoxuwn with relay-tui exported at kxsoqlxu
  c1716fc8, in my own agentloop and webtool workspaces: agentloop,
  relay-tui, webtool and eventkit pass, relay passes 1189 of 1190 tests
  and the pentest 30 of 31 scenarios. The two misses are
  relay.shell-live-test and s18-shell-walls, both relay#99 (Apple's
  container service is stopped). s27 passes. relay's `integration` moved
  after that run by issue files only.
- Part one landed on `integration` on 2026-11-15 on the coordinating
  session's word: relay-tui ytnytlom, oomzupqw, xmvurrzo, pzwvpyps,
  ukrtspwq, zxlqkvtp, pzkztxvr and kxsoqlxu, then relay ttlkztor,
  wwmznmqz, rpvmrytu, kvxoxuwn and this log. Its condition: since round
  5 found a defect in scope and nothing had reviewed kxsoqlxu, one more
  fresh round goes over that commit before `main` moves. Round 6 was
  running when this was written. A clean round means part one has
  converged. A defect in scope would be the third round since the
  redesign to find one, and goes to Kira.

Still to do:

1. Record round 6.
2. Step 2, once relay#118 is on `integration`: the list on `L` reads
   relay's `links` field, a hyperlink is anchored on `shown`, and the
   port of link-in goes from everything but conversation text.
3. Part two lands: relay-tui xyksywky (rebased onto kxsoqlxu, its tests
   and goldens already moved to the broken form) and relay ooorlxwl,
   with relay-tui's half of relay#126.

