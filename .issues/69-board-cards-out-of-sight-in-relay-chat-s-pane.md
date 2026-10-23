# Board cards out of sight in relay chat's pane

## Context

On 2026-10-22 the Hub said in relay chat that it had put a "Focus by area"
note on Kira's board (card_add ran), but the pane beside the conversation
read "workspace (0) · nothing yet".

The card was saved. Relay's hub serves `_relay/cards` and pushes
`_relay/cards/changed`; acp-tui's hub e2e test covers the board. The fault
was in acp-tui: the pane opens on the workspace items, it asked relay for
the board only after ctrl+x then b, and nothing on screen said there was a
board. relay subscribes a connection to card changes on its first
`_relay/cards`, so before that nothing arrived either.

Reproduced on staging data (`bb staging init`, `relay card add note`,
`relay chat` in tmux with an acp-tui built from source). The pane appears
only once a conversation exists, which needs a model call, so the end-to-end
check lives in acp-tui's ui tests against the fake agent instead.

## Acceptance criteria

Commit: acp-tui mqwqlkzq "Bring the board into view when a card changes".

- [x] The pane names the board and its count from either view, and the
  unfocused footer says how to reach it.
  - Receipt: render `TestPaneNamesTheBoard`; TestBoardGolden headers now
    read "board (4) · workspace (0)".
- [x] A card added or changed during a turn comes into view without a key.
  - Receipt: ui `TestACardAddedInATurnShowsWithoutAKey` (fails on main's
    board.go: "timed out waiting for the count"); core
    `TestAChangedCardBringsTheBoardIntoView`.
- [x] The switch never takes the pane while the operator works in it or a
  proposal waits.
  - Receipt: core `TestTheBoardStaysOutOfTheWay` and the rapid property
    `TestTheBoardNeverTakesThePane`; both fail with the focus guard removed.
- [x] `make check` in acp-tui passes (gofmt, vet, race tests).
  - Receipt: all packages ok on mqwqlkzq, 2026-10-23.

## Plan

Kira chose (2026-10-22): count in the header plus switching to the board
when a card arrives, over making the board the default view, stacking both,
or hints only.

## Log

- Ask for the board on session start (Connected / ConvAttached), open or
  not. One extra `_relay/cards` per conversation attach; it is a local call
  to relay and subscribes the connection to changes.
- Switch only when `put` stores an active card with a new rev, and only once
  the board has loaded: an archived card, a copy already held, or a change
  before the first answer leaves the view alone.
- Gate: no switch while the pane has focus, a trust prompt is open, or a
  proposal is waiting. The board selects the changed card.
- The header drops the other view's count when the pane is too narrow for
  it, keeping the focus key.
- relay's README describes only the wire protocol, which did not change. The
  acp-tui README's board section is updated.
- No Codex review: the change is display and when the client reads the
  board. It touches no label, hold, approval or outside network call.
