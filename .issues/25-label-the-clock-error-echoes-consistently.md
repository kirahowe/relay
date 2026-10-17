# Label the clock error echoes consistently

## Context

From the clock review (2026-10-17). The `as_of` error and the "You have no tool called X" message echo a session's own input back with labels []. This is harmless, since the text came from that session, but the labels are inconsistent with how other echoes are labelled.

## Acceptance criteria

- [ ] Both echoes carry the labels of the session that sent the input, or the code says why [] is right.

## Plan

## Log
