# Show the board in the acp-tui workspace pane

## Context

The client half of cards (#54). acp-tui shows the operator's board in its
workspace pane and holds only what relay last sent. Every key sends an op
and the card is redrawn from relay's answer or from a
`_relay/cards/changed` notification. The spec is the build brief at
`~/code/projects/.cards/BRIEF.md`; the design page is
https://claude.ai/code/artifact/699995de-3c38-45a9-8cc8-95d36141d806.

Work is in the jj workspace `~/code/projects/.cards/acp-tui` (baseline
`20231137`).

## Acceptance criteria

- [x] A Board view in the workspace pane loads `_relay/cards` and applies
      `_relay/cards/changed` by card id, dropping archived cards.
  - Receipt: internal/core TestBoardAsksOnceWhenFirstOpened, TestCardsChangedReplacesAndDropsArchived, TestSnapshotKeepsANewerNotification; ui TestBoardSnapshot, TestBoardEditAndNotifications.
- [x] An agent without the method gets "This agent has no board."
  - Receipt: core TestBoardWithoutTheMethod; ui TestBoardWithoutRelay.
- [x] Checklist, tracker and note are drawn natively; any other kind shows
      relay's text form.
  - Receipt: render TestBoardGolden (checklist, tracker, note and an unknown kind).
- [x] Outsider text is marked and shown exactly as relay sent it. No live
      links, nothing fetched.
  - Receipt: render TestOutsiderTextIsMarked, TestBoardNeverEmitsRawControls; wire TestRelayCardFixtures (contaminated note from real relay acp).
- [x] Keys tick items, cycle cells, add and edit text (with `seen`), and
      archive. A refusal shows relay's text.
  - Receipt: core TestRefusalIsShown, TestRefusalGivesTheTypedTextBack, TestTrackerCellCycles; ui TestBoardOpRoundTripAndRefusal, TestBoardInputKeys.
- [x] Fake-agent tests, plus fixtures recorded from the real `relay acp`
      (testdata/relay) run through the real wire, core and render code.
  - Receipt: wire TestRelayCardFixtures over testdata/relay, recorded by relay's scripts/card_fixtures.clj; fails when the outsider flags are flipped.
- [x] `make check` passes.
  - Receipt: make check on 2026-10-20 after af1fe91a: gofmt clean, vet ok, race tests ok in every package.

## Decisions

- The board is a toggle, not a tab. `b` in the focused pane switches
  between the workspace items and the board, and the pane header names the
  current view. The board stays the pane's view when focus leaves it, so a
  wide screen keeps it beside the chat.
- Layout follows the items view: a list of compact card blocks, and Enter
  opens a card, where the ops are. Esc backs out of the card, then out of
  the pane.
- Keys: j/k or the arrows move between cards, or between the lines of an
  open card; h/l pick a tracker column; x or space ticks an item or cycles
  a cell open → done → skip → open; a adds an item or row, A adds a column;
  e edits the selected line, E renames the column; X then y archives; r
  reloads; b switches views. The board has its own key table, so space
  still pages in the items view.
- Archive asks for a y but has no arm delay, unlike trust, because restore
  undoes it. The TUI offers no restore and no archived listing; the CLI
  does.
- An edit sends `seen` = the card's rev when the input opened, so a change
  that lands while the operator types is refused instead of overwritten.
- Editing outsider text starts from an empty input. Whatever the operator
  sends becomes operator text with no labels, so sending an outsider's
  text back unchanged should not be one key away. The input takes only
  printable characters, and pastes still go nowhere while the pane has
  focus.
- A note's text is not editable in the pane. A one-line input would
  destroy its line breaks, and making `e` depend on whether a note happens
  to be one line would be confusing. On a note, `e` retitles; the text is
  changed from the CLI or by a role. Remove ops are not bound either.
- One op in flight at a time; navigation still works while it is sending.
- A copy of a card with a lower rev never replaces a newer one, and the
  board remembers the rev an archived card left at, so a stale answer
  cannot bring it back. Cards that a notification brought before the first
  snapshot are kept.
- `_relay/cards` is asked only once the session exists, and never again
  after method-not-found.
- Text relay already defanged is not defanged again. The client defangs
  only if a link in outsider text is still live, as a backstop.

## Log

- 2026-10-20: built by the cards workflow: implementation, one
  clean-context review round (2 real defects fixed: cards from an early
  notification dropped, `changed: false` ignored), clean QA (hint gaps,
  refused text thrown away and a stale busy note, all fixed), and an
  integration test against recorded `relay acp` answers. A follow-up round
  is fixing the selection drifting when a notification reshapes a card, a
  stale "Refused." message, and keys missing from the list hint.
- Known gap: the global `?` overlay lists no pane keys (this predates the
  board).
