# List the pane's keys in the help overlay

## Context

The `?` overlay lists the typing and moving keys. For the pane it shows
only one line, "in the pane, its own keys are along its foot", so the
board's keys (x, a, A, e, E, X, r, b and the rest) appear nowhere in
help. The foot hint is cut to the pane's width, so on a narrow pane some
keys are never shown at all. Found while closing relay#55; the gap
predates the board.

See `HelpLines` and `paneHelp` in relay-tui `internal/render/transcript.go`.

## Acceptance criteria

- [ ] With the pane in focus, `?` lists the keys of the pane's current
      view (items or board, list or open card).
- [ ] A test fails if a key bound in the pane's key table is missing from
      help.
