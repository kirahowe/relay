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

2026-10-23, phase 4 (relay-tui):

- State: `App.Moving` (per connection, both modes) says moving; its zero
  value is typing, so a fresh single-agent client types. The column is
  the existing state: `Hub.List`, `App.PaneFocus`, else the
  conversation. `Hub.PaneFromList` and `Hub.Last` are gone. The core
  keeps the mode true to the column after every event: the list, the
  pane, or no role in focus all mean moving, and with no role in focus
  and no pane the list has the keys. Entering the pane sets moving
  itself, so a pane key can only act while moving.
- Events: `Move{Delta}` (h, l), `Type` (i, enter on the conversation),
  `StopTyping` (esc), `Unbound` (the "i to type" note). `FocusMove`,
  `FocusLast` and `ToggleList` are gone. `ListOpen` and `--open` type;
  `FocusIndex` (1-9 moving, alt+1-9 either mode) keeps the mode and
  gives the keys to the role's conversation, from the pane too (it used
  to keep the pane when the role did not change).
- h and l skip a column with nothing in it: with no role in focus, l on
  the list goes straight to the board, and h from the board to the list.
  At the edges they do nothing. Moving into or out of the pane goes
  through the old toggle, so what was unsent there (a card trust, an
  archive question, a doc trust) is dropped as before. The card trust
  also drops when the column changes (`hubView` now includes the pane),
  so no path out and back can keep an armed trust.
- In an open tracker card, h/l and left/right keep moving between the
  tracker's columns, as before; esc closes the card and they move
  between screen columns again. Chose this over "h at the first tracker
  column leaves", which would make the same key do two things by
  position.
- The pane's own keys come after the column keys (h, l, i, 1-9, ?,
  ctrl+c, ctrl+d), none of which the board or items use, except when the
  pane is asking something (archive confirm, card or doc trust not yet
  sent) or its one-line input is open: then every key goes to the pane,
  as before, so y can only answer what is on screen and a digit types
  into the input. ctrl+c there cancels the question or the input, or
  stops a running turn. Without this, `?` then `y` could archive: help
  does not clear the archive question.
- Esc: in typing it stops typing and keeps the draft; today's input did
  nothing with esc (the textarea ignores it) and a running turn has no
  esc binding, so no key lost a meaning. ctrl+c still interrupts in both
  modes. In moving, esc steps back: closes what the pane has open, then
  leaves the pane (to the conversation, or to the list with no role);
  on the list it goes to the conversation when there is one. A request
  on offer keeps esc for cancel, as before.
- Permission requests: the request on offer has the keys in either mode,
  ahead of everything but the end question, exactly as before; only the
  request's own keys answer it, and the arm delay is unchanged. Past its
  keys, h (to the list), 1-9 and alt+1-9, and the page keys work, as
  ctrl+g, ctrl+n and alt+N did; l cannot take the keys to the pane (the
  toggle refuses with a request waiting). I made this the same in both
  modes rather than leaving typing with no way to the list but answering:
  while a request waits the input is not in play anyway.
- ctrl+c while moving stops a running turn, else quits; it never clears
  the input, so a draft survives a quit from moving (the saved view
  keeps it). ctrl+d quits only from an empty input, in both modes.
- Unbound: a printable key that does nothing while moving shows "i to
  type" (hub notice, single status message) and does nothing else. A
  paste while moving does the same rather than landing in the input.
- Removed: ctrl+n, ctrl+p, ctrl+l, ctrl+g, ctrl+x, ctrl+s (end is X
  while moving on the conversation, still asking y/n), and ctrl+/ and
  ctrl+_ for help in the input (esc then ? does it). Kept in typing:
  enter, the four newline keys, esc, alt+1-9, pgup/pgdown (hub; not
  chords, and scrolling while composing is real use), ctrl+t and ctrl+o
  (open what a tool did while writing a reply), ctrl+y, ctrl+c, ctrl+d,
  and ? on an empty input. The transcript overlay keeps its keys, and
  help closes with esc, ? or q.
- Single-agent mode: two columns, conversation and pane; h at the
  conversation does nothing. j/k there do nothing (the conversation is in
  the terminal's scrollback; ctrl+t/ctrl+o open the transcript), so they
  show the note.
- Narrow screens: as before, one column at a time. The list is the
  screen when it has the keys, and the pane covers the conversation when
  it has them; h and l walk between them. With no role in focus the pane
  takes the list's place. This keeps how phase 3 drew the pane from the
  list, and is what the phone would see.
- The mode shows as TYPING or MOVING in bold: in the panels it leads the
  row of keys at the bottom, and elsewhere (narrow hub, single) it sits
  in the status line after the newline hint, so at 40 columns the line
  still keeps what the agent is doing and how to type. While a request
  waits the status line leaves it out. The panel with the keys already
  had the accent frame; that is now always the column. While moving
  there is no cursor in the input.
- Saved view: the column is saved, not the mode (simpler, and what the
  issue asks): restore lands typing when the keys come back to a
  conversation, moving otherwise. `HubView.PaneFromList` is gone, and
  the file format keeps version 1: a phase 3 file with `paneFromList`
  reads as damaged and gives the defaults with a note. Phase 3 was never
  deployed. Restore cannot flip the mode under typing: a key cancels the
  pending layout (relay#74's first Codex fix).
- Also fixed: the doubled prompt in the panels ("› › text"). The panel
  now puts its accent mark in place of the text area's own, and the text
  area gets the full inside width (it was cut two columns short).
- Tests: core transitions (columns and edges, skipped empty column, jump
  keeps the mode, i with no role, unbound note, single's two columns,
  restore lands in the column's mode) and a property that typing is only
  ever in a role's conversation and no pane key acts while typing. In the
  shell: a property that no key while typing reaches the board or answers
  a request off screen; a property that no key while moving changes any
  draft and esc keeps it (hub and single); digits vs alt+digits; a
  request keeps its keys in either mode. The help rows and the
  classifiers are checked both ways over every printable key and the
  ctrl and alt letters, and the README's two key tables against the help
  rows.
- Verified live: scratch data dir, roles gardener and writer (agent
  /usr/bin/false), two cards via `relay card add`; `relay-tui
  --state-dir <tmp> -- relay acp --approvals --data <dir> --run-dir
  <dir>` in a private tmux at 170x40, then 60x20. Started moving on the
  list; j, enter on Writer typed; a draft, esc kept it (MOVING); w showed
  "i to type"; h to the list, l, l to the board, enter, j, x ticked an
  item (`relay cards` showed it done); h back, i typed onto the same
  draft; alt+1 went to Gardener still typing; esc, 2 back to Writer still
  moving with its draft; ? showed the moving keys. Quit and reopen landed
  typing in Writer with the draft; quit from the pane and reopen landed
  moving on the board with the card open. At 60 columns h and l walked
  the list, the conversation and the pane over it.

2026-10-23, Codex round 1 over phase 4 (relay-tui ovnlqpmw..krxzsrto):
four in-scope defects and a nit, each fixed in its own relay-tui commit
with a test that failed before the fix.

- Typing could approve a request (relay-tui orqotqox). A request that
  arrived while composing took the keys at once, so a y typed more than
  600 ms later approved it. The main session decided: a request is
  answered only while moving. While typing it takes no keys; a bold line
  above the input ("permission needed", the tool, "esc to answer") and
  the status line ("esc to answer") say it waits, and the row of keys
  leads with "esc answer". Its delay starts when it becomes answerable
  (`Hub.answerable`: on screen and moving; esc, moving to its role, or
  arriving while moving), and in single mode on the switch to moving.
  The core also drops answers (allow, reject, confirm, cancel, move,
  show all) while typing, in both modes, so the rule does not rest on the
  shell. A paste while a request waits now lands in the input when
  typing. ctrl+c still stops the turn, and that cancels its requests on
  purpose, so the property test leaves ctrl+c and ctrl+d out. Esc while
  typing with a request on offer now moves rather than cancelling it
  (it used to cancel); esc again cancels. After an answer the operator
  stays moving; i types again.
- Closing the help also acted (relay-tui kuypskkw). Any key closed the
  list of keys and then did what it does, so at 60x20 ? then x ticked a
  checklist item hidden under the list. The closing key is now used up.
- Nit: with no role in focus the conversation panel's top border carried
  an empty title; it now runs unbroken (relay-tui trvpwlnp). `panel`
  draws no title gap for an empty title.

The other two are in relay#74's Log (Ctrl-Y after a restore, typing
before the hub is known). Also found and fixed there: a phase 3 property
test whose simulated damage was unrealistic.

Not fixed, for the main session: `render.TestWidthBound` failed once on
a random seed, a thought entry with "AAAA" and U+FE0F (emoji
presentation) measured 9 wide at width 8. It is in the entry renderer,
unrelated to navigation and older than this work; the failing input is
kept in the scratchpad, not in the repo. It should be an issue.
