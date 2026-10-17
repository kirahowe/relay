# Give conversations the time, and add a clock tool

## Context

Chats (relay acp/chat) get only the system prompt, so they never learn the time. Dispatched wakes already get "It is ...". A pure `clock` tool lets a role also work out dates without guessing.

## Acceptance criteria

- [ ] The chat system prompt names the start time and the operator's time zone.
- [ ] A `clock` relay tool, class read, no labels, returns now in `:timezone` and the weekday.
- [ ] Pure date arithmetic: add a span, days between two dates, next weekday.
- [ ] Property tests for the arithmetic, including DST transitions in America/Halifax.
- [ ] README, resources/AGENTS.md and spec/relay.allium updated where they describe tools.

## Plan

## Log

- 2026-10-17: landed on main (qqnvmnvp, vymtlzvw): the clock tool and the time a conversation started. Decision for Kira to confirm: the time zone is not private.
