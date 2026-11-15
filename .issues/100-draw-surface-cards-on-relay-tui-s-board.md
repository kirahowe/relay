# Draw surface cards on relay-tui's board

## Context

Part of relay#93. relay#96's spike draws A2UI v1.0 surfaces to lines of a
given width (`internal/a2ui`, `render.A2UI` in relay-tui) and is wired to
nothing. relay#97 keeps a role-built surface in relay's log as a card of
a fourth kind and sends it to clients through `_relay/cards` and
`_relay/cards/changed`. This issue joins the two: relay-tui draws a
surface card on the board, among the checklists, trackers and notes.

What the other issues settle for this one:

- relay resolves templates, `@path`, `formatString` and `@index` before a
  client sees a surface, so relay-tui gets final strings (relay#97).
- relay has made every string safe and left its live links whole. The
  spike's renderer still breaks every link itself and has to stop. One
  function in relay-tui decides what is drawn as a followable link.
  relay#95 owns it and surfaces use it.
- A surface has one rev and one set of labels (relay#97).
- What a press may do is Kira's rule in relay#98.

The session that built relay#96 builds this.

## Acceptance criteria

A first draft from the coordinating session's brief. Whoever builds this
tightens it once relay#97's wire form is fixed.

- [ ] relay-tui draws a surface card that relay sends on the board with
  the other cards, at any pane width, from the wire form relay#97
  defines.
- [ ] A surface relay sends again in `_relay/cards/changed` is redrawn,
  and an archived one leaves the board.
- [ ] The renderer breaks no link itself. What is drawn as a followable
  link is decided by relay#95's function and by nothing else.
- [ ] A tick on a surface goes to relay, and the board redraws from
  relay's answer, as for a card.
- [ ] A component relay-tui does not know draws as one plain line that
  names it, and a surface it cannot read draws as the card's text form.
- [ ] Kira has seen a mock of a surface on the board first.
- [ ] relay-tui's README says what the board draws.
- [ ] `make check` passes.

## Plan

Needs relay#97's wire form and relay#95's link function.

## Log
