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
