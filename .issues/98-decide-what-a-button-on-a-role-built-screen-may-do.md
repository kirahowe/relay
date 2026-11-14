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

## Acceptance criteria

- [ ] Kira has decided, and the decision is recorded here and in relay#93.

## Plan

## Log
