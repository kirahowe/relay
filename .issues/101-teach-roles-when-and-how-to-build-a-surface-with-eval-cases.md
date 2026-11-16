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

Kira, 2026-11-15: the user should not have to know anything about how
surfaces work. The agent decides what makes sense and acts as the
designer. So every case here starts from what the user wants to keep
track of, in the user's own words, and never names a surface, the
catalog or a kind of card. She also floated an internal UX agent that
runs all the time and advises on the best layout for what the user
describes; she then said it can come later, since most users will not
need it.

## Open

Who designs a surface: the steward by itself, or a designer role the
steward consults. My proposal, not decided: a private role with no way
out of the house, which the steward memos when a need calls for a
screen. It would be the one role that has to know the catalog well, and
the steward would only need to know when to ask it. The same cases can
compare the two, so the choice rests on results. An always-on UX agent
is later work, outside this issue.

## Acceptance criteria

- [ ] No case's prompt names a surface, the catalog or a kind of card.
  The user says what they want in their own words.
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
- [ ] The cases ran with the steward designing alone, and with a
  designer role if one is built here. The Log records which did better
  and what was chosen.

## Plan

Needs relay#97.

## Log

- 2026-11-14: first filed with three cases, the form for adding an agent
  among them. The relay#97 session then sent the first case in the form
  it has now. The form case went to relay#107, so this issue no longer
  waits on Button.
- 2026-11-15: added Kira's principle that the agent is the designer and
  the user needs to know nothing about surfaces, and the open question
  of a designer role (relay#97 session).
- 2026-11-15, from the relay#93 session: relay#97, the only blocker,
  is done, so this is ready.
- 2026-11-16 (relay-ae): two cases, `packing-by-bag` (capability) and
  `plain-list-stays-a-checklist` (regression), each with an oracle and
  foils that pass `bb test`. The graders read cards through a new
  `:card` kind in `relay.scenario`. A checklist's items count as its
  boxes, so a checklist whose headings are items of their own fails the
  heading grader, as relay-32 asked: a bag there is a box to tick.
  `relay.eval-test/the-operator-asks-for-surfaces-in-their-own-words`
  checks that no memo in a `:surfaces` case names a surface, the
  catalog or a kind of card.
- 2026-11-16, Haiku round 1, `20261116T035909Z-0386` (prompt digest
  `0a511b158ba0`): packing 0/3, plain list 3/3. Every packing trial
  weighed a surface and chose "the simpler checklist", with items like
  "Blue duffel: sleeping bag", and one made the ferry line an item. The
  board section said only to use a fixed kind when it fits, and never
  what a checklist cannot show. All three also read my "reminder up top"
  as one for the operator's reminder lists and made a calendar event,
  which was the case's fault. No surface was refused, since none was
  tried.
- Changed after round 1: the case's words are now "a line at the top"
  (version 2). The board section of `resources/AGENTS.md` now says a
  checklist is one flat list, that items in groups the operator named or
  a line that is not a step need a surface, and that a group's name
  written into every item is the sign. Its examples (rooms, days, an
  address, a deadline) are not the case's, so the case does not test its
  own wording. `card_add`'s description says the same in a clause.
- Round 2 waits: the live run was refused by this session's permission
  check, as spending money, so it needs Kira's approval.
