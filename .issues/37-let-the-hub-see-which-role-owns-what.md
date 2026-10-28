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

2026-10-28 (Claude):

- The house rework (B12b, 2026-10-26) had already changed the case.
  route-to-owner v2 lets the steward put the reminder on its own
  calendar, and BASELINES.md records it at 3/3 on Haiku and 1/1 on
  Opus. The cause this issue names was still there, though: `roles`
  described itself as showing "what each is for" and gave only id,
  title and reports-to.
- `roles` now shows under each role the first paragraph of its
  instructions, without heading lines, on one line, cut at a word to 400
  characters (`core/role-purpose`). I chose the instructions over a new
  `purpose` field: a charter already opens with what the role is for
  (setup/steward.md asks for that), and a new field would mean new
  events, CLI flags and a public-text marker for no gain. `role_add`'s
  description now asks for the purpose in the first paragraph.
- The read carries the role's `:instructions-labels` along with the
  title and id labels, because it shows that text. The two labels differ
  only after a `role_update` that changed the instructions alone.
- Public roles: no change to the filter. A public role sees a role only
  when `role-text-public?` holds, which already requires the
  instructions to be written with `--public` and to carry no private
  label. That is tested in public_roles_test.
- Codex review, round 1: no security findings. It found that a heading
  directly followed by text, with no blank line between, dropped that
  text as well. Fixed by removing heading lines before splitting into
  paragraphs, with a test for it.
