# Move around the dashboard with Esc to move and type to talk

## Context

Kira, 2026-10-23: "we should come up with a more sensible way to navigate
around the panes", weighing a tmux-style prefix against a vim-style move
mode with `i` to type.

relay-tui has three places to be: the role list, a conversation (the one
place to type), and the pane (board or items). Moving between them uses a
mix of chords (ctrl+n/p/l/g, alt+1-9, ctrl+x, ctrl+t), and the list and
board are already modal (single letters act there) without saying so.

Kira chose the hybrid, and to build it without a mock:

- Opening or focusing a conversation puts you in **typing**. Esc switches
  to **moving**.
- In moving: h/l (or left/right) go between columns (list, conversation,
  pane); j/k move within a column; Enter opens or acts; 1-9 jump to a
  role; the board's letter keys work as now; i, or Enter on a
  conversation, starts typing. A letter that does nothing shows "i to
  type" and is not swallowed silently.
- While typing, only a few chords remain: Enter sends, ctrl+c interrupts,
  alt+1-9 jumps roles and stays typing. ctrl+n/p/l/g/x go.
- The status line names the mode, and the framed panels highlight the
  column in focus.
- The transcript view and help stay overlays, closed with Esc/q.

Rejected: a tmux prefix (two key presses for every move; ctrl+space is
macOS's input-source shortcut and a common tmux prefix); move mode by
default (an extra key before almost every message, and stray typing
lands on the board as commands).

## Acceptance criteria

- The keys above, in hub mode and in single-agent mode (where there is
  no list: conversation and pane only).
- No key reaches a card op while typing; no typed text is lost on Esc.
- Card archive and trust keep their confirm, arm delay and rev checks.
- Help, README and the saved view (relay#74) know the mode: reopening
  lands in typing when focus is a conversation, moving otherwise.

## Log
