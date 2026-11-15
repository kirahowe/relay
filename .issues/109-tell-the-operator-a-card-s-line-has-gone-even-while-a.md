# Tell the operator a card's line has gone even while a refusal is showing

## Context

relay-tui. Found by Codex in round one of relay#100's review and sent by
the relay#100 session on 2026-11-14. It is how the board behaves today
for checklist items and tracker rows, and surfaces inherit it.

The reviewer's text:

> An existing failure suppresses the notice that the selected box
> disappeared. Location: internal/core/board.go, reanchor.
>
> 1. Open a card and select a line.
> 2. Press r; the reload fails, and CardsLoaded{Err} leaves
>    Board.Failure nonempty (an outstanding refusal does the same).
> 3. A later, valid CardsChanged removes the selected line and keeps its
>    neighbour.
> 4. reanchor moves the cursor to the nearest line but suppresses the
>    'gone' notice because the earlier failure remains.
> 5. Press x. boardKey clears the old failure and sends the op for the
>    neighbour. There is no deferred notice and no requirement to select
>    the line again.

So a key meant for the line that went acts on its neighbour, and nothing
told Kira the line had gone.

For surfaces, relay#100 sends the cursor to the title when the selected
input has gone, and x does nothing there. The same would work for the
older kinds: when the selected line has gone, put the cursor on the
title.

## Acceptance criteria

- [ ] When the selected line of an open checklist or tracker has gone,
  the next key cannot act on a neighbour. Either the cursor is on the
  title, or the notice is shown whatever failure was showing before.
- [ ] A test walks the reviewer's five steps and fails on today's code.
- [ ] `make check` passes.

## Plan

## Log
