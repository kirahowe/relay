# Let Kira fill in a form a role built

## Context

Part of relay#93, and the step after buttons (relay#106). The epic's own
example is a screen for adding an agent. A role builds the form, Kira
fills it in and submits it, and the steward adds the role with
`role_add`, as it can today (Kira's answer in relay#98). That needs
TextField in relay's catalog and a relay-tui that can type into one. The
coordinating session left TextField and ChoicePicker out of the first
catalog until relay-tui can type and choose in them.

What Kira types into a field is hers and carries no labels, as on a
card. A value the role put in the data model keeps the role's labels.

## Acceptance criteria

A first draft, for whoever builds this to tighten.

- [ ] relay's catalog takes TextField and ChoicePicker, and relay-tui
  types into the one and chooses in the other.
- [ ] Text Kira typed into a field carries no labels. A value the role
  wrote keeps the role's labels, and relay tells the two apart when a
  press sends them.
- [ ] A case in `test/scenarios`: Kira asks for a way to add an agent,
  the steward builds a form, a press with Kira's fields reaches the
  steward, and the steward adds the role. It has an oracle and a foil,
  ran live on Haiku and once on Opus, and is in
  `test/scenarios/BASELINES.md`.
- [ ] The daily note lists the new role, as it does for any role the
  steward adds.
- [ ] README, resources/AGENTS.md, spec/relay.allium and
  docs/client-protocol.md describe forms.
- [ ] `bb verify` passes, and an adversarial Codex review finds nothing
  in scope.

## Plan

Needs relay#106.

## Log
