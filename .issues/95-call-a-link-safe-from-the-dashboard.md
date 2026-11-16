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
- [x] A key on a ready item or a card lists the links in it, live and
  broken, each in full.
  - Receipt: relay-tui zykmxoqs ff0eed19 and kwxlsxtv 2cd0e44d, on
    relay-tui's `integration` since 2026-11-15: `L` on a ready item or a
    card (a surface card too) lists them; `TestTheListShowsEachLinkInFull`
    (every width), `TestReadyLinksGolden`, `TestBoardLinksGolden`,
    `TestASurfaceCardListsTheLinksItDraws` in internal/render.
  - Receipt: `TestTheSelectedLinkIsOnScreenInAShortPane`: the selected
    link stays on screen in a pane shorter than the list.
- [x] Choosing a broken link asks before it is kept, and shows relay's
  refusal when there is one.
  - Receipt: internal/ui/links_e2e_test.go,
    `TestKeepingAndDroppingALinkFromTheReadyView` and
    `TestKeepingALinkFromTheBoard`: Enter asks, a `y` too soon is refused,
    an armed `y` sends `_relay/links/keep` with the link as shown, and
    relay's refusal of a bracketed address is shown with its reason.
  - Receipt: `TestAYesKeepsALongHostOnlyWhereItIsOnScreen`: a `y` keeps
    nothing while the link's whole host and the question are not on
    screen (Kira's rule, 2026-11-15).
  - Receipt: `bb verify` on 2026-11-15 with relay-tui at kwxlsxtv
    2cd0e44d: every step passes, relay 1233 of 1233, pentest 31 of 31.
- [x] A live link in conversation text is a hyperlink too, over its
  broken text. (It read "leaves a live link whole" until 2026-11-15.)
  - Receipt: the case "a reply in a contaminated session" in
    `TestALiveLinkIsAHyperlinkWhereOutsideTextIsDrawn`, relay-tui
    zxlqkvtp 72d0b337: a bare address, one in angle brackets and a
    Markdown link each become one hyperlink to the live address.
- [x] docs/client-protocol.md describes the methods and what the client
  is sent.
  - Receipt: `_relay/links`, `/changed`, `/keep` and `/drop`, each with
    what relay-tui does with it, in relay ttlkztor and murqutlq 8b0baf1e;
    the `links` field beside a thing's text is relay#118's, in the same
    file.
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

After part one landed, 2026-11-15:

- Round 6 (a fresh agent, on kxsoqlxu) found one defect in scope, the
  third round since the redesign to find one, so it went to Kira. In a
  contaminated session's conversation text a hidden character inside
  the separator or inside `www.` was drawn unchanged (`https:`, a
  zero-width joiner, `//evil.test`). kxsoqlxu had closed only a
  character before the `://`. It is older than this issue, and no
  terminal was shown to follow it. Rounds 5 and 6 were one class: a
  card or a ready item is safe because relay lists its links, but a
  reply reaches the client as written, so the client still breaks links
  there by pattern, and a pattern can be dodged by a character a link
  finder ignores.
- Kira's answer, of four options (drop them, show them as escapes, do
  relay#104 now, leave it filed): "Drop zero-width chars (Recommended)".
- Built as relay-tui yuttxulq and vywmmpxm: `core.DropHidden` takes
  every hidden character out of a contaminated session's text before
  its links are broken, and out of the lines with no mark of their
  source. Judgment call, at the coordinating session's request: what is
  hidden is relay's own definition (`relay.text/hidden?`) with relay's
  exception for the joiner or selector that holds an emoji together, so
  the rule exists once. That also means emoji mostly do not come apart,
  which was the cost Kira had accepted. The rule is in
  docs/client-protocol.md for every client (zqxpvzpx).
- The review of that rule, round 1: one defect in scope. The port did
  not take an unassigned code point for hidden, because Go's unicode.C
  holds those too and the test for "in no kind" never fired. No test
  had such a case, and the coordinating session and I had both read the
  port against relay's without running one. Fixed in vywmmpxm, with the
  cases that were missing. Round 2, on that fix: nothing in scope.
- Found on the way and fixed: a full stop after a broken link was drawn
  as `[.]` once every word went through Defang (xmoskxsx). The property
  that outside text is drawn as sent had missed it, because its skip
  rule dropped every text Defang would change. It now has its own model
  of what relay sends. Four other tests judged rows with the production
  check and now use the tests' own reader (ottsqzmp); one of them, of
  the relay#96 spike, failed on one seed since the first rendering
  commit. A test of a safety check must not ask the code under test
  what the right answer is; that went wrong three times here.
- relay#118 landed, so relay-tui reads each card's and ready item's
  `links` list (ppzxzvzp): a listed link's broken form is the hyperlink,
  the target is the listed address, and it must be in the connection's
  set too. Once relay#118 was in, the path that took a whole address
  from a card's text was removed (pmuovtok). A reply's text has no
  list and keeps it.
- Filed from these rounds: relay#120 (a role's title), relay#126
  (schemes with no double slash), relay#127 (a Markdown panic), and
  relay#130 for an address hidden by a character with no width that is
  not hidden, such as a combining mark. The session that filed it ran
  Apple's link finder: it follows such an address after `https:`, relay's
  own text already breaks the host's dots, and relay-tui's Defang leaves
  it whole in a reply, so that half is this client's.
- Known and left, each noted by a reviewer as out of scope or not
  followable: `www.` before a closing mark that Markdown puts a host
  after (`www.:@evil.test`); an unassigned code point of the emoji
  blocks inside an address, which relay keeps too; `mywww.txt` drawn
  with its dot broken where relay leaves it.

- The `bb verify` below was the last on part one.

Part two, 2026-11-15:

- Built as relay-tui zykmxoqs (the list on `L`), xyoullmx (relay-tui's
  half of relay#126) and kwxlsxtv (a surface card's links), on pmuovtok
  (a card's links from relay's list alone), all over relay#100's
  surface work, with relay's docs in murqutlq.
- A live row is a link relay listed for the thing whose address is in
  the set, drawn as a hyperlink to that address. A broken row is any
  other broken http address in what the thing draws, read only to be
  shown and sent back as shown to `_relay/links/keep`; it is never a
  hyperlink. Judgment call, at the coordinating session's request: a
  ready item's `outsider` flag is not asked, since relay's own daily
  note can hold a line that drew on outside text, and a surface card's
  rows come from the strings its surface draws.
- Fixed on the way, older than part two: on a short screen a notice
  above a tall draft could push the first row of the pane's question
  (the archive question too) off the top while `y` still answered. It
  was on trunk; it is fixed and pinned in zykmxoqs.
- Review, a fresh read-only agent each time (Codex's quota is out):
  1. Round 1, per commit. pmuovtok: nothing. zykmxoqs: the bar could
     move to another link when relay sent the item again while a
     question was open; on a small screen the question hid its link.
     xyoullmx: a scheme stuck to a dot or hyphen was left whole, and a
     wrap could drop characters. kwxlsxtv: the list read strings the
     card does not draw (an obscured field's value among them) and its
     test asked the code under test. All fixed.
  2. Round 2: a long link still pushed its host and the question off a
     small screen. Fixed by cutting the link's tail.
  3. Round 3: a long host (a bank's name as another site's subdomain)
     was cut before the site it belongs to. That was the third round on
     the question to find something, so it went to Kira.
- Kira's answer, of three options (never cut the host, refuse `y`
  whenever any of the link is cut, land and file): "Never cut the host
  (Recommended)". The question shows the link's whole host before any
  of its path. Each frame says whether it showed the host and the words
  that ask ("Keep it?", "Drop this link?"); one that did not look
  counts as not, and core refuses a `y` to such a question beside one
  pressed too soon, with a note to make the screen larger.
- Round 4, on that rule: the question's words could scroll off a short
  pane while the host and `y` showed. Fixed by checking the words too.
  Round 5: nothing in scope; at 80x24 and above a `y` is always
  allowed.
- `bb verify` on relay-tui kwxlsxtv 940b0c03: agentloop, relay-tui,
  webtool, eventkit, catalog and fuzz pass; relay 1224 of 1225 and the
  pentest 30 of 31, the misses relay#99's as before; s27 passes.

- relay#130's sweep (14,631,118 strings as relay shows them) through
  relay-tui's card drawing found one way relay-tui changed relay's
  text: relay escapes a hidden character after it breaks links, so it
  sends `https:\u{0080}//…` with `https:` whole, and relay-tui broke
  that `https:`. Fixed in xyoullmx; after it, 0 strings are drawn other
  than sent and 0 can be followed.
- Part two landed on `integration` on 2026-11-15 with the coordinating
  session's read: relay-tui pmuovtok, zykmxoqs, xyoullmx, kwxlsxtv;
  relay murqutlq and this log. Every acceptance criterion is met.

Left for other issues: relay-tui's half of relay#130 (draw a
contaminated reply as relay sent it once relay#104 is in, with `gate`
last), relay#126's `mailto:`, `tel:` and `sms:` (Kira's question in
relay#121), and relay#127.
- `bb verify` on 2026-11-15 with relay-tui at vywmmpxm 13c740c5, on
  relay's trunk with relay#118 in it: agentloop, relay-tui, webtool,
  eventkit and the new fuzz step pass (seed 1794749875), relay passes
  1196 of 1197 tests and the pentest 30 of 31, the two misses being
  relay#99's as before. s27 passes.
