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
