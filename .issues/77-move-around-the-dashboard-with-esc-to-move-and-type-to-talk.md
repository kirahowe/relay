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

2026-10-24, the surface redesign (relay-tui xprupxzl), after Codex
round 2 over krxzsrto..orqotqox kept finding the screen and the key
router disagreeing. Kira approved removing the class rather than the
cases.

- One pure function decides what has the keys: `core.HubSurface` and
  `core.SingleSurface` return a `Surface` (kind, the kind under the
  help, and the column). Kinds: conversation (moving), input, editor
  (the board's one-line editor), request, confirm (archive, card trust,
  doc trust), pane, list, end, help, transcript. `Typing()` is input or
  editor. The renderer draws that surface as active (frames report
  `Active`, which a property test compares) and only draws an overlay
  when it is the surface; the shell's `routeKey` classifies a key
  against that surface's keys alone; and `ReduceHub`/`ReduceSingle` drop
  any operator event the surface does not take (`Surface.takes`), so a
  misrouted key still cannot act elsewhere. The old `Moving` guards on
  answers, `answerable`, `inputInPlay`'s request check and `PaneAsking`
  are gone.
- Judgment call: a request arriving never moves the keys, in any
  surface, not only while typing (the brief's "never moves focus" read
  literally). It used to take the keys from the pane. Now the pane keeps
  them and keeps working (ops, trusts and the archive question all work
  with a request waiting, each its own surface), the conversation and
  the status line say how to get to the request ("h to answer", "esc,
  then h to answer" from the editor or a question), and `l` may enter
  the pane while one waits. The request is answered only as the surface,
  and its delay starts when it becomes the surface: esc, moving to its
  role or column, what covered it closing, or arriving while its
  conversation has the keys. A request arriving also no longer drops an
  unsent doc trust: it closes nothing.
- Against a plain agent a request still closes the transcript and the
  help, as before. Ctrl-T and Ctrl-O no longer open the transcript while
  a request waits (it could not be drawn with it); the status line says
  to answer it first (Codex finding 5). Help while typing with a request
  waiting is drawn, and the request shows as its note under it.
- In hub mode the end question and the help cannot be opened over the
  request surface (the shell never sent X or ? there; now the core
  refuses them too). Two old core tests that did so were changed to
  paths the shell can take (list, pane, another role).
- The card editor is typing: the mode row says TYPING. A paste while it
  is open is refused with a note ("a card's editor takes no paste: type
  the text"): a card's text is the operator's own words, and a paste
  could carry anyone's in as such. Esc and ctrl+c in the editor still
  cancel it; the property counts them as deliberate discards, as ctrl+c
  clearing an idle input already was.
- The core keeps `PaneKey{PaneToggle}` takeable where Move is and from
  the input, for core callers and tests; the shell only ever sends Move.
- Status line: the mode is kept ahead of the other optional parts and
  shown while a request waits too, never cut. At 40 columns the newline
  hint, a retry's reason or "no output for" give way to it (two e2e
  tests now check those at 80 columns only). Only a line too narrow for
  the labels and the mode drops the mode; the labels win. A property
  test checks it. Codex's "TYPI" and TestNewlineKeys failing at baseline:
  both reproduce here with RUNEWIDTH_EASTASIAN=1, where the emulator
  counts `·` and the box characters as two cells; the renderer never cut
  the mode word. Not a relay-tui bug; the sandbox's locale.
- Tests: a property through the real model (hub and single) over random
  keys, macros that reach the editor, the questions, the help and the
  list, pastes, requests on and off screen, board changes and clock
  steps, checking (a) the routed surface equals the drawn one, (b)
  nothing typed answers, trusts, confirms or sends a card op but the
  editor's enter, (c) no request answered within ArmDelay of becoming
  the surface, (d) no draft or editor text lost but by sending or a
  deliberate clear, and that an event never takes the keys from a typing
  surface. A core property runs every operator event at random through
  the reducers with the same (b), (c) and the event rule; it fails if
  `takes` lets an answer through anywhere. Regression tests for
  findings 1 and 5. Reverting finding 1's fix fails the model property
  and the regression test.
- Also fixed: `TestRestoredDraftIsNeverMoreTrusted` failed on a seed
  where two pastes had the same words; it now compares by words.
  `TestNoKeyAnswersARequestWhileTypingOrUnarmed` counted a cancel (esc,
  any time) as an answer and tracked arming by the old rule.
- Not fixed, for the main session: `fakehub.TestSecondPromptWhileATurnRunsIsRefused`
  fails now and then under -race (it sleeps 200 ms for a turn to start).
  It predates this work.

2026-10-24, main session's follow-up (relay-tui lvpwunqp): the fakehub
flake was not the 200 ms sleep. The ACP SDK cancels a session's running
prompt context when another prompt for it arrives, so the fake's slow
turn could end on that just before the second prompt checked whether
the conversation was busy. The slow turn now ends on session/cancel, a
finish, or the connection ending, as relay's hub refuses the second
prompt; the test waits on a channel the fake signals once the first
prompt has the conversation. 50 runs under -race passed. Key replay
timing changes are in relay#74's Log.

2026-10-24, Codex round 3 over sxtryuoz..qkntqrtl: two HIGH defects,
each fixed as a rule in the core with property tests.

- Authorization needs a live key after the surface was drawn (relay-tui
  qpkkqkyv, core/authority.go). A replayed esc then y could approve a
  request that arrived before the roster, before any frame showed it;
  a held t then y could trust a card. The shell now sends `Drawn` a
  frame (50 ms) after the surface with the keys changes, naming it by
  `HubSurfaceID`/`SingleSurfaceID`; the core keeps it as `App.Shown` and
  forgets it the moment the surface changes. Every key's `KeyPressed`
  says whether it is replayed. Allow (y, or enter on an allow), archive
  y, card and doc trust y, the end question's y and ctrl+y are taken
  only from a live key ArmDelay after the surface they land on was
  drawn. Judgment call: no, cancel (esc) and the end question's n are
  safe, so they need a live key only, no delay; a replayed key answers
  nothing at all, safe or not, since the operator never saw the
  question. Card ops (tick, edit's enter) and sending a message are not
  authorizations here: replayed, they act, as typing does; they are the
  operator's own words and recoverable. The archive and end questions
  gain the delay they lacked; ctrl+y needs the input drawn 600 ms
  first (pty tests now wait). A refused key says why on the status line
  ("too soon…", "a key typed while connecting answers nothing…"). The
  renderer arms the request box and the trust and archive hints from
  the same drawn time (`RequestArmed`, `ConfirmArmed`). Core tests'
  helpers draw after every event, as a renderer would; render goldens
  use states drawn long ago, so none changed. Properties: in the core,
  operator events after live or replayed keys, agent events and Drawn
  at random, no authorization but by a live key ArmDelay after its
  surface was drawn (fails if the gate is removed); through the model,
  random held keys over a restored view with a request and a board
  arriving before the roster authorize nothing; Codex's two cases as a
  test.
- Only the operator moves the keys out of typing (relay-tui qtzrpukq,
  core/keeptyping.go). After any event that is not the operator's, if a
  typing surface became one that is not, the column, the mode and the
  board's editor with its text are put back. An editor whose card has
  gone stays open with "card N left the board · enter does nothing · esc
  to discard", enter sends nothing, esc discards. An input whose role
  was removed keeps its focus and text, says "no role X any more: what
  you typed stays · esc to move", and a Submit there sends nothing and
  gives the text back. Judgment call: before the operator's first key
  (`App.Pressed`) the start may still place the keys (the saved view,
  --open, the first roster), since nothing has been typed; with input
  held until the roster, replayed keys come after that. The core
  property now also runs cards changed and archived, the board resent,
  roles removed and back, conversations ended, failed attaches, turns
  ending and requests settled; none may change a typing surface, and it
  fails without the rule. The model property archives cards too.

2026-10-24, Codex round 4 (sxtryuoz..qtzrpukq): three structural gaps,
each closed with a rule and a property that fails without it.

- A question's identity is the version of what it shows (relay-tui
  zqplpotu). An armed archive question kept archive/<card> while another
  device changed the card, and y archived it at once. Identities are now
  (kind, object, version): request id; card id and rev for archive, plus
  digest for trust; doc path, version and digest; the end question's
  session. A change is a new identity, drawn and armed afresh, and the
  question whose object changed is cancelled with a note ("The card
  changed, so it was not archived…", "The doc changed, so nothing was
  trusted…"). The archive op also carries the rev shown (Seen), so relay
  checks it is a rev it had (it applies staleness only to text edits, so
  the client's cancelling is what protects; relay could apply it to
  archive too). Property: questions opened, drawn,
  objects changed and y pressed at random; a yes only goes out for the
  object as drawn ArmDelay before, with its rev.
- Arm only what the renderer drew in full (relay-tui nwrvpmro). Drawn
  came from the surface enum, so a trust or archive question clipped
  away at 60x10 (a tall draft and a waiting request left the pane no
  room) could be confirmed, and a request behind "Window too small" kept
  its drawn time. Frames now return Armable: the identities they drew in
  full and readable (the request box with its options; a question with
  its subject and yes and, for a trust, the whole card or doc; the end
  question; the input). The shell's Drawn carries exactly that set:
  what a frame no longer shows is forgotten at once (OnlyForget), what
  it newly shows is armed a frame later, and a report overtaken by a
  later frame is dropped. A y on something not shown in full says "not
  all of it is on screen: make the window larger…". Judgment call: a
  trust needs its whole card or doc on screen, so a doc too long for the
  window cannot be trusted there; widen it or use relay trust. The
  shell now renders the frame once per update to know the set (the ui
  tests take about 30% longer). Property: sizes, draft heights,
  requests, questions, waits and resizes at random in both modes; an
  authorization only happens for what the screen itself (read as text,
  not the renderer's own report) showed in full for the whole delay. It
  fails if the pane reports a clipped question or a too-small frame its
  request.
