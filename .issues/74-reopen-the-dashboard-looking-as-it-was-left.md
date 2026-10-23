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

- Closing and reopening acp-tui in hub mode against the same agent
  restores the items above.
- What is restored is only where the operator was; anything that has
  since gone (a role removed, a card archived, a conversation ended)
  falls back quietly to the default.
- The file is written atomically and a damaged or unreadable file means
  defaults, never a crash.
- No environment variable chooses the path.

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
