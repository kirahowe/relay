# Let the hub read the web through closed answers

## Context

Part of the personal-agent epic Kira started on 2026-10-18. Design
sections D8 of the epic design, which is kept with these issues as
.issues/personal-agent-design.md. Kira's principles: assume the model is
fully compromised; nothing acts in the real world for the operator without
explicit consent, given at the time or in advance; roles draft and the
operator reviews; answers cite sources; the operator never needs to know
relay's internals.

## Acceptance criteria

- [ ] webtool ask searches and fetches the top n result URLs (at most 5) under the fetch guards. No free URL goes in.
- [ ] The quarantined reader may read through an egress inner tool. The call is decided under the caller's labels.
- [ ] The example config and relay init grant the hub web under :quarantine.
- [ ] An eval shows a closed web answer leaving the hub clean. A pentest shows a hostile result page giving only a closed answer.
- [ ] relay#34 is closed as covered, with the reason.
- [ ] The Codex review loop has converged.

## Decisions

## Log
