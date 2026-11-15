# Teach roles when and how to build a surface, with eval cases

## Context

Part of relay#93. Once a role can put a surface on the board (relay#97),
it has to know when one is worth making and how to write one relay will
accept. Roles learn the board today from one section of
`resources/AGENTS.md` ("The operator's board") and from the tools'
descriptions. Two scenarios touch cards: `grant-digest-skips-the-board`
and `look-ahead-offers-interview-help`.

The epic's own example belongs here as a case. Kira asks for a way to
add an agent, the steward builds a form for it, and submitting the form
ends with the steward adding the role through `role_add`, as it can
today (Kira's answer in relay#98).

## Acceptance criteria

- [ ] `resources/AGENTS.md` says when a surface is the right thing to
  make, when a checklist, a note or a plain message is, and how to keep a
  surface current.
- [ ] A scenario where a surface is the right answer, with an oracle that
  passes every grader and a foil that fails the grader for it.
- [ ] A scenario where a surface is the wrong answer, such as a one-off
  question, with an oracle and a foil.
- [ ] A scenario for the form that adds an agent: the role builds the
  form, a press with Kira's fields reaches the steward, and the steward
  adds the role. It has an oracle and a foil.
- [ ] Each case is run live on Haiku (`--trials 3`) and once on Opus. The
  run ids are recorded here and the cases are in
  `test/scenarios/BASELINES.md`.
- [ ] In those live runs relay accepts the surfaces the role writes
  without a refused attempt, or the Log says what the tool's description
  lacked and how it changed.

## Plan

Needs relay#97. The form case also needs Button and a text field in
relay's catalog, which come after the first catalog.

## Log
