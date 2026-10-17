# Add a per-role trust ladder for research roles

## Context

Review of everything does not scale, so a role can earn lighter review, one level at a time.

## Acceptance criteria

- [ ] L0: review everything.
- [ ] L1: findings passing every check enter the ledger as trusted structured fields and free text stays withheld; flagged findings plus a random sample (1 in 5) are reviewed.
- [ ] Only Kira raises a role's level.
- [ ] A missing quote, a guard refusal, or a finding rejected as fake drops the role to L0 and notifies Kira.
- [ ] Property tests on the level transitions.

## Plan

## Log

- 2026-10-17: landed on main as zzlwrlon, with the docs in qwssvysx. At the tip: 494 tests and 9/9 scenarios green. Two Opus review rounds; round 2 had no in-scope defect. Its one non-blocking finding is relay#27.
