# Label the clock error echoes consistently

## Context

From the clock review (2026-10-17). The `as_of` error and the "You have no tool called X" message echo a session's own input back with labels []. This is harmless, since the text came from that session, but the labels are inconsistent with how other echoes are labelled.

## Acceptance criteria

- [x] Both echoes carry the labels of the session that sent the input, or the code says why [] is right.

## Plan

## Log

- 2026-10-17: The clock tool itself never echoes input: its errors are fixed text with labels [] (relay.clock/fail, tested). The `as_of` echo is core/world-time's refusal, reached from `facts` (`as_of`, `known_at`) and `fact_add` (`valid_from`, `valid_to`).
- 2026-10-17: Chose to label rather than to stop echoing. Quoting the bad value helps the role fix its call, and the text is the session's own, so its labels are exact. A blanket rule (every error carries the session's labels) was rejected: the clock's fixed errors are deliberately unlabelled and tested as such.
- 2026-10-17: Mechanism: core/reject-quoting-call! marks a refusal with :relay/quotes-call, and tools/run-tool adds the calling session's labels to such a refusal. "You have no tool called X" in tools/call! carries the session's labels directly.
- 2026-10-17: Applied the same marker to the other refusals that quote a role's input with labels []: a fact topic, a routine schedule, and doc_get/doc_history's "There is no doc at <path>". Fixed-text refusals keep [].
- 2026-10-17: Test: relay.clock-test/an-error-that-quotes-the-call-carries-the-session-s-labels. spec ReadsCarryTheLabelsOfWhatTheyShow says so.
