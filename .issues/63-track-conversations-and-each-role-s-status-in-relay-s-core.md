# Track conversations and each role's status in relay's core

Part of #62. Read its decisions first.

## Context

Every client needs the same two lists, built by relay rather than worked
out by each client:

- Roles: the active roles with their titles (`core/active-role-ids` and
  `render/roles` already make this for `relay roles`) and a status for
  each: idle, working, waiting on Kira's approval, or has a reply Kira has
  not seen.
- Conversations: one per role at most, with an id, role, title, status,
  start time, last activity and the ACP sessions it holds.

No ACP method exposes roles today. The ACP agent is fixed to one role.

## Acceptance criteria

- [ ] The log records a conversation's lifetime (opened, ended), which turns
  run in it, and when Kira last saw it. Decisions are pure (decide*/evolve*);
  writes go through relay.inbound.
- [ ] A conversation holds its role only while a turn runs (the #62
  decision). The dispatcher can wake a role whose conversation is idle; a
  turn cannot start while a wake of that role runs. spec/relay.allium
  OpenConversation and RunWake say so.
- [ ] A pure function gives each role's status, and one gives the
  conversation list. Property tests cover the state machine.
- [ ] The wire forms of both lists are viewer-agnostic data, like the cards
  wire form, and carry their labels.
- [ ] `_relay/roles` and `_relay/conversations` work in today's `relay acp`,
  with `.../changed` notifications.
- [ ] README, resources/AGENTS.md and the spec updated. bb test passes.

## Open questions

- Is a conversation still one wake record with `:chat true`, or a new
  entity whose turns are short wakes? The answer decides how cost, sessions
  and lost-wake reaping are recorded. Settle it at the start of the work and
  write the reason here.
- Conversation titles: agentloop sends no `session_info_update` title.
  Either relay derives one from the first prompt, or agentloop asks the
  model for one. A model-made title is text from a turn and needs labels.

## Log
