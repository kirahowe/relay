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
