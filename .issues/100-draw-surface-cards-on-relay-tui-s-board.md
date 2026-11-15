# Draw surface cards on relay-tui's board

## Context

Part of relay#93. relay#96's spike draws A2UI v1.0 surfaces to lines of a
given width (`internal/a2ui`, `render.A2UI` in relay-tui) and is wired to
nothing. relay#97 keeps a role-built surface in relay's log as a card of
a fourth kind and sends it to clients through `_relay/cards` and
`_relay/cards/changed`. This issue joins the two: relay-tui draws a
surface card on the board, among the checklists, trackers and notes.

What the other issues settle for this one:

- relay resolves templates, `@path`, `formatString` and `@index` before a
  client sees a surface, so relay-tui gets final strings (relay#97).
- relay has made every string safe and left its live links whole. The
  spike's renderer still breaks every link itself and has to stop. One
  function in relay-tui decides what is drawn as a followable link.
  relay#95 owns it and surfaces use it.
- A surface has one rev and one set of labels (relay#97).
- What a press may do is Kira's rule in relay#98.

The session that built relay#96 builds this.

## Acceptance criteria

Tightened on 2026-11-14 against the wire form relay#97 fixed
(`docs/client-protocol.md`, "A surface card"), and again on 2026-11-15
where the reviews and the contract moved a rule. The receipts name tests
in relay-tui at yxssltly a92508ec, the tip of thirteen commits on its
`integration` (vywmmpxm 13c740c5).

- [x] relay-tui reads a card of kind `surface` from `_relay/cards` and
  `_relay/cards/changed` as the contract gives it, and folds
  `surface.a2ui` from nothing each time the card arrives.
  - Receipt: `TestTheContractsSurfaceCardIsNative`,
    `TestRelaysSurfaceFixtureCardsAreNative` (relay#97's three fixture
    cards) and the round trip in `TestWireFormRoundTrip`, in
    `internal/wire`.
- [x] The board draws a surface card in a panel with the other cards, at
  any pane width: Text, Row, Column, List, Card, Divider and CheckBox,
  the whole of relay's catalog. A Card at the root has no frame of its
  own, since the panel is one. A Text is dressed as a note card's text
  is and no further, and text an outsider may have written is drawn as
  sent, with no dressing.
  - Receipt: goldens `surface`, `surface_detail` and `surface_outside`
    of `TestBoardGolden`; `TestSurfaceCardsGolden`;
    `TestSurfaceRowsRootCardHasNoFrame`; `TestSurfaceRowsDressText`;
    `TestSurfaceRowsDoNotDressAnOutsidersText`.
  - Receipt: width: `TestBoardNeverEmitsRawControls` draws surface cards
    at random widths, and `TestSurfaceRowsLoseNothingAtOrAboveTheirLeast`
    says a drawing no narrower than the surface's narrowest only wraps.
- [x] A surface relay sends again in `_relay/cards/changed` is redrawn,
  and an archived one leaves the board.
  - Receipt: `TestHubTicksASurfaceCardsBox` (the answer redraws the
    box); `TestAnotherClientsTickThatChangesTheCaptionSendsTheCursorToTheTitle`
    (a notification replaces the card). Leaving the board when archived
    is the board's own rule for every kind, `TestBoardEditAndNotifications`.
- [x] A surface relay-tui cannot draw in full shows the card's text form
  and takes no input: one that does not fold, holds a component outside
  the catalog, names a child that is not there, reaches a component
  twice or not at all, or still holds a call, a template or a binding
  anywhere but a CheckBox's value.
  - Receipt: `TestResolvedRejects` and the two properties in
    `internal/a2ui/resolved_property_test.go`;
    `TestABrokenSurfaceCardShowsItsText` (twelve cases) and
    `TestAWronglyTypedSurfaceMemberKeepsTheCardInItsTextForm` in
    `internal/wire`; golden `surface_text`.
  - Receipt: `TestSurfaceRowsOfAResolvedSurface`: a surface that passes
    the check draws with no placeholder.
- [x] The renderer breaks no link itself. Every string of a surface goes
  through relay#95's functions with the surface's `outsider` flag and
  the connection's live links, every row of an outsider's surface goes
  through the board's last check on drawn rows, and no two strings of a
  surface are drawn on one line with nothing between them.
  - Receipt: a surface's strings are cases of relay#95's own properties,
    `TestOutsideTextIsDrawnAsItWasSent` and
    `TestNothingFromOutsideIsFollowedButALiveLink`;
    `TestSurfaceCardsHostileKeepsOneLink`;
    `TestSurfaceRowsKeepTwoColumnsBetweenChildren`.
  - Receipt: `TestRelaysHostileSurfaceCardKeepsItsListedLink` in
    `internal/wire` reads relay's hostile card as an answer to
    `_relay/cards` and draws it on the board: one hyperlink, to the
    address relay lists beside the card, and none when that address is
    not one of the connection's live links.
- [x] In an open card the keys move between the CheckBoxes whose value is
  bound to a path, and the key that ticks a checklist item sends `input`
  with that path, the new value and the card's rev as `seen`. The board
  draws the card relay answers with and never its own write first. A
  CheckBox with a literal value cannot be selected.
  - Receipt: `TestTickingAnInputSendsTheInputOpAndWaits`,
    `TestALiteralCheckBoxIsNotALine`,
    `TestAnInputOpsParamsAreExactlyTheContractsFive`; end to end
    `TestHubTicksASurfaceCardsBox` and `TestBoardTicksASurfaceCard`.
- [x] The cursor is on a box only where the operator put it. It stays
  there across relay's answer to the operator's own tick and across a
  copy with the same components. Anything else that replaces the card,
  a restart, or a refusal that opens another card leaves the cursor on
  the title, where the key that ticks does nothing, and the board says
  when the boxes changed.
  - Receipt: `TestTheCursorStaysOnABoxWhileTheListOfBoxesIsTheSame`,
    `TestAChangedListOfBoxesSendsTheCursorToTheTitle`,
    `TestTheCursorLeavesABoxWhenARoleEditsTheTextAroundIt`,
    `TestALineThatStoppedExistingDoesNotComeBack`,
    `TestASavedViewDoesNotPutTheCursorOnABoxNobodyChose`,
    `TestALateRefusalDoesNotCarryALineToAnotherCard` (every pair of
    kinds), `TestACardThatChangesKindKeepsNoLine`.
  - Receipt: the selected line of an open card is on the screen, on the
    bar, whichever line it is: `TestTheSelectedLineOfATallCardIsOnTheScreen`
    (a surface and a checklist of forty lines) and the property
    `TestThePaneKeepsTheSelectionClearOfItsMarks`.
  - Receipt: the property `TestSurfaceCursorAcrossEveryPath`, 100,000
    runs: every input sent is for a box the operator moved onto on the
    components held. With the binding switched off it fails.
- [x] relay's refusal of an input is shown as relay wrote it.
  - Receipt: `TestARefusedInputShowsRelaysText`; a stale rev on screen
    in `TestHubTicksASurfaceCardsBox`.
- [x] The card's frame is relay-tui's own: it names the card's owner and
  says "from outside" when the surface, the title or the labels say so,
  and nothing of a surface is drawn outside it. (The contract's fourth
  client rule, added on 2026-11-15.)
  - Receipt: `TestASurfaceCardsFrameNamesItsOwnerAndSaysWhenItIsFromOutside`,
    `TestAnOutsidersSurfaceIsMarked`.
  - Receipt: the owner is in the corner while an op is on its way and
    for an owner that draws as nothing
    (`TestASurfaceCardsCornerAlwaysNamesItsOwner`,
    `TestAnOwnerOfNoWidthLeavesTheKindInTheCorner`), and the selected
    card's bottom border is on screen in the list
    (`TestTheSelectedCardsFootIsOnTheScreenInTheList`).
  - Not met for an open surface taller than the pane: its top and
    bottom borders scroll out of view with the rest. That is relay#110.
- [x] relay-tui's `relay_catalog.json` is relay's
  `resources/a2ui/relay_catalog.json`, byte for byte, and the spike's
  Tracker is gone from the surface renderer.
  - Receipt: `cmp` of the two files on 2026-11-15 after the third copy
    (qsnkqulv); `TestCatalogs`. The catalog step of the early joint
    `bb verify` passed on the second copy.
- [x] fakehub serves surface cards and applies `input` by the contract's
  rules, refusals included.
  - Receipt: `TestAnInputSetsTheBoxAndBumpsTheRev`,
    `TestAnInputIsRefusedAsRelayRefusesIt` in `internal/fakehub`.
- [x] Properties: no surface card draws past the pane's width, and no
  control character and no link relay did not keep live reaches a row,
  whatever the surface's strings hold. relay#96's saved failing case is
  one of the tests.
  - Receipt: `TestBoardNeverEmitsRawControls`,
    `TestA2UINeverDrawsPastTheWidth`,
    `TestA2UIDrawsNoControlBytesFromTheSurface`; the saved case is
    `TestSurfaceRowsBrokenLinkWrappedAfterTheSchemeIsNotFollowable`.
- [x] Kira has seen a mock of a surface on the board first.
  - Receipt: shown to her in the session on 2026-11-15: the board's
    list with two surface cards between a checklist and a tracker, drawn
    by the renderer from relay#97's fixture cards, and then the corner
    that names the owner. Her answer the same day: "yes the mock looks
    great."
  - Receipt: the drawing as built is held by the goldens `surface`,
    `surface_detail`, `surface_outside` and `surface_text` under
    `internal/render/testdata/TestBoardGolden/`.
- [x] relay-tui's README says what the board draws.
  - Receipt: the paragraph on surface cards under "The board", in
    uqwynqos, urpkvpun and plulklqq.
- [ ] `make check` passes, and so does `bb verify` from a relay workspace
  that has relay#97.
  - `make check` passes at yxssltly a92508ec, after the rebases and
    round four's fixes of 2026-11-15 that the Log records. The early
    joint run, relay
    at relay#97's nlyokvuorvpu 2d0c8e8a against relay-tui urpkvpun
    7efde4e4: agentloop, relay-tui, catalog, webtool and eventkit pass,
    relay 1,212 of 1,213 and pentest 30 of 31, the two failures being
    relay#99's. The run that counts is on both rebased tips and is not
    made yet.
- [ ] An adversarial review finds nothing in scope.
  - Four rounds each found something in scope. Round four could not
    break the cursor binding it was aimed at; what it found is fixed or
    filed, and the Log has it. It is with Kira again.

## Plan

Without Tracker and without Button. relay's catalog v1 holds neither:
the tracker card kind stays as it is (relay#93), and Button waits for a
step of its own under relay#98's rule.

Built in four steps, then three rounds of review fixes. What is left:
Kira's word on the review, the joint `bb verify` on the rebased tips,
and landing together with relay#97, relay-tui first.

## Log

Judgment calls, mine unless one says otherwise, 2026-11-14 and 15:

- A surface with anything relay-tui does not draw shows the card's text
  form, whole. The first draft had an unknown component draw as one line
  that names it. A surface drawn in part could leave out an input, or
  the text that explains one. relay#97's session agreed.
- Text is drawn with the note card's code and not through Markdown. I ran
  relay's strings through the spike's Markdown drawing: a live link in
  angle brackets lost its brackets, `ht**tp**s` came out as `https`, and
  line breaks inside a paragraph went. The contract says a text is plain
  and a client dresses it only as it dresses a note.
- Text an outsider may have written is not dressed at all. relay#97's
  review found `ev**il.**example/steal?d=1`: dressing the bold leaves an
  address relay never saw. relay#95 then made the note card follow the
  same rule, and a surface's body text calls the same function.
- A Card at the root is drawn without a frame. The board already puts
  every card in a panel with its title on the border.
- One op at a time, as the board already does: a key pressed while an
  input is on its way is not sent, and the board says so.
- The corner of the panel names the card's owner, such as "steward",
  where a checklist shows its progress. The contract's frame rule asks
  for the owner. I left the word "surface" out because with both the
  title was cut at the usual pane width. The mock asked Kira what she
  wants there and she has not said.
- The spike's Tracker is out of the surface renderer. relay's catalog
  has no component of its own (relay#93), so nothing could reach it.
- `a2ui.Activate` still writes a CheckBox's value into the data model.
  The board does not call it. It stays for the step that adds Button.
- The check that a surface is one relay could have sent takes the
  properties it allows from the embedded catalog file. If relay adds a
  property or a component, relay-tui shows the text form until it
  learns it.
- The mock went into this session's messages on 2026-11-14: relay#97's
  checklist and note fixtures, drawn inside the board's panels between a
  checklist card and a tracker card. No answer yet, so that criterion
  stays open.

The review, read-only, with the threat model:

- Round 1, Codex, 2026-11-14: eight findings. Fixed in xqxnqoxp: the
  cursor took another box with the same label; a Row squeezed boxes to
  bare ellipses that stayed tickable; a `surface` member of the wrong
  type dropped the card; the check accepted more children than are
  drawn and ignored a component nothing lists. Filed, both the board's
  behaviour on trunk: relay#109 (the "gone" notice hidden behind a
  refusal, for checklists and trackers) and relay#110 (an open card
  taller than the pane cannot be scrolled). Rejected: extra values in a
  surface's data (never drawn or sent), and the decoder folding a list
  of messages where relay sends one (the contract says a list, and the
  result must pass the check).
- Round 2, Codex, 2026-11-14: five findings. Fixed in rnlyrwvk: a role
  sorting the text beside boxes that all read Done left the cursor on a
  box that then belonged to another row; a card that fell back to text
  and came back revived a stored line; fixed minimum widths still cut a
  wide emoji label and a list line, so the minimum is measured from the
  content; a2uiview printed an error quoting an id with a control
  character and a whole address. Rejected: a surface with no outside
  text shows a link as the role wrote it. That is relay's rule for
  every card and is with Kira as relay#119.
- Round 3, 2026-11-15, by a fresh reviewer agent with a scratch copy to
  reproduce in, since Codex was out of quota until 19 November. Three
  findings in scope: the saved view put the cursor back on a line number
  after a restart, on whatever box then had it; a refused retitle that
  arrived late reopened its card under the line chosen on another card;
  and the frame did not name the owner. It found nothing in the check,
  the drawing or the wire decoding after several hundred thousand
  generated cases each. Filed from what it did not count: relay#128
  (drawing cost at relay's limits) and relay#129 (a card member of the
  wrong type drops the card, for every kind). relay#122 is a rare
  failure of a ready-view property on trunk, found while running the
  properties deep.
- Three rounds each found the cursor on a box nobody chose, by another
  path each time, so the loop stopped as AGENTS.md says and the question
  went to Kira on 2026-11-15. The root: the cursor on a box was a bare
  line number, and a box has no id, so every path that carried the
  number to another copy of the card or to another card could land it
  on the wrong box. plulklqq fixes that where the number is read: the
  board holds the copy of the surface a key press put the cursor on,
  and reports the title unless that copy is the one shown. It has not
  had a review round. relay-32 recommends one.
- 2026-11-15: rebased the chain over three commits relay#95 put on
  relay-tui's `integration`: the reader for the `links` list relay sends
  with a card or a ready item, and a change of the judge in the tests
  that check a drawn row. The tip is plulklqq 3ba7dadd on ottsqzmp
  74b0d40e. Each of the ten commits vets, and `make check` passes at the
  tip. Two things changed with the rebase.
  - A surface card gets its live links the way every card does.
    `cardLinesFor` takes the card's own list before it draws any kind,
    so a broken form relay lists for a surface card becomes the
    hyperlink to the listed address. A surface card with no list draws
    as it did. No code of relay#100's changed for this.
  - The surface tests judge a drawn row with `followable`, the tests'
    own reader, where they used the production checks `unsafeRow` and
    `linkable`. relay#95 found the production check wrong as a judge
    for its version of `TestA2UIDefangsLinks`: a Row can put the cut
    tail of a broken link in the middle of a line, which no terminal
    follows but the check flags, on rapid seed 373474630933771000 at
    3,000 checks. Mine had the same judge in four places. The test
    bodies are as they were. The surface and link properties pass on
    that seed and at 5,000 checks.
- 2026-11-15: Kira answered the three questions put to her: "ok great.
  these seem reasonable, and yes the mock looks great." My reading: the
  binding is the right root fix, the review round relay-32 and I
  recommended goes ahead, and the mock is accepted. Round four started
  the same day, by a fresh reviewer agent with a scratch copy of
  plulklqq 3ba7dadd, since Codex is still out of quota. It is aimed at
  the cursor binding, the frame and the weight clamp, with a fresh look
  at the rest because the base moved.
- 2026-11-15, later: copied relay's sample cards again (mllyxnmy). They
  carry `links` and `linksLeftOut` now, and the tests draw them from
  that list. No golden changed. Rebased over three more of relay#95's
  commits (hidden characters); no conflict.
- Round 4, 2026-11-15, by a fresh reviewer agent with a scratch copy of
  plulklqq, 110 tool calls, about 40 minutes. It could not break the
  cursor binding: a model of relay and an operator with its own oracle,
  150,000 runs each for one agent and for the hub, 31,000 input ops
  checked, never sent an input for a box the operator had not selected.
  It found nothing in the links, the check, the weights or the drawing
  at relay's limits. It counted three findings in scope, all around the
  fix and two of them older than this change.
  - A late refusal still carried the line, onto a checklist or a
    tracker: round three's fix covered only a reopened surface. Fixed
    at the source in yxssltly: `reopen` sends the cursor to the title
    whenever the refused card is not the one the operator is on.
  - In the list the pane's "↓ N below" was drawn over the selected
    card's bottom border, where "from outside" is, and in an open card
    taller than the pane over the selected line itself, so x acted on a
    line that was not drawn. Older than this change and the same for
    every kind. Fixed in yrxoryto: the window keeps a row for each mark
    outside the selection. No golden changed.
  - The corner said "sending…" in place of the owner while an op was on
    its way. Fixed in yxssltly: the owner first, the state after it.
  - Not counted by the reviewer and left: an open surface taller than
    the pane scrolls its top and bottom borders out of view (added to
    relay#110, which I take next); keys held while "connecting…" shows
    are replayed onto a restored card; the saved view puts a
    checklist's cursor back by number; an op that times out reads as
    refused. The last three are filed as relay#131, relay#132 and
    relay#133.
  - Rejected: keys acting on a card the unloaded board does not draw
    (two unlikely failures at once: a notification with no good first
    answer, and keys pressed blind); a line masked on a surface coming
    back when the card turns into a checklist (relay would have to send
    one card as two kinds); wire and the check taking a path outside
    the key grammar, two boxes on one path, or extra members of
    createSurface (relay would have to send them, and nothing drawn or
    sent differs from what relay sent). An owner of no width widening
    the border is handled for a surface's corner; for an unknown kind's
    name it needs relay to send such a kind.
  - Left as it is: the hint shows "e retitle" with the cursor on a box,
    where e does nothing. The hints are one line for a kind, as "x
    tick" is on a checklist's title.
  - The reviewer's own probes and model, run on the fixed tree in a
    scratch copy: the late-refusal and corner probes pass, the tall-card
    and list probes pass, and what still fails is the tall surface's
    borders and the two rejected or filed cases above.
- The tip is yxssltly a92508ec, thirteen commits on relay-tui's
  `integration` vywmmpxm 13c740c5. `make check` passes there. Round four
  found something in scope, so by the rule it goes back to Kira before
  landing.
