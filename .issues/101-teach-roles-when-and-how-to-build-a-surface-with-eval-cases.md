# Teach roles when and how to build a surface, with eval cases

## Context

Part of relay#93. relay#97 gives roles a fourth kind of card, a surface,
which they lay out from relay's catalog. What a role is told is the text
`cards` gives with `catalog`, the card tools' descriptions and a section
of `resources/AGENTS.md`, all written in relay#97. relay's tests show
that relay checks, resolves and makes safe whatever a role writes.
Nothing yet shows that a real model, given that text, writes a surface
relay accepts, or that it makes no surface when a checklist, a tracker
or a note would do.

These are the eval cases for it, made the way AGENTS.md describes for a
case from real use, on artificial data. The relay#97 session asked for
the first of them on 2026-11-14 and wrote its criteria.

The epic's own example, a form for adding an agent, needs Button and a
text field. It is in relay#107.

## Acceptance criteria

- [ ] A case in `test/scenarios` asks the steward for something none of
  the three fixed kinds fits, such as a packing list grouped by bag with
  a note above it. Its oracle adds a surface relay accepts.
- [ ] A foil fails that case's grader: one that adds a checklist
  instead, or a surface relay refuses.
- [ ] A second case asks for something a checklist, a tracker, a note or
  a plain message fits. Its oracle makes no surface, and a foil that
  makes one fails the grader.
- [ ] Both cases ran live on Haiku (`--trials 3`) and once on Opus, the
  transcripts were read, and the run ids are here and in
  `test/scenarios/BASELINES.md`.
- [ ] If the model's first surface is often refused, the catalog text or
  the refusals were changed until it is not, and the Log says what
  changed.
- [ ] `resources/AGENTS.md` says when a surface is the right thing to
  make and when one of the fixed kinds or a message is. If relay#97's
  section already does, the receipt points at it.

## Plan

Needs relay#97.

## Log

- 2026-11-14: first filed with three cases, the form for adding an agent
  among them. The relay#97 session then sent the first case in the form
  it has now. The form case went to relay#107, so this issue no longer
  waits on Button.
