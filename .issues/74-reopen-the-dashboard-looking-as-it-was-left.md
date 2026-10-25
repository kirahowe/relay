# Reopen the dashboard looking as it was left

## Context

Kira, 2026-10-23: "I'd like things to be persistent across open/close, so
that if I re-open it it looks the same."

Kira's answers (2026-10-23):
- Keep focus and layout: the focused role, the list cursor, the pane open
  or closed, board or items, the selected card and whether its detail is
  open.
- Keep unsent drafts, pastes included.
- Not scroll positions.
- The view state lives in acp-tui's own state file, per device, keyed by
  the agent it opens. It is presentation, not relay data, so it stays out
  of relay's log, and the laptop and the phone may each look different.

## Acceptance criteria

- [x] Closing and reopening acp-tui in hub mode against the same agent
  restores the items above.
  - Receipt: core view derive/restore and internal/viewfile (zuzvkvup, zkqxrvnv, sumpsnop), restore order reworked by mvvuktxk; e2e reopen tests; QA 2026-10-24 reopened into the saved role with its draft; state file 0600, written atomically; --state-dir only, no env var.
- [x] What is restored is only where the operator was; anything that has
  since gone (a role removed, a card archived, a conversation ended)
  falls back quietly to the default.
  - Receipt: core view derive/restore and internal/viewfile (zuzvkvup, zkqxrvnv, sumpsnop), restore order reworked by mvvuktxk; e2e reopen tests; QA 2026-10-24 reopened into the saved role with its draft; state file 0600, written atomically; --state-dir only, no env var.
- [x] The file is written atomically and a damaged or unreadable file means
  defaults, never a crash.
  - Receipt: core view derive/restore and internal/viewfile (zuzvkvup, zkqxrvnv, sumpsnop), restore order reworked by mvvuktxk; e2e reopen tests; QA 2026-10-24 reopened into the saved role with its draft; state file 0600, written atomically; --state-dir only, no env var.
- [x] No environment variable chooses the path.
  - Receipt: core view derive/restore and internal/viewfile (zuzvkvup, zkqxrvnv, sumpsnop), restore order reworked by mvvuktxk; e2e reopen tests; QA 2026-10-24 reopened into the saved role with its draft; state file 0600, written atomically; --state-dir only, no env var.

## Log

2026-10-23, phase 3 (relay-tui):

- Shape: `core.View` holds the hub's part (focus, list cursor, whether
  the list has the keys, whether the pane was opened from the list), the
  app's part (pane has the keys, board or items, selected card, card
  open with its line and column) and the drafts (role, text with its
  placeholders, the pastes those placeholders name, next paste number).
  Single-agent mode saves the app's part and one draft with no role.
  "Pane open" is `App.PaneFocus`: on a wide screen the pane is always
  beside the conversation, so having the keys is what open means.
- Functional core: `SaveHub`/`SaveSingle` derive it; `RestoreHub`/
  `RestoreSingle` hand a loaded one to the reducers, which put it back
  part by part. Board-or-items is put back at once. The focus, cursor,
  list, pane and drafts wait for the first successful roster (against a
  single agent, for the session); the card waits for the board
  (`_relay/cards` answered). A roster that failed does not count.
  Whichever of roster and board comes first, the other part waits.
- Fallbacks: a role gone is not focused, the list gets the keys, the
  pane does not; its draft is dropped and the status line says "a saved
  draft could not be put back" (dropping typed text silently seemed
  worse than a note). A card gone leaves the default selection. A role
  whose conversation ended is focused and shows none; nothing is opened.
  A live one attaches, as picking it from the list does.
- A key pressed before a part is back drops that part (layout and card)
  but not the drafts: the operator has moved on, and should not see the
  screen jump. A restored draft goes ahead of anything typed since. If
  pastes were made meanwhile for that role, its saved draft with pastes
  is not put back (their numbers would clash) and the note says so.
- --open wins over the saved focus, list and pane; the card and drafts
  still come back.
- Closing before a part came back saves the loaded part, not the
  defaults shown meanwhile, so a quick open and close forgets nothing.
- Paste trust: in the file a trusted paste carries a seal, sha256 over
  its number and text; no seal, or one that does not match, means
  untrusted, with a note. The view's bytes carry a digest, so any damage
  in the file means the defaults. In the core, `restoredDraft` drops a
  paste with no number, no text, a shared number, or no placeholder in
  the draft, and makes a paste untrusted when either the paste or its
  placeholder says untrusted. Property tests: round trip exact; random
  byte damage gives defaults or the same view; pastes edited with the
  digest made again never gain trust; damaged drafts in the core never
  send as trusted text what was not before. Bytes that are not UTF-8
  are saved replaced (the JSON encoder needs UTF-8), so such a paste
  comes back untrusted rather than failing the save.
- Not saved: queued messages, permission answers, doc or card trust in
  flight, the archive confirm, the end question, the board's one-line
  input, the session's labels (relay sends those again).
- File: one per agent argv, `view-<sha256 of the argv's JSON, 32 hex>.json`
  under `~/.local/state/relay-tui/` or `--state-dir`, with the argv in
  it; a mismatch means defaults. Read with strictjson, capped at 8 MiB
  (a view too large is saved without its drafts, with a note). Written
  with a temp file and rename in the same directory, 0600, directory
  made 0700 when missing (an existing --state-dir is left as it is).
- The default dir comes from the user database (`os/user`), not
  `$HOME`, so no environment variable picks it.
- Writes: a background saver, at most every 500 ms while the view
  changes, and once on exit; the UI only hands it the view. Encoding
  happens in the saver too.
- Notes about the file go to the status line at start and to the agent
  log (the stderr tail); none quotes the file.
- Single-agent mode: ctrl+c on a non-empty input clears it, and ctrl+d
  quits only from an empty one, so a clean quit there never keeps a
  draft; a kill or a closed terminal does (debounced save). Hub mode
  keeps per-role drafts across a quit from the list.
- relay needs a change (not made): two relay tests run the real
  relay-tui (`relay.acp-test/chat-with-the-real-relay-tui-on-a-pty`,
  `relay.display-test/the-pane-in-relay-chat-shows-a-display-and-trusts-it`)
  with no --state-dir, so they would write Kira's
  ~/.local/state/relay-tui. Their client vector should add
  `"--state-dir" (str *dir* "/relay-tui-state")`. With that change made
  in a scratch copy of relay, both pass.
- Verified live: scratch data dir, roles gardener and writer (agent
  /usr/bin/false), two cards via `relay card add`; `relay-tui
  --state-dir <tmp> -- relay acp --approvals --data <dir> --run-dir
  <dir>` in a pty at 170x40. Run 1: alt+2, a draft with two pastes,
  ctrl+y on the second, ctrl+x, open the first card, j, ctrl+g, ctrl+c.
  The file was 0600 and held the view, with a seal on the trusted paste
  only. Run 2: the list over Writer, the draft with "untrusted #1" and
  "trusted #2", esc to the pane with the card open. Run 3, after `relay
  remove writer`: the note, the list, ctrl+x to the board with no role
  in focus, j, x; `relay cards` showed the item ticked.

2026-10-23, main session: relay's tests and pentest that run the real
relay-tui now pass it `--state-dir` under their temp directory, so no
test writes the operator's `~/.local/state/relay-tui`. Phase 3's agent
found the gap and left the relay change to the main session.

2026-10-23, Codex round 1 over phase 3 (relay-tui sxtryuoz..ovnlqpmw):
three in-scope defects in the restore, each fixed in its own relay-tui
commit with a regression test that failed before the fix.

- A letter the input box takes for itself did not count as a key, so
  typing while the agent was slow to start left the saved pane and card
  pending; the roster and board then gave the pane the keys and the next
  `x` ticked a checklist item. The shell now sends `core.KeyPressed`
  ahead of every key in both modes, and the restore counts it (relay-tui
  pvourwxz).
- A draft typed before the restore came back replaced the waiting one in
  the next save, so closing in that window lost the saved text and
  pastes silently. The save now merges them, saved draft ahead, as the
  restore does. Judgment call: the restore used to drop a saved draft
  with pastes when pastes had been made meanwhile (numbers could clash).
  It now renumbers the saved pastes to follow the new ones, placeholders
  with them and trust unchanged, and the save does the same, so nothing
  is dropped for that reason any more. A property test checks that every
  paste of both drafts goes out as it would have and no number repeats
  (relay-tui konpyrwm).
- The start (`RestoreSingle`, used before the client knows the agent is
  the hub) held the layout only when the pane had the keys, so a view of
  just a focused role was dropped before `HubConnected`. It now holds the
  layout while the view has a hub part (relay-tui xzmzuvky).

2026-10-23, Codex round 1 over phase 4 (relay-tui ovnlqpmw..krxzsrto),
the two restore defects, each with a test that failed before the fix:

- Ctrl-Y trusted the wrong paste after a restore (relay-tui smzxykwz).
  Restore puts the saved text ahead of what was typed since but appends
  its pastes to the list, and Ctrl-Y scanned the list backwards, so after
  a restart and a paste of your own it trusted the old email. The main
  session decided: Ctrl-Y trusts only a paste made in this run. Each
  paste now carries `Seq`, when it was pasted in this run (a counter on
  the app, carried from single to hub mode, and also kept above every
  paste a conversation holds so a bare `Reduce` stays ordered). Ctrl-Y
  takes the highest; a restored paste has Seq 0, keeps the trust it was
  saved with, and is never trusted by Ctrl-Y ("a paste from before
  cannot be trusted: paste it again"). Seq is never saved. A property
  test runs pastes, typing, the roster, save and reopen, and Ctrl-Y in
  any order.
- Typing and pastes before the hub was known were dropped (relay-tui
  rlrunsrz). The input now goes to the hub as a draft with no role yet
  (`Hub.Loose`), its pastes as trusted as they were and still this run's.
  The first role in focus takes it, after what its input holds
  (`RestoreDraft.After`): with --open that is at once, so a saved draft
  still goes ahead of it and later typing after it; with a saved focus it
  lands right after that role's saved draft. A save before any role is in
  focus keeps it as a draft with no role, and a reopen holds it the same
  way.
- Test fix (relay-tui uyutxmsm): `TestRestoredDraftIsNeverMoreTrusted`
  failed on a random seed because its damage changed a paste's number or
  text and kept its trust, which the file's seal cannot do. The damage
  now drops the trust. No change to the restore.

2026-10-24, holding input until the agent's kind is known (relay-tui
vrumumwo, lrkzokvt), after Codex round 2 found three more lost drafts
in the move from single-agent state to the hub's.

- The shell handles no input until initialize answers. It holds keys
  and pastes in order with their times (`ui/connect.go`) and draws
  "connecting…" with how many wait. `HubConnected`, or the new
  `core.Initialized` that the agent emits for a plain agent right after
  initialize (before session/new, so a slow session does not hold
  typing back), says the kind; `Connected` counts too, for callers that
  skip it. The shell then builds that kind's state, runs RestoreHub or
  RestoreSingle on the saved view, and replays the terminal size, the
  event and the held input through the normal Update, each key at the
  time it was pressed, so every guard applies (a test runs held y and
  enter before a request).
- Judgment calls: at most 4096 keys are held; more are dropped and the
  status line says how many. Ctrl-C is the one key handled while
  connecting: it quits, saving nothing, so an agent that never answers
  cannot trap the operator.
- If the agent exits before it answers, nothing was handled and the
  saved view file is left byte for byte (`Saver.Stop`; `Model.Known`).
  A pty test checks it (finding 2).
- Enter before the hub is known is replayed after it: with --open it
  queues in the role's conversation and goes when the attach is
  answered (finding 3, test).
- Finding 4 was a move inside hub mode too: a paste into a conversation
  waiting for its attach, then relay's replay making the conversation
  first. `attached` now folds the waiting conversation's queue, pastes
  and paste counter into the replay's (`adoptWaiting`), a failed attach
  leaves the pastes in the role's blank, and `adoptBlanks` merges rather
  than dropping a blank's pastes when the conversation has some. A
  property runs pastes, submits, the replay and the attach in any order
  and checks every paste goes out and no number repeats.
- Deleted: `HubFromSingle` and `hubRestore`, `Hub.Loose`,
  `placeLoose`, `RestoreDraft.After` and the shell's `placed`,
  `RestoreSingle` holding a hub's layout, `carry`'s Loose exception and
  `SaveHub`'s Loose draft, and their tests. The paste counter no longer
  needs carrying from one state to the other.
- A view saved against the other kind of agent puts back what it can; a
  draft with nowhere to go (a role's against a plain agent, one with no
  role against the hub) is not put back, with the "could not be put
  back" note.
- Unsure, for the main session: with no --open, the hub starts on the
  list, so keys typed while connecting replay as the list's keys, as if
  typed just after, and the first one cancels the saved layout (the
  pvourwxz rule). Typing a message straight after reopening therefore
  lands as list keys ("i to type") and the saved role does not come back
  into focus; the saved drafts do. That follows the brief, but holding
  until the roster (when the layout is resolved) may be what Kira wants.
- Cosmetic: the "connecting…" line is drawn inline before the hub's
  alternate screen, so it stays in the terminal after quitting from the
  hub.
- Verified live: scratch data dir (notify none), roles gardener and
  writer with agentloop's bin /usr/bin/false, a checklist via `relay
  card add`; `relay-tui --state-dir <tmp> [--open writer] -- relay acp
  --approvals --data <dir> --run-dir <dir>` in a private tmux at 170x40
  and 100x30. Keys sent at once showed "connecting… · 9 typed, kept for
  when it answers"; with --open writer they landed in Writer's input
  after the saved draft; without it they acted on the list and the
  saved draft came back under Writer. Walked esc, l to the board, enter,
  j, e: the editor showed TYPING and took "ab"; esc, h, ? showed the
  moving keys and x closed them without ticking.

2026-10-24, main session's follow-ups (relay-tui mvvuktxk, qkntqrtl):

- Kira's decision on the question above: input is held until the screen
  it lands on is known, not just until initialize. Against the hub that
  is the first roster answer, by which time the saved layout has been
  put back (or found nothing to put back); against a plain agent, its
  session (Connected), so `core.Initialized` is gone again. Keys typed
  while connecting then replay onto the restored screen and do not
  cancel the restore; a key after the roster and before the board still
  cancels only the card, as before. A roster that fails releases the
  keys onto the default screen, with "no roster: …; what you typed went
  to the list". The bound, ctrl+c quitting while connecting, and a
  failed start saving nothing are kept; the view counts as known (and
  is saved) only once the input has been released.
- Consequence, by design: a replayed key types on where a restored
  draft ends ("old draftn"), as a key typed just then would, where the
  old merge put a newline between them.
- The "connecting…" line is drawn on the alternate screen, so nothing
  is left in the terminal after quitting from the hub. A pty test runs
  a failed start, a plain agent and the fake hub; it failed before. The
  pty harness had a hang of its own, now fixed: the goroutine copying
  the emulator's answers stopped when the pty closed, and the emulator
  then blocked writing an answer with the screen lock held.
- Verified live as before: keys sent at once showed "connecting… · 6
  typed"; without --open the saved focus (Writer) came back and the keys
  typed on after its saved draft; quitting left only relay-tui's exit
  line.

2026-10-24, Codex round 3: replayed input now types and moves only; it
authorizes nothing, not even a refusal (relay-tui qpkkqkyv, see
relay#77's Log for the rule). A held ctrl+y no longer trusts the paste
it follows; the operator presses it again once the input is on screen.

2026-10-24, Codex round 4, finding C (relay-tui kvnyrysn): a role owns
its draft. Pastes lived in the role's conversation (or a "blank" before
it had one) and were carried between them; ending the conversation on
another device dropped them and the paste counter while the input kept
the placeholders, so the next paste reused #1. Now Hub.Drafts holds one
Draft per role (text, pastes, counter, this run's paste order) and
Single.Draft one, apart from any conversation. The textarea is a view
of the focused role's draft; its text reaches the core through one path
(SetDraftText), and every text effect names its role (the shell applies
it to the box when it shows that role, else to the core draft).
Messages take their pastes when sent or queued (Queued{Text, Pastes})
and give them back with the text when not sent (failed turn, failed
attach, conversation ended, exited), to that role's draft even when it
is not in focus (it used to become a "not sent" note). Removed: Blanks,
adoptBlanks, adoptWaiting, State.Pastes/NextPaste; the replay-first
attach now merges only the queue, as the two are one conversation.
SaveHub/SaveSingle read the core's drafts. Property: random pastes,
ctrl+y, typing, sends, role switches, attaches answered/failed/overtaken
by a replay, conversations ended here or elsewhere, roles removed and
back, turns ending; every placeholder names the paste it was given for
with its trust, and no number repeats. Fails if ending a conversation
takes the draft's pastes. Codex's case is a test.

- 2026-10-25: Kira accepted it while clearing the review backlog. The
  work is on main in every repo it touched, pushed and deployed.
