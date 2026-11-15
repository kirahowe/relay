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

- [ ] Every line of an open card can be brought into view with the keys,
  whatever the pane's height: a long note, a card shown as its text
  form, and a surface with no inputs.
- [ ] The lines between two inputs that are far apart, and those after
  the last input, can be reached too.
- [ ] A test opens a note taller than the pane and reaches its last
  line. It fails on today's code.
- [ ] `make check` passes.

## Plan

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
