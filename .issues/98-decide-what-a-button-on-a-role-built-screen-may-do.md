# Decide what a button on a role-built screen may do

## Context

Cards were designed with no buttons and no approvals: a card has only its
kind's fixed ops. A role-built A2UI surface can hold a Button whose label
the role wrote. A role that has read outside text could label a button one
thing and wire it to another.

## Proposal

A button on a role-built surface can do two things and no more:

- say something to the role that owns the surface, as Kira's text in that
  conversation;
- run a function relay defines on relay's own data, as a card op does now.

It never changes config, and nothing it does counts as Kira agreeing to
something outside the house. A role may build the form for adding an
agent, but submitting it lands on a screen relay draws, where Kira reads
what will be written.

## For Kira

Is that the rule? It changes what cards promised, so it is hers to decide.

## Decision

Kira, 2026-11-14, asked in the relay#93 session:

- A press on a role-built button does one of two things. It tells the
  role that owns the screen, in a line relay writes itself that names the
  button and the screen, and that starts a turn for the role as typing to
  it does. Or it runs one of relay's own functions on relay's data, as a
  card tick does now, with no model turn.
- A press never changes config, and it never counts as Kira agreeing to
  anything that leaves the house.
- The line relay writes keeps the labels of the screen's text. Whatever
  the role wired behind the label never reaches a role as Kira's words.
- Submitting a role-built form for adding an agent works as adding a role
  does today, with a form in front. The press tells the steward what Kira
  filled in, the steward adds the role with `role_add`, and the daily note
  lists it. Tools and grants stay in relay.edn, which only Kira edits.
  relay draws no confirmation screen of its own for this, so the last
  sentence of the proposal above is dropped.

She chose this over two narrower rules: relay's own functions only, where
no press ever starts a turn, and no buttons at all.

What follows for relay#97. These readings are mine. Kira read them on
2026-11-14 and said they are fine:

- In A2UI v1.0 a Button's `action` is an `event` or a `functionCall`. An
  `event` is the press that tells the role. A `functionCall` has to name a
  function in relay's catalog. The renderer sends it as
  `callAgentFunction`, relay decides it as it decides a card op, and
  answers with `agentFunctionResponse`. relay refuses a surface whose
  button names any other function, `openUrl` included.
- relay composes the role's line from the button's label and the
  surface's title as Kira saw them. The event's `name`, `userMessage` and
  `context` are the role's own writing. If the role gets them back, they
  come apart from Kira's line, marked as what the role wired, with the
  surface's labels.
- What Kira types into a field is hers and carries no labels, as on a
  card (her third answer on cards, 2026-10-20). A value the role put in
  the data model keeps the role's labels.
- One thing cards promised changes. A tick still wakes nobody. A press of
  an `event` button wakes the role that owns the surface.
- No action on a surface can answer one of relay's own review screens.

## Acceptance criteria

- [x] Kira has decided, and the decision is recorded here and in relay#93.
  - Receipt: Kira answered both questions in the relay#93 session on
    2026-11-14. Her answers are under Decision above, and under "Kira,
    2026-11-14, on buttons" in relay#93's Decisions.

## Plan

## Log

- 2026-11-14: asked Kira in the relay#93 session, with a mock of the same
  checklist under each of three rules, and a second question on the form
  for adding an agent. Her answers are under Decision.
- 2026-11-14: Kira read the five readings under Decision, said they are
  fine, and said the issue is good to close. relay#106 builds buttons on
  them.
