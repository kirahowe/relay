# Keep role-built surfaces in the log and send them to every client

## Context

Part of relay#93. relay plays A2UI's "agent" toward clients. A role
authors a surface through a tool, relay checks it against relay's catalog,
keeps it as log events and replays it to each client. Every string in it
goes out through the path cards use, so labels, hidden characters and
links (relay#94) are handled once.

## Decisions

relay-32 coordinated the sessions on relay#93 to relay#97 on 2026-11-14
and settled the first four with me. The rest are mine. Kira may overrule
any of them.

- A surface is a fourth card kind, `surface`. It reuses `card/added` and
  `card/changed`, the board's limit, the owner, archive, restore and
  retitle, `[[card:N]]`, the ready finding, `relay cards`, the card tools
  and the `_relay/cards` methods. The epic says relay keeps everything
  underneath, and relay.cards already names a new kind as the way to
  extend it. There is no second stack of events, methods or tools.
- relay resolves a surface before any client sees it: it expands list
  templates, reads every `@path` and fills `formatString` and `@index`.
  If a client did the filling, a role could join two strings that each
  pass the link check into one working address. Resolved in relay, the
  check runs on the string the operator reads, and there is one
  evaluator, so relay and relay-tui cannot disagree about what a surface
  says.
- Labels are kept per surface, not per component or per path, and a
  surface has one rev. Paths into a list are positions and shift when
  the list changes length, so labels or revs keyed by path are where the
  bugs would be. A surface's own ops (`input`, `data`, `components`) need
  `seen` and are refused unless it is the card's rev now, so no write
  lands on an item that moved. `retitle`, `archive` and `restore` name
  no path and keep the rules of any card.
- Catalog v1 is what relay-tui can operate today: Text, Row, Column, List,
  Card, Divider and CheckBox. TextField and ChoicePicker wait until the
  dashboard can type and choose in them. Button waits for step two
  (relay#98 is decided; see Plan).
- The catalog has no Tracker. Kira asked whether a grid could be built
  from A2UI's own components, and relay-ed recorded in relay#93 that the
  tracker kind of card stays and relay's catalog stays a plain subset of
  A2UI's types. A grid of boxes already works as a List whose template is
  a Row. It has two states to a cell, not three, and no aligned columns.
- A Text's `text` and a CheckBox's `label` are plain text, not Markdown.
  relay-54 found that relay-tui's Markdown renderer drops the angle
  brackets round a live link and joins `ht**tp**s` into `https`, so what
  was drawn was not the string relay checked. A client dresses a Text
  only as it dresses a note card.
- A surface keeps two label sets, one for its components and one for its
  data model. Each is reset by a whole replace of its own part, and every
  string is made safe with their union. With one set, a clean role
  replacing the components would wipe the taint of data an outsider
  shaped.
- A component takes only the properties a terminal can honour. `justify`,
  `align`, `weight`, `@checks`, `@accessibility`, `@metadata` and
  `@catalogId` are refused, so a role is not told something worked when
  nothing drew it. The catalog grows when a client can do more.
- Numbers in a surface are whole numbers within 2^53, and data keys are
  `[A-Za-z0-9_-]`. Every reader (Clojure, Go, the log's JSON) agrees on
  those, and a key can stand in a path with no escaping.
- The data model a client is sent holds only what the inputs read. None
  of the role's text is in it, so the only strings a client can draw are
  the resolved ones in the components.
- The operator's change to a surface is one op, `input`, with the path an
  input is bound to and the new value. It carries no text, so it adds no
  labels, and like a tick it wakes no role.
- `formatString` fills data paths only. A function call inside `${...}`
  is refused. `@index` is available as a value of its own.
- A path to a list or an object reads as nothing in a text. A2UI would
  give its JSON. A text shows words, and the JSON of a whole data model
  in every spot of a `formatString` is how a small surface came to 1.6
  gigabytes in Codex's first round.
- A box is bound only where the data already holds true or false. A
  tick then puts one of the two where the other was. Bound to a list, a
  tick replaced the list. Bound where the data held nothing, a tick had
  to make the place, and a path too deep or too long made a box that
  could never be ticked (Codex, rounds 1 and 2). relay refuses the
  role's write instead, and says to write false there first.
- A surface is limited by what it weighs as sent, 128,000 bytes, and by
  16,000 characters in all, as well as by counts. relay-tui reads lines
  of at most 10 MiB, and a board holds 30 cards. What it keeps is
  limited too, 64,000 characters of JSON for components and data
  together, since a role that reads the card is handed all of it, and a
  draw may read the data 20,000 times.
- Every limit is checked on the surface with all its boxes unticked. A
  true is shorter than a false wherever a surface shows or sends it, so
  that is the surface at its longest, and a tick changes nothing about
  it. So an `input` is decided with no limit check at all and cannot be
  refused by one. This replaced arithmetic that tried to allow for each
  tick, which round 3 found wrong in one direction. All three rounds
  found a way for a tick to do harm or be refused, so the rule is now
  one that makes the question go away.
- A refusal for `seen` on a surface's op never says which rev the card
  is at. `seen` is there so that no write lands on an item that moved,
  and a caller handed the rev could send the same op again without
  reading the card.
- A write makes a key that is missing on its way and never a list. A
  position or a `-` where there is no list is refused: relay cannot tell
  that a list was meant, and would make an object with that name.
- `--surface` on the command line reads a file on this machine and
  nothing else. `slurp` would fetch an address, and what came back would
  be recorded as the operator's own text.
- An op this relay does not know in a `card/changed` event still stops
  the fold, as an event type it does not know does: only a newer relay
  can have written it. What never stops the fold is a surface whose
  content no longer fits the catalog or the limits.

## Acceptance criteria

Tests named below are in `test/relay/surfaces_test.clj` unless another file is given.

- [x] A role adds a surface with `card_add` (kind `surface`, a title,
  components and data) and changes it with the `card_op` ops `components`
  and `data`. It is a card in every other way: the board's limit,
  archive, restore, retitle, `[[card:N]]`, the ready finding and the
  prompt's board line.
  - Receipt: `a-role-keeps-a-surface-with-the-card-tools` (card_add,
    `data`, `components`, the read) and `a-surface-is-a-card-in-relay-core`
    (the board, the prompt's line, the ref, retitle, archive).
  - Receipt: the ready finding, run by hand on the tip: a surface a role
    adds comes out of `relay.ready/ready` as
    `{:id "card:2", :kind :finding, :title "Lake weekend", :lines [], :card 2}`.
  - Receipt: change wxzmyzqp, "Add surface, a kind of card a role lays
    out itself".
- [x] relay refuses a surface that is outside catalog v1, says what to
  change, and records nothing: an unknown component or property, a
  function other than `formatString` and `@index`, no `root`, a child
  that is missing, used twice or its own ancestor, an id holding `__`, a
  number that is not whole, a data key outside `[A-Za-z0-9_-]`, or
  anything over a limit.
  - Receipt: `relay-refuses-what-its-catalog-does-not-have`,
    `the-components-are-one-tree-under-root`,
    `a-data-model-holds-plain-json-within-limits` and
    `a-surface-is-drawn-before-it-is-recorded`, each on the refusal's
    exact words.
  - Receipt: `a-mistaken-surface-is-refused-not-an-error`, 500 surfaces
    with properties replaced by any JSON: a refusal or an acceptance,
    never an error.
- [x] The wire form holds a resolved surface: final strings, `children`
  as ids, no `@call` and no template. The only binding is a CheckBox's
  `value`, and the data model holds only the values at those paths.
  - Receipt: `every-string-of-a-surface-is-made-safe-whole` checks it on
    300 generated surfaces (`resolved?`, and no string in `dataModel`).
  - Receipt: `the-fixture-cards-are-what-relay-sends` against the cards
    in `testdata/surfaces`.
- [x] Every string of a surface that a client or the command line shows
  is made safe with the surface's labels: hidden characters are shown
  escaped, and in a surface an outsider may have shaped every link is
  broken, a link that `formatString` joins from pieces included. The
  live links its strings hold are listed in the card's `links`.
  - Receipt: `every-string-of-a-surface-is-made-safe-whole` and
    `a-surface-a-session-with-outside-text-wrote-shows-nothing-live`.
  - Receipt: `a-surfaces-strings-are-the-ones-it-shows`, where a live
    link joined from two halves is listed in the form the text shows;
    change lzslyxyt.
- [x] The operator's `input` op, from the command line and from
  `_relay/cards/op`, is taken only at a path a CheckBox is bound to, with
  true or false and `seen` equal to the card's rev. It adds no labels and
  wakes no role, and every other subscribed client is sent the card.
  - Receipt: `the-operators-input-lands-only-on-an-input`,
    `a-surfaces-ops-need-the-rev-the-caller-read` and
    `every-box-relay-accepts-can-be-ticked-and-unticked`.
  - Receipt: `a-clients-input-reaches-relay-and-then-every-client` (the
    hub) and `the-operator-keeps-a-surface-from-the-command-line`.
  - Receipt: `a-surface-is-a-card-in-relay-core` ("an input wakes
    nobody") and `a-surface-is-as-tainted-as-every-write-still-in-it`
    ("an input adds no labels, whoever sends it").
- [x] A surface's labels are those of its title, its components and its
  data model. A whole replace of the components or of the data model
  resets that part's labels, and reading the card adds them all to the
  reader's session.
  - Receipt: `a-surface-is-as-tainted-as-every-write-still-in-it`, 300
    random runs of writes against a model kept by the test.
  - Receipt: `a-role-keeps-a-surface-with-the-card-tools`, the labels a
    read comes back with.
- [x] The text form is relay's header and then the drawn surface, every
  line of it behind the gutter. A role that reads one card is also shown
  its components and data as JSON, so it can change them.
  - Receipt: `the-text-form-is-the-header-and-then-the-surface-behind-the-gutter`.
  - Receipt: `a-role-keeps-a-surface-with-the-card-tools`, "read alone,
    a surface comes with what its role wrote and what the operator set".
- [x] Folding the decided events gives the same cards as folding them
  after a round trip through the log's JSON, for random surfaces and ops.
  - Receipt: `a-surface-is-a-fold-of-its-events` (300 runs) and
    `relay-core-folds-cards-as-replay-does` in
    `test/relay/cards_test.clj`, which now adds surfaces too.
  - Receipt: `whatever-a-log-holds-the-fold-and-the-forms-go-on`, a
    surface's events holding any JSON.
- [x] `resources/a2ui/relay_catalog.json` is generated from relay.surfaces
  and checked in. A test fails when the two differ, and `bb verify` fails
  when relay-tui's copy differs from it.
  - Receipt: `the-catalog-file-is-the-catalog` and
    `the-catalog-says-what-relay-checks`; `bb surfaces` writes the file.
  - Receipt: the `catalog` step of `bb verify`, change pxkpwqpp, and
    `relay-tuis-catalog-is-compared-with-relays` in
    `test/relay/verify_test.clj`.
  - Receipt: the joint `bb verify` of 2026-11-15 (relay-54's run, relay
    at lzslyxyt bdfc1086, relay-tui at mxpvltlm a2ce30c4): `catalog`
    PASS.
- [x] Wire forms for a checklist and a note built as surfaces, and for
  one a session with outside text wrote, are checked in as fixtures that
  relay-tui's renderer tests read. `bb verify` fails when relay-tui's
  copy of them differs.
  - Receipt: `testdata/surfaces`, and `the-fixture-cards-are-what-relay-sends`.
  - Receipt: the same `catalog` step runs `diff -r` on the directory.
    relay-tui's copy and the tests that draw it are relay#100's.
- [x] `docs/client-protocol.md`, `README.md`, `resources/AGENTS.md` and
  `spec/relay.allium` describe surfaces. The role instructions say which
  read gives a role back what it wrote and what the operator ticked.
  - Receipt: "A surface card" in `docs/client-protocol.md` (changes
    vorvxlrm, nlyokvuo); "Surfaces" in `README.md`, "The operator's
    board" in `resources/AGENTS.md`, and `Surface` and `ChangeSurface` in
    `spec/relay.allium` (change rzyuxqus).
  - Receipt: `allium check spec/relay.allium` reports the same three
    errors and six warnings as before the change, all in rules this
    change does not touch.
- [x] A pentest attacks a surface end to end: a role that read outside
  text builds one with hidden characters, an escape sequence and a link
  joined from pieces, and the operator is shown none of them live.
  - Receipt: `s6-hostile-text` in
    `pentest/relay/pentest/scenarios/display.clj`, change ztpzsyul. It
    passes, with the checks "an address the surface joins from two
    pieces is broken as one" and "relay cards shows the row's two pieces
    with a gap between them" among those it prints.
- [x] `bb verify` passes, apart from the two checks relay#99 covers.
  - Receipt: the joint `bb verify` of 2026-11-15 on relay lzslyxyt
    bdfc1086 and relay-tui mxpvltlm a2ce30c4, run by relay-54:
    agentloop, relay-tui, catalog, webtool, eventkit PASS; relay 1,224
    of 1,225 (seed 759790736, the failure `shell-live-test`); fuzz
    300,000 cases, none followed; pentest 30 of 31 (`s18-shell-walls`).
    Both failures are relay#99 (Apple's container service stopped).
- [ ] The Codex review loop ends with a round that finds no defect in
  scope.
  - Not met as written. Rounds 1 and 2 were Codex, 3 and 4 a fresh
    reviewer agent after Codex's quota ran out. Each found a defect in
    scope, the fourth a low one, and rounds 3 and 4 found nothing that
    exposes data or acts outside the house. Kira was asked after round
    3 and chose one more round, and after round 4 chose to land. The
    Log has each round.

## Plan

Step one, this issue's criteria: the surface kind without Button, relay's
side only. relay-54 (the relay#96 session) draws surface cards on
relay-tui's board from the contract in `docs/client-protocol.md`.

1. `relay.surfaces`, pure: the catalog as data, the check, the data-model
   paths, the evaluator, the text lines and the A2UI message.
2. `relay.cards`: the kind, its three ops, its fold, text form and wire
   form. `relay.core`, `relay.tools`, `relay.cli`, `relay.workspace` and
   `relay.hub` pass the new fields through.
3. Tests and properties, the catalog file, the fixtures, a pentest.
4. The documents and the spec.
5. `bb verify`, then the Codex loop.

Step two, after step one lands: Button, by Kira's rule in relay#98. A
press of an `event` button tells the owning role in a line relay writes.
A `functionCall` button runs one of relay's own functions with no model
turn.

## Log

- 2026-11-14: started. Worked in the jj workspace `i97` under
  `~/code/projects/.surfaces/`, as relay-32 asked of every session.
- 2026-11-14: read A2UI v1.0's schemas (`common_types.json`,
  `agent_to_renderer.json`, `renderer_to_agent.json` and the basic
  catalog) from a2ui.org. In A2UI an input writes the client's own copy
  of the data model, and the agent hears of it only when an action fires.
  relay's rule is that no client holds the truth, so the operator's input
  is an op of relay's own.
- 2026-11-14: wrote the wire contract into `docs/client-protocol.md` ("A
  surface card") and sent it to relay-32 and relay-54. relay-32 approved
  it. relay-54 (relay#100) draws surface cards from it.
- 2026-11-14: `bb verify` on the chain, before the review fixes: agentloop,
  relay-tui, webtool and eventkit passed; relay 1208 of 1209 and the
  pentests 30 of 31, the two failures being the container ones relay#99
  covers; the new catalog step failed, since relay-tui's copy lands with
  relay#100.
- 2026-11-14: Codex round 1 found five defects and two risks, all in
  scope. Fixed, each with a test: a box bound to a list (a tick replaced
  the list) or to a list's `-`; `formatString` building a huge string
  before its length check; a card too large for relay-tui's line; a
  malformed `formatString` call raising an error, not a refusal; drawing
  not total on a log's odd content. Kept on purpose, with the documents
  corrected: `retitle`, `archive` and `restore` follow any card's rules,
  since they name no path. Passed to relay#100 through the contract: a
  client must not dress outside text, since `ev**il.**example` becomes an
  address once its asterisks go.
- 2026-11-14: Codex round 2 found one defect, two risks and a nit. Fixed:
  a box bound so deep or long that it could never be ticked, by the rule
  that a box is bound only where the data already holds true or false;
  the data's labels reset by a replacement that was not applied; the
  size check not counting JSON escapes.
- 2026-11-15: Codex ran out of quota until 19 November, so round 3 was a
  fresh reviewer agent with the adversarial prompt, the threat model,
  the diff and a scratch copy, and none of my conclusions. It ran about
  6,000 garbage inputs and 3,900 hostile surfaces through the pure
  functions and found no unsafe string, no label or `seen` bypass and
  no error that was not a refusal. It found one defect, a tick refused
  on a surface at a limit, and these risks: data no component reads
  kept up to about 30 MB; `/list/-` with no list making an object;
  `--surface` fetching an address; a crafted surface taking seconds to
  draw. All are fixed with tests, and it named four tests that asserted
  less than they claimed, which now assert it. Two findings are in
  relay.text, older than this work, and went to relay-ed to file: a
  host left whole when glued after `host:digit`, and a link pattern
  that is slow on one very long word.
- 2026-11-15: three rounds each found a defect in scope, so by AGENTS.md
  the loop stops here and the question goes to Kira.
- 2026-11-15: the two eval cases that involve the board ran live on
  Haiku (test/scenarios/BASELINES.md). The model's API takes the new
  tool schemas. One case passed three of three; the other, a known weak
  one, scored one of six here and none of six on the commit below.
- 2026-11-15: after round 4 Kira chose to land. Landed with relay#100:
  relay's `integration` at lzslyxyt bdfc1086, relay-tui's at mxpvltlm
  a2ce30c4, after the joint verify above. Not pushed; relay-32 moves
  `main`.
- 2026-11-15: Kira, asked what to do after three rounds that each found
  a defect, chose one more round: land with relay#100 if it finds none
  in scope, back to her if it does.
- 2026-11-15: rebased over relay#95 part one and relay#118 (links as
  data). Text relay shows is inert now, so a surface's strings are all
  broken in outside text, and its live links travel in the card's
  `links`, read from `relay.surfaces/strings`, each string by itself.
- 2026-11-15: round 4, a fresh reviewer again, ran about 21,000 accepted
  random surfaces and 60,000 junk recorded ones and wrote that the
  security claims hold: no unsafe string, no label bypass, no error that
  was not a refusal, and every box of every accepted surface could be
  ticked and unticked. It found one low defect in round 3's fix: what a
  surface keeps was measured as written, not unticked, so unticking a
  box on a surface at exactly 64,000 characters took it one over. And
  one risk: the refusal for a stale `seen` named the rev the card was
  at, so a role could send the same write again without reading, onto
  an item that had moved. Both are fixed with tests. The refusal no
  longer says the rev. It also showed that three of my tests passed
  with or without the fix they named; each now fails without it.
- 2026-11-14: I had written Tracker as a component of relay's own before
  Kira's answer on it. It is not in the chain. The work was jj change
  omytuowt (commit 504dfeb0), abandoned, which `jj` keeps reachable by
  that commit id until its history is collected.
