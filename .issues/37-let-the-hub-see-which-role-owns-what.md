# Let the hub see which role owns what

## Context

The route-to-owner eval case failed every live trial on both models
(Haiku run 20261018T143129Z-4550, 0/3; Opus run 20261018T142542Z-f30f,
0/1). Asked to remind the operator of something, the hub set a once
routine to do it itself and never handed the job to the clerk, which owns
reminders. The likely cause is that `roles` shows only each role's id and
title, so the hub cannot tell what a role is for.

The case stays in the regression tier at 0%, so a fix shows up as a
change in the baseline.

## Acceptance criteria

- [ ] route-to-owner passes on Haiku (3 trials) and Opus (1 trial).
- [ ] The rest of the suite holds its baseline (test/scenarios/BASELINES.md).
- [ ] A public role still sees only what the operator wrote with `--public`.

## Plan

Look at what `roles` returns first: a role's purpose, or the first part
of its instructions, may be enough. Check it against the public-role
filter, since a role's text is private unless written with `--public`.

## Log
