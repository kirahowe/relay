# Show findings again when their hidden match is rejected as fake

## Context

Found in round 2 of the report pipeline review (2026-10-17) and confirmed with a probe. It is in scope but does not block: nothing leaks and the log keeps every finding.

Report A cites URL X, and its finding waits for review. Report B cites the same X and is hidden as seen before (`:seen-as {:kind "pending" :report A :finding i}`). The operator then rejects A's finding as fake. B's finding is never shown. `pending-findings` in core.clj treats `:seen-before` as permanent, so `reports-waiting` never lists B again. A report checked after the rejection is shown with the `rejected-as-fake-before` flag, as the README promises, but B was checked before it.

So a decoy can bury a later genuine finding. That is a generic injection under the threat model.

The reviewer's fix: when `:report/review` records a "fake" decision, emit an event that un-hides every finding whose `:seen-as` points at that report and finding and gives it the `rejected-as-fake-before` flag. Withdrawal of an entry likely needs the same. The digest then covers the new state. Check first whether working out visibility at read time is simpler and keeps the digest sound.

## Acceptance criteria

- [x] Regression test: A pending on X, B on X hidden, A rejected as fake. B's finding is then pending and flagged `rejected-as-fake-before`.
- [x] The same for a withdrawn entry, or a note on why it differs.
- [x] The review digest covers the new flag.

## Plan

Coordinate with relay-23 first. Its cost work touches core.clj.

## Log

2026-10-17: Fixed by working out visibility at read time (option a),
not with an event on a fake decision (option b).

`core/report` now returns a report as it stands: a finding hidden as
seen before is shown again, with the `rejected-as-fake-before` flag,
once the finding it matched is decided fake. The stored verification
is unchanged. Reasons for (a): it needs no new event type and no
fan-out in `:report/review`, and it also brings back findings already
buried in existing data, which an event would only do for decisions
made after it shipped. The digest stays sound because the review screen
and the digest are both computed from that same view. The digest now
covers the flags as well as the fields
(`reports/finding-digest-text`), so a decision made before the flag
appeared is refused.

Withdrawal: an entry relay accepted unread is withdrawn when a sibling
is rejected as fake, and its finding waits for review again. A finding
hidden behind that entry stays hidden, now behind the finding that made
it (relay keeps a `:withdrawn` index of entry to finding for this), and
is shown, flagged, if that finding is then rejected as fake. Showing it
at withdrawal would put the same URL in front of the operator twice.

`known-findings` uses the same view, so a later report on the URL hides
behind the shown-again finding while it waits. `relay review` says
which findings a fake decision brought back. Tests: regression tests in
reports_test, a CLI test, and an invariant in the model-based property
test (a hidden finding is always backed by a shown, non-fake finding
with its URL). The invariant fails on the old code with a five-step
counterexample (report, verify, report, verify, review fake).
