# Write the cut-short journal entry even when someone else wrote in the role's journal during the wake

## Context

Found by the adversarial review of the wake context work (lkrxorxo).
core.clj `journal-cut-short` decides the role "wrote no entry" by
looking for any journal entry with an id after the wake's start. If the
operator, or a relay entry, writes to that role's journal while the wake
runs, and the wake is then cut short without the role writing anything,
no cut-short entry is written. The next wake is then not told the last
one was cut short.

## Acceptance criteria

- [ ] A cut-short wake gets relay's entry unless the role itself wrote
      one during that wake.
- [ ] A test with an operator journal write during the wake.

## Plan

Count only entries the role itself wrote in that wake. That needs the
writer (event actor) kept on journal entries, or a check of the log.

## Log
- Judgment call: kept the event actor on journal entries (`:actor` in the evolved entry) rather than checking the log, so the decision stays a pure function of state (`role-wrote-in-wake?`). The role's own id is the actor when it writes through the journal tool. Reason: the log check would put IO in the core.
- Test: an operator journal write during the wake no longer suppresses relay's entry; confirmed it fails with the old rule.
