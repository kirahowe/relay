# Switch between roles and their conversations in acp-tui

Part of #62. Needs #64 for live switching. The sidebar can start against
#63's `_relay/roles` in today's `relay acp`.

## Context

acp-tui holds one agent process with one session (`internal/agent`), and
its core `State` is one conversation's worth. Chat is printed into the
terminal's scrollback, which cannot be redrawn, so switching conversations
needs a full-screen chat view. The transcript view (ctrl+t) already draws a
conversation on the alt screen and is the place to start.

## Acceptance criteria

- [ ] A sidebar lists roles with their status from `_relay/roles`. A role
  with an unseen reply or a pending approval stands out.
- [ ] Choosing a role opens its conversation, or starts one. Keys to move
  between conversations are taken from the free ones (ctrl+n/p, alt+digits).
- [ ] Core state becomes a top-level state holding the sidebar and one
  conversation State per session; every event carries its session id. The
  core stays pure, with property tests for switching.
- [ ] Permission requests from a conversation in the background show in the
  sidebar and can be answered after switching to it.
- [ ] The workspace pane and the board keep working.
- [ ] Works at narrow widths: the sidebar collapses into a list screen, the
  way a phone client would show it.

## Log

## Judgment calls

- **Hub mode is a separate model path, not a generalisation of single mode.**
  `core.Hub` sits above `Reduce`; single-session acp-tui keeps its inline
  scrollback view and `core.State` as before. Reason: the brief says
  single-session mode must stay unchanged, and the inline view's scrollback
  machinery cannot be redrawn, which hub mode needs.
- **A conversation is a `Shared` State.** It takes agent updates in any
  phase. Reason: the replay has no end marker, so replayed and live updates
  cannot be told apart. Entries are what the updates said, in order, so
  replay then live equals the full transcript (property test).
- **Events carry their session as `core.Routed{Session, Event}`**, a wrapper
  added by the agent package, rather than a session field on each event.
  Permission answer ids stay global (the shell's counter), so answers need no
  session.
- **`user_message_chunk` becomes `core.UserChunk` in both modes**, shown as
  a user box. Its URLs are defanged when the session is contaminated, since
  the text came down the agent's channel.
- **Focus is a role id, not a session id.** A role's conversation can end
  and be replaced. Moving to a role with a live conversation attaches
  (session/load); moving to one without does nothing. Typing a message, or
  enter on an empty input, starts one (session/new with `relay/role`).
  `--open` always opens or attaches. Reason: arrow-keying through the roles
  must not spawn an agent process per role.
- **Starts on the list without `--open`, in both layouts.** Core does not
  know the terminal width, and a phone-like start at the list is the stated
  narrow behaviour. In a wide terminal that costs one enter.
- **Keys:** ctrl+n/ctrl+p next and previous role, alt+1..9 role by position,
  ctrl+g list of roles, ctrl+l previous role (alt-tab style), ctrl+s end the
  conversation (asks y/n in the view). Hub keys act even while a permission
  request waits, so a background request never traps the operator.
  pgup/pgdown and the wheel scroll. These override the textarea's own
  ctrl+n/p/l.
- **Focusing a conversation re-arms the 600 ms permission delay**
  (`core.Focused`). Reason: the switching key and the next key typed are
  close together, and a `y` meant for the input must not approve.
- **Sidebar marks:** `▶` turn running, `…` queued, `~` scheduled work runs,
  `●` unread, `?N` approvals in reverse video, `!N` holds. Approvals shows
  the larger of relay's count and the requests this client holds.
- **Ended conversations are detected by the roster.** A conversation the
  roster listed and then stops listing is dropped. One the roster never
  listed is kept, so a stale roster that arrives after an attach cannot drop
  a new conversation. Sessions this client ended, or failed to load, are
  remembered and never re-attached from a roster that still lists them.
- **Ctrl-C** cancels a turn another device started (roster `running` or
  `queued` while the conversation is locally idle) by sending
  `session/cancel`; it quits only when nothing is running. Ctrl-D and
  quitting leave running conversations alone (they continue under the
  service; in embedded mode the process ending ends them).
- **`_relay/seen`** goes out when the conversation is attached, on screen
  (the list is not covering it), scrolled to the end, the roster says it is
  unread and idle, and `turns` is above what was already sent. `turn` sent
  is the roster's `turns`.
- **Drafts** (unsent input) live in the shell, one per role, because the
  textarea is shell state. Queued text that a failed attach or a cancelled
  turn gives back for a conversation not on screen becomes a faint "not
  sent" note in that conversation, so it is never lost.
- **Workspace pane in hub mode covers the conversation** (ctrl+x), never
  stands beside it. The sidebar already takes the width.
- **`_relay/roles/changed`, `_relay/permission/settled` and
  `_relay/cards/changed`** arrive through the SDK's extension handler.
  `cards/changed` has no session id in today's shape, so it goes to every
  attached conversation's board.
- **Settled** releases the handler waiting on our request by answering it
  cancelled. relay drops that late answer, per section 4.
- Tests: the fake hub (`cmd/fakehub`, `internal/fakehub`) plays one client;
  a second device is a control file the test appends commands to.

## Protocol questions

All answered in section 4 of the brief (2026-10-21) and now implemented.

- **Replay end: resolved.** relay sends `_relay/replayed {sessionId}` after
  every replay. acp-tui reads it in wire (`Replayed`) and marks the
  conversation loaded (`Conv.Loaded`). It stops the tool calls still open at
  the marker only when the roster is loaded and lists no running or queued
  turn for the conversation. Attaching in the middle of a turn started on
  another device leaves them open, because they are running and their
  updates arrive live. The roster going idle later closes leftovers (the
  earlier fallback, kept). An open reply, thought or tool run is never
  finalized at the marker, because the next chunk joins it. Tests: a replay
  cut anywhere, then the marker, then live updates equals the whole
  transcript; a mid-turn attach, marker and live completion ends completed
  with no duplicate; a late update for a stopped call adds no entry.
- **Replay order after `session/new`: resolved.** The answer, then the
  replay, then the marker; after `session/load` the replay, the marker, then
  the answer. acp-tui still accepts any order, since it keys on session id.
- **`_relay/seen`: resolved.** It is a request and relay answers `{}`;
  acp-tui sends it as one and ignores the answer.
- **`turn` in `_relay/seen`:** unchanged (the roster's `turns`, only while
  idle); not raised again in the brief.
- **Late answer after `settled`: resolved.** relay drops it without an
  error, which is what acp-tui relies on to release its handler.
- **`_relay/cards/changed` routing:** still sent to every attached
  conversation, as it carries no session id.
- **`turn: "queued"` with the client's own prompt outstanding:** unchanged.
- **Roster `labels`:** unchanged.

## Not done

- A `session/list` call: the roster already lists live sessions, so the
  client does not use it.
- The pane standing beside the conversation in hub mode.
- Per-conversation drafts surviving a restart.

## Hub view parity with the inline view

- **Workspace pane beside the conversation (supersedes the earlier note
  that it always covers it).** In hub mode the pane now stands beside the
  conversation when the area left of the sidebar is 119 columns or wider
  (a 160-column screen), as in the single-session view, so a display with
  its `untrusted` mark is in front of the operator without ctrl+x. Narrower,
  ctrl+x covers the conversation as before. Found by relay's
  `the-pane-in-relay-chat-shows-a-display-and-trusts-it`, which now passes.
- The conversation uses the inline view's own per-entry renderer
  (`entriesAs`), so entries cannot drift. A test holds one of every entry
  kind and checks every line the inline view prints at the same width also
  appears in the hub view, and that the session's labels and defanged URLs
  show.

## Codex round 1

All fixes are in `lxsoqzvqvoqu` (acp-tui workspace `acp-tui-convs`). Tests are
in `internal/core/hub_test.go` (core), `internal/render/hub_test.go`
(render) and `internal/ui/hub_e2e_test.go` (end to end).

1. **Uncovering re-arms the permission delay.** Structural: `ReduceHub`
   compares what is on screen (`Hub.onScreen`: the focused conversation, not
   behind the list, help or end question) before and after every event, and
   `settle` re-arms a request that has come into view. Test
   `TestHubEveryUncoveringArmsRequestsAgain` (list, end question, help; `y`
   1 ms after uncovering answers nothing, 700 ms later it does).
2. **Trust confirmation on blur.** `settle` sends `core.Blurred` to the
   conversation that left the screen, which drops an unsent `Pane.Trust`
   (one already sent stays). Test `TestHubTrustConfirmationDoesNotSurviveLeavingTheScreen`
   (switch, list, help, end question).
3. **Role titles.** Sidebar, list rows, headers, the end question and the
   empty-conversation line go through `roleName` (`EscapeLabel`, then defang
   when the role is contaminated); the role's labels show as `p`/`c` marks,
   in words on the list screen and as badges in the header. A role named only
   on the command line is escaped and defanged. Test
   `TestHubRoleTitlesFollowTheirLabels`.
4. **Replay labels.** No code change needed: a labels update at the start of
   a replay applies before the entries after it. Test
   `TestHubReplayLabelsApplyBeforeTheEntriesThatFollow`.
5. **Loaded-from-roster is listed.** `ensureAttached` and `attached` set
   `InRoster` when the roster lists the session. Test
   `TestHubLoadedFromTheRosterCountsAsListed`.
6. **End question holds its session.** `Hub.EndSession`; `settle` drops the
   question if the focused conversation is another, and `ConfirmEnd` checks
   it again. Test `TestHubEndQuestionIsAboutOneSession`.
7. **Pastes.** `pasted` no longer refuses a Shared conversation for having
   its transcript open; pastes under the help or the end question go
   nowhere. Tests `TestHubPastesWorkInAConversation` (bracketed, untrusted
   attachment, ctrl+y) and e2e `TestHubPasteBecomesAnUntrustedPlaceholder`.
8. **`_relay/seen` gating.** Requires `Conv.Loaded` (set by
   `_relay/replayed`, or by one of our own turns ending, since a fresh
   conversation has no replay), no help, end question or pane focus, and the
   end of the conversation. Test
   `TestHubSeenWaitsForEverythingThatCoversTheConversation` plus the updated
   once-only test.
9. **Text area width.** `render.HubInputWidth` gives the width of the box
   `HubView` draws, and the shell sets the text area to it after every event.
   Tests `TestHubInputWidthIsTheBoxDrawn` and e2e
   `TestHubLongDraftIsAllVisibleWithThePaneBeside` (160 columns; fails with
   the old width).
