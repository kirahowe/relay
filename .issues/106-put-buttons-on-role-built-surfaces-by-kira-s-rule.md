# Put buttons on role-built surfaces by Kira's rule

## Context

Part of relay#93, and the second step after relay#97. relay#97's first
catalog has no Button and its criteria do not cover one. Kira's rule for
what a press may do is in relay#98, with my reading of what it means for
A2UI's two kinds of action.

In short: an `event` button tells the role that owns the surface, in a
line relay composes from the label and the title Kira saw, and that
starts a turn for the role. A `functionCall` button runs one of relay's
own functions on relay's data with no model turn. A press never changes
config and never answers one of relay's review screens.

The coordinating session asked that this follow relay#97's first
landing. The `input` op relay#97 agreed with relay-tui carries a path
and a value, and has no place yet for a press.

## Acceptance criteria

A first draft. Whoever builds this tightens it against relay#97's wire
form.

- [ ] relay's catalog takes Button. relay refuses a surface whose button
  names a function outside relay's catalog, `openUrl` included.
- [ ] A press of an `event` button reaches the role that owns the
  surface as a line relay wrote, naming the button and the surface, and
  starts a turn for that role.
- [ ] That line carries the labels of the surface's text. The event's
  `name`, `userMessage` and `context` reach no role as Kira's words.
- [ ] A press of a function button changes relay's data as a card op
  does, starts no model turn, and every client redraws.
- [ ] No press changes config, and none answers one of relay's review
  screens. A test tries both.
- [ ] relay-tui moves to a button, presses it with a key, and shows
  relay's refusal as relay wrote it.
- [ ] README, resources/AGENTS.md, spec/relay.allium and
  docs/client-protocol.md say what a button may do.
- [ ] A pentest case presses a button whose label and wiring disagree.
- [ ] `bb verify` passes, and an adversarial Codex review finds nothing
  in scope.

## Plan

Needs relay#97. relay-tui's side builds on relay#100.

## Log
