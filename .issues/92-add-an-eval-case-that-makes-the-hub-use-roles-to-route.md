# Add an eval case that makes the hub use roles to route

## Context

relay#37 made `roles` show each role's purpose, the first paragraph of its
instructions. route-to-owner passes, but no trial called `roles`, because
v2 of the case lets the steward put the reminder on its own calendar. The
unit and public-role tests cover what `roles` returns. No live eval shows
that the hub reads the purpose and routes by it.

## Acceptance criteria

- [ ] A scenario in `test/scenarios/` where the job can only be done by
  another role, and the hub has to call `roles` to find which one.
- [ ] An oracle that passes every grader and a foil that fails the routing
  grader.
- [ ] Live runs on Haiku (3 trials) and Opus (1 trial), with the run ids
  recorded here and the case added to `test/scenarios/BASELINES.md`.

## Plan

Use the staging seed and made-up names. Give at least two roles titles that
do not say which of them owns the job, so that only the purpose text can
settle it.
