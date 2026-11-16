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

## Decisions

Kira, 2026-11-16, through relay-32's dialog, on two questions from this
session's plan:

- "keep the sources." The memo a press sends carries the card's labels
  and keeps its sources, so what a role does after a press on a card
  built from outside text shows that source, as acts from outside text
  do. (Accepting an offer in the daily note drops them instead.)
- "only `relay:setInput` for now." A function button sets one box on
  the same card, a path a CheckBox is bound to, to true or false.

relay-32's additions, 2026-11-16: relay-tui takes a press only from a
live key pressed ArmDelay after the card came up, as it does for archive
or a link's yes, never from input replayed after connecting. The
pentest covers a button in a list item whose label an outsider shaped.

Mine:

- A2UI's Button has `child`, `action` and `variant`. relay's Button
  keeps them, needs the child to be a Text, and draws that Text as one
  line, so the label a press names is one string the operator saw.
- `variant` takes `primary` and not `borderless`, which relay-tui draws
  underlined like a link it would follow.
- An event has a `name` and nothing else. A `userMessage` or `context`
  is refused, not dropped, since relay writes the role's line itself
  and a role must not think its text reached anyone.
- A press is an op, `press`, with the drawn `component` and `seen`. relay
  finds the button in the surface drawn at that rev, so what is pressed
  is what was shown, and a button in a list item names the item by its
  drawn id (`remove__2`).
- Only the operator presses. A role's `card_op` is not offered `press`,
  and one sent anyway is refused.
- A press that tells the role is a memo from the operator to the card's
  owner and changes nothing on the card. A second press of the same
  button is refused while the memo of the last one is unread, so a held
  key cannot queue wakes. A card the operator keeps herself has nobody
  to tell, so a press there is refused.
- A press that sets a box is recorded as that box's `input` op: no
  labels, nobody woken.

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
- 2026-11-15, from the relay#93 session: relay#97, the only blocker,
  is done, so this is ready.
- 2026-11-16: relay's side built: Button in the catalog, the `press`
  op, the memo, the contract, a fixture, the pentest s28 and the
  documents. relay-tui's side was built by a subagent to the contract,
  in my `ae` workspace, and I read the arming code and ran `make check`.
- 2026-11-16, review round 1, a fresh reviewer agent per side (Codex is
  out of quota until 2026-11-19), each with the threat model and the
  diff and none of my conclusions.
  - relay: no route to a third action, `openUrl`, config, an approval,
    or a press by a role. Five findings, all in scope and fixed with
    tests: the memo said "you wired" to the owner when another role had
    replaced the components, with the event name outside any quote (it
    now names who wired it, kept as `:wired-by`, and quotes the name);
    the label stood unquoted in the subject (the subject is now relay's
    words only, and the prompt says relay wrote the memo); a button a
    list draws could set another item's box through an absolute path
    (refused now); the memo named the item only by its drawn id (it now
    gives the rev pressed and the item's path, and says to check them);
    a press on a card whose owner became public wrote a memo nobody
    could take (refused now).
  - relay-tui: one in-scope defect, sent back to be fixed: a press was
    armed from when the focus reached the button, so a doubled key on a
    button scrolled out of view brought it back and pressed it at once.
    Also fixed: a label with a line break drawn as two buttons, and a
    press with no pane geometry failing open. Left as they are: a clean
    surface's label shows an address as the role wrote it, as every
    clean card does (relay#119, Kira's to decide); a label holding
    `] [` reads as two buttons, sent to relay-32 to file.
