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
- 2026-11-16, review round 2 on relay's side, a fresh reviewer: no route
  to a third effect and no structural break. One low finding in scope,
  fixed with a test: the prompt still framed a press memo as "the
  operator's own request", beside "Do what the button is for", which
  gave a label's instruction her authority in the model's eyes. A press
  memo now has its own line instead (she pressed it, and did not write
  or agree to what it says), the inbox tool says the same, and the body
  says to do what the button is for only if she would want it and to
  ask otherwise. Also fixed from its nits: the memo said both "the
  operator wired it" and "a role wrote" its label when she had written
  the components herself; the refusal table and the README said "read"
  where the block lasts until the memo is closed; two stray 11-17
  dates. Left: a role removed and added again under the same id is told
  "you wired it", since relay keeps no role's incarnation and only Kira
  re-adds a role. Filed from round 1, by relay-32: relay#147 (relay-macos
  learns Button) and relay#148 (a label holding `] [`).
- 2026-11-16, review round 3 on relay's side, a fresh reviewer: no route
  to a third effect and no structural break. Two low findings in scope,
  both about wording and both fixed: a dead letter of a press memo
  handed its "you wired it" on to the handler, which had not (the body
  now names the wirer by id, for any reader), and resources/AGENTS.md
  told roles the memo quotes the label back as their own words, which is
  false when another role or outside text wrote it. Also fixed from its
  nits: the button's id is quoted like the event name, and the press
  line in the prompt keeps the reply mechanics an operator's request
  has. Three rounds each found something in scope, all low after round
  1 and none a third effect, so by AGENTS.md the loop stops and the
  question goes to Kira.
- 2026-11-16, Kira, in the coordinating session's dialog, asked after
  three rounds on relay's side that each found something in scope
  (round 1: five defects of the memo's wording and attribution and a
  button that could tick another item's box; round 2: a press framed as
  her own request; round 3: a dead letter carrying "you wired it" and
  the role instructions calling the label the role's own words; all
  fixed, none a third effect, a press by a role or a path to config or
  approvals): "Land after three rounds", together with relay-tui's side.
  relay-32 dated it 2026-11-17; it was 23:59 on 2026-11-16 here when it
  arrived.
