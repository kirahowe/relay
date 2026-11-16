# Scroll an open card that is taller than the pane

## Context

relay-tui. Found by Codex in round one of relay#100's review and sent by
the relay#100 session on 2026-11-14. It is how the board behaves today,
and surfaces inherit it.

The reviewer's text:

> Text below the viewport can be permanently unreachable in an open
> card. Location: internal/core/board.go (Lines),
> internal/render/board.go (boardParts), internal/render/pane.go
> (paneDraw). Open a card whose body is taller than the pane and has no
> selectable line but its title, such as a long note, a text form or a
> surface with no inputs. Up, down and the page keys stay on the title.
> boardParts never enables scrolling; paneDraw keeps the viewport at the
> top and reports zero scroll capacity. The last lines cannot be
> reached. The same happens between widely separated inputs and after
> the last one. Opening the card does not recover what the list preview
> cut.

The relay#100 session's note on a fix: the key event needs the view's
first row passed to it from the frame, as the items view's maximum
already is. That session offered to take this after relay#100.

## Acceptance criteria

- [x] Every line of an open card can be brought into view with the keys,
  whatever the pane's height: a long note, a card shown as its text
  form, and a surface with no inputs.
  - Receipt: `TestATallNoteCanBeReadToItsLastLine` (hub and single
    agent, wide and narrow screens), `TestATallTextFormCanBeReadToItsLastLine`
    and `TestATallSurfaceIsReadInItsFrame`, internal/ui/tall_card_test.go,
    relay-tui upkvtxyy, run at 80 by 24 and larger since oyposnpp
- [x] The lines between two inputs that are far apart, and those after
  the last input, can be reached too.
  - Receipt: `TestATallSurfaceIsReadInItsFrame`, boxes 30 lines apart
    with 30 after the last: down reads to the end, tab stops on every box
  - Receipt: `TestTheFocusReachesEveryLineOnAnyPane`,
    internal/render/tall_test.go: every line of each fixture card at every
    height from 1 to 30 and three widths
- [x] A test opens a note taller than the pane and reaches its last
  line. It fails on today's code.
  - Receipt: `TestATallNoteCanBeReadToItsLastLine`; on relay-tui
    integration (mxpvltlm, before this work) it failed with "the last line
    is out of reach"
- [x] `make check` passes.
  - Receipt: `make check` in relay-tui.i96 at upkvtxyy and after each fix,
    and at the final tip rrnwtulr 41d3850d (rebased onto relay-34's
    ppqnkvxw dbebb09f), exit 0
  - Receipt: joint `lockf -k /tmp/relay-verify.lock bb verify` from
    relay.i96 (integration kysqppyu) with relay-tui.i96 at rrnwtulr on
    vxpqlutq, agentloop.i96 and webtool.i96 at main: all eight steps
    passed (agentloop, relay-tui, catalog, webtool, eventkit, relay, fuzz
    seed 1794883579 with 400,000 cases and 0 followed, pentest 33 of 33)

## Plan

Each kind of open card takes the keys the way its kind of widget does. A
checklist or tracker is a list: up and down move the selection and the
card scrolls to keep it in view. A note, a text form or a surface is read
like a page: up, down and the page keys scroll it. On a surface, tab and
shift+tab move the focus from box to box, as in a form, and bring the box
into view. The frame stays in place under the pane's head and only the
inside scrolls. relay-tui needs a terminal of at least 80 by 24. The pane hands back where the window starts, how far it
scrolls and whether the focused box is on screen. The core uses the last
of these for x alone: x on a box scrolled out of sight brings it back
and ticks nothing.

## Log

- 2026-11-15, from review round four of relay#100 (the relay#100
  session). The reviewer's text on the same mechanism:

  > An open surface taller than the pane: the selected box is under
  > "↓ N below", x acts on it, and the owner corner and "from outside"
  > are off the screen. Input: outsider surface with 40 one-line boxes,
  > pane 60x24, detail view. With the cursor on any of lines 19 to 40,
  > no row is on the bar and the selected box is not drawn. With the
  > cursor on line 20 or 40 neither "steward" nor "from outside" is on
  > screen; "from outside" is on screen at no cursor position at all.
  > The filed wording says it cannot be scrolled, and what happens is
  > that it does scroll, one row at a time, with the selected row
  > always covered.

  The first half is fixed in relay#100's chain (relay-tui yrxoryto):
  the pane's window keeps a row for each of its marks outside the
  selection, so the selected line is on screen. What is left for this
  issue, beside the criteria above: an open card taller than the pane
  scrolls its top and bottom borders out of view. For a surface the
  contract's frame rule wants the owner and "from outside" on screen,
  so the frame of an open card should stay in place while its body
  scrolls. That changes the look, so it wants a mock first.

- 2026-11-15, the relay#110 session (the same one as relay#100). The
  mock went to Kira with two choices for page up and page down: only
  scroll, or move the selection by a page as before. Kira chose "pages
  only scroll", so x can act only on a box picked with up or down that
  is on screen.
- Judgment calls, made while building:
  - Up and down move in the order the screen reads (by first row), not
    in the order of the card's lines. A first version went by line
    number, and the property test found a loop: two boxes side by side
    in a Row, in the opposite order on screen, sent the window back and
    forth for ever.
  - When up or down scrolls the selected line off the screen, the cursor
    goes to the next line on screen that way rather than to the title.
    Going to the title made up miss lines: from the title, up only
    scrolled. Page keys still give the cursor back to the title.
  - From the title, up goes to the last line on screen, except with the
    window at the top, where the title stays: there it would put the
    cursor on a box below the title for an up key.
  - The surface drawing now reports the rows of every checkbox from one
    draw. Asking for each box by drawing it focused would have cost a
    draw per box for every frame.
  - A card that fits keeps its own height in the frame, so the board's
    goldens change only in the key hints: "pgup/pgdn scroll" (or "↑↓
    scroll · pgup/pgdn page" on a card with no lines to select).
- Review with Codex (read-only), three rounds. Each found something in
  scope, and each finding is fixed with a test that fails on the commit
  before the fix:
  - Round one (on vnqslukk), fixed in wsozpppx:
    - On a pane too short to draw the card, the keys fell back to
      stepping by line number, and x sent a box nobody could see. The
      shell now always hands back a view while a card is open, and x
      refuses while the selected line is off the screen
      (`TestABoxOffTheScreenIsNotSent`).
    - Up looped between a tall box and a short one side by side in a Row.
      The window now only moves the way the keys go
      (`TestUpEscapesOverlappingInputs`; the property tests now generate
      overlapping lines).
  - Round two (on wsozpppx), fixed in ysotkzvl:
    - Selecting a tracker row added the cell line to the foot, which took
      the only row of body, so down cycled between the title and the
      first row. The cell line now always has one row
      (`TestDownReachesTheLastLineOnAnyPane`).
    - With room for the borders and none for the body, the frame was
      dropped. It now stays (`TestTheFrameStaysWhenOnlyItsBordersFit`).
  - Round three (on ysotkzvl), fixed in pxtkwkvl. Round two's one-row
    cell line let a long row name cut the column, and the narrow tracker
    layout did not mark the selected cell. So right changed the cell x
    would mark with nothing on the screen changing. The line now leads
    with the state and the column, and the narrow layout marks the cell
    (`TestTheSelectedCellIsOnTheScreenWhateverItsRowsName`).
  - Three rounds in a row found something, so by the review rule the loop
    stops here and the question goes to Kira: review the round-three fix
    in a fourth round, or land. The findings were all on panes a few rows
    tall or on narrow trackers. None was about the design Kira chose, but
    two of them came from the fixes before them.
- 2026-11-15, the redesign. Kira saw the review thrashing and asked to
  step back and find the root cause rather than patch. My diagnosis: one
  cursor was doing two jobs, reading a document and pointing at a
  control. Choosing between them needed the layout, which only the
  renderer has, so the drawn rows went back into the core with each
  key, and the drawing in turn depended on what the core had chosen.
  Every finding sat on that loop. Kira's direction: use the standard
  concept of focus, where whatever has focus gets the keys, and invent no
  new paradigm. So each card takes the keys as its widget kind does
  (list, page, form; see Plan). Kira approved, including tab for boxes
  on a surface in place of up and down.
- The first design's four commits were abandoned unlanded (vnqslukk,
  wsozpppx, ysotkzvl, pxtkwkvl). The rebuild is upkvtxyy, on integration
  at kwxlsxtvvswk. Judgment calls in it:
  - x on a focused box scrolled out of sight brings it back into view
    and ticks nothing; the next x ticks. A browser would act on it. Here
    x must never act on a box the operator cannot see.
  - Tab past the last box goes back to the title, and shift+tab from the
    title to the last box, as focus wraps in a form.
  - On a checklist or tracker, tab and shift+tab do what down and up do,
    as they did before.
  - The tracker's cell line and the rest of the pane keep the look they
    had on integration: nothing the keys choose depends on the drawing,
    so their height can follow the selection again.
- yuuzltyl, beside it, fixes a race in
  `TestTheBoardIsTheDefaultAndACardAddedInATurnShows`: it waited for the
  card and assumed the reply was drawn too. It failed once under the full
  make check and never in 30 runs alone, with or without this change.
- 2026-11-16, review of the rebuild with Codex, six rounds. The focus
  design held from round one on. Each round found something; each finding
  is fixed with a test that fails on the commit before the fix:
  - Round one (on upkvtxyy), fixed in mtlyovss: page down hid a row
    behind the marks; a checklist item taller than the window could not
    be read to its end (the page keys now scroll every open card, lists
    included); a notice for an off-screen x took the rows the box needed;
    tab no longer moved through the link list.
  - Round two, fixed in xzvtpsnr: on a short pane the key hints left an
    open tracker no row of body. The hints now give way to the body.
  - Round three, fixed in soqzmrvx: a tracker's long cell line and an
    empty surface crowded the card out of a short pane. The pane's rows
    now go out in one order: head and foot, the frame, three rows of body,
    the line under the card, the hints.
  - Round four, fixed in pxoxpvuo: the hub cut a pane after it had drawn
    a box; a cell line was cut in half; an archive question lost its
    card's name (that one predates relay#110). Fixed then with a least
    size per open card, since replaced (see below).
  - Round five, fixed in uqszvoov and srqzxvoo: in single-agent mode the
    check that a link question is on screen before a yes asked the hub's
    state and never held a yes back (predates relay#110); a focused line
    counted as shown from its first row, so x could mark a narrow
    tracker's cell below the window. A line now counts only when all of it
    is on screen.
  - Round six, fixed in oyposnpp and rrnwtulr: the board's list view could
    take an answer to an archive question or the editor while they were
    off a tiny screen (predates relay#110); a tracker grid's column names
    scrolled away. The names now stay at the top as the rows scroll.
- 2026-11-16, Kira stopped the loop and asked what was going on. My
  answer: after round one every finding was on a screen a few rows tall,
  or predated relay#110, and my brief to the reviewer covered the whole
  app at any size, so it could not converge; each fix guessed at a layout
  decided elsewhere. Kira's decisions: a pragmatic constraint on
  reasonable use rather than every obscure edge case; relay-tui is for
  terminals (mobile will have its own app); the least terminal is 80 by
  24; the hub's narrow layout, which that size makes unreachable, is
  deleted. oyposnpp does both and drops the per-card least size of
  pxoxpvuo. Tests that ran smaller now run at 80 by 24 or larger (a
  subagent raised the ui tests; I checked its two risky changes: the long
  reply still overflows 24 rows, and the link-host test keeps its point
  with a longer host).
- Not reviewed: oyposnpp and rrnwtulr had no Codex round of their own.
  Kira asked to wrap up and land after the decision above.
- Possible later work, offered to Kira and not started: hit-testing, so
  the final frame lists the targets it drew and a key can act only on one
  of them, in place of the visibility flags. A design change to the whole
  app, so its own issue if Kira wants it.
- 2026-11-16, landing. relay-32 moved relay-tui integration to relay-34's
  ppqnkvxw while my verify ran. I rebased the chain onto it (no
  conflicts) and `make check` passed again. relay-32 reads the chain and
  lands it, then runs the joint verify for main.
