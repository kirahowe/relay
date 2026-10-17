# Show findings again when their hidden match is rejected as fake

## Context

Found in round 2 of the report pipeline review (2026-10-17) and confirmed with a probe. It is in scope but does not block: nothing leaks and the log keeps every finding.

Report A cites URL X, and its finding waits for review. Report B cites the same X and is hidden as seen before (`:seen-as {:kind "pending" :report A :finding i}`). The operator then rejects A's finding as fake. B's finding is never shown. `pending-findings` in core.clj treats `:seen-before` as permanent, so `reports-waiting` never lists B again. A report checked after the rejection is shown with the `rejected-as-fake-before` flag, as the README promises, but B was checked before it.

So a decoy can bury a later genuine finding. That is a generic injection under the threat model.

The reviewer's fix: when `:report/review` records a "fake" decision, emit an event that un-hides every finding whose `:seen-as` points at that report and finding and gives it the `rejected-as-fake-before` flag. Withdrawal of an entry likely needs the same. The digest then covers the new state. Check first whether working out visibility at read time is simpler and keeps the digest sound.

## Acceptance criteria

- [ ] Regression test: A pending on X, B on X hidden, A rejected as fake. B's finding is then pending and flagged `rejected-as-fake-before`.
- [ ] The same for a withdrawn entry, or a note on why it differs.
- [ ] The review digest covers the new flag.

## Plan

Coordinate with relay-23 first. Its cost work touches core.clj.

## Log
