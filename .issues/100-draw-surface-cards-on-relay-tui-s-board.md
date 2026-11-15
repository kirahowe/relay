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

Tightened on 2026-11-14 against the wire form relay#97 fixed
(`docs/client-protocol.md`, "A surface card").

- [ ] relay-tui reads a card of kind `surface` from `_relay/cards` and
  `_relay/cards/changed` as the contract gives it, and folds
  `surface.a2ui` from nothing each time the card arrives.
- [ ] The board draws a surface card in a panel with the other cards, at
  any pane width: Text, Row, Column, List, Card, Divider and CheckBox,
  the whole of relay's catalog. A Card at the root has no frame of its
  own, since the panel is one. Text and a CheckBox's label are dressed as
  a note card's text is and no further.
- [ ] A surface relay sends again in `_relay/cards/changed` is redrawn,
  and an archived one leaves the board.
- [ ] A surface relay-tui cannot draw in full shows the card's text form
  and takes no input: one that does not fold, holds a component outside
  the catalog, names a child that is not there, or still holds a call, a
  template or a binding anywhere but a CheckBox's value.
- [ ] The renderer breaks no link itself. Every string of a surface goes
  through relay#95's functions with the surface's `outsider` flag and
  the connection's live links, every row of an outsider's surface goes
  through the board's last check on drawn rows, and no two strings of a
  surface are drawn on one line with nothing between them.
- [ ] In an open card the keys move between the CheckBoxes whose value is
  bound to a path, and the key that ticks a checklist item sends `input`
  with that path, the new value and the card's rev as `seen`. The board
  draws the card relay answers with and never its own write first. A
  CheckBox with a literal value cannot be selected.
- [ ] When relay's newer copy of an open surface moves or removes the
  CheckBox the cursor was on, the cursor follows it or the board says it
  has gone, as for a checklist item.
- [ ] relay's refusal of an input is shown as relay wrote it.
- [ ] relay-tui's `relay_catalog.json` is relay's
  `resources/a2ui/relay_catalog.json`, byte for byte, and the spike's
  Tracker is gone from the surface renderer.
- [ ] fakehub serves surface cards and applies `input` by the contract's
  rules, refusals included.
- [ ] Properties: no surface card draws past the pane's width, and no
  control character and no link relay did not keep live reaches a row,
  whatever the surface's strings hold. relay#96's saved failing case is
  one of the tests.
- [ ] Kira has seen a mock of a surface on the board first.
- [ ] relay-tui's README says what the board draws.
- [ ] `make check` passes, and so does `bb verify` from a relay workspace
  that has relay#97.
- [ ] An adversarial Codex review finds nothing in scope.

## Plan

Without Tracker and without Button. relay's catalog v1 holds neither:
the tracker card kind stays as it is (relay#93), and Button waits for a
step of its own under relay#98's rule.

1. `internal/a2ui` and the surface drawing: relay's catalog, no Tracker,
   the inputs of a surface, the check that a surface is one relay could
   have sent, and drawing with the board's own text functions.
2. `internal/core` and `internal/wire`: the kind, the `surface` field,
   the `input` op and the cursor on a surface's inputs.
3. `internal/render`: the card on the board.
4. fakehub, the end-to-end tests and the README.
5. Verify and review.

## Log

Judgment calls, mine unless one says otherwise, 2026-11-14:

- A surface with anything relay-tui does not draw shows the card's text
  form, whole. The first draft had an unknown component draw as one line
  that names it. A surface drawn in part could leave out an input, or
  the text that explains one. relay#97's session agreed.
- Text is drawn with the note card's code and not through Markdown. I ran
  relay's strings through the spike's Markdown drawing: a live link in
  angle brackets lost its brackets, `ht**tp**s` came out as `https`, and
  line breaks inside a paragraph went. The contract now says a text is
  plain and a client dresses it only as it dresses a note.
- A Card at the root is drawn without a frame. The board already puts
  every card in a panel with its title on the border.
- One op at a time, as the board already does: a key pressed while an
  input is on its way is not sent, and the board says so. Each input
  moves the card's rev, and `seen` has to match it.
- The corner of the panel says "surface" where a checklist shows its
  progress. relay sends no progress for a surface, and the board counts
  nothing itself. The mock asks Kira whether she wants something else
  there.
- The spike's Tracker comes out of the surface renderer. relay's catalog
  has no component of its own (relay#93), so nothing could reach it.
- `a2ui.Activate` still writes a CheckBox's value into the data model.
  The board does not call it. It stays for the step that adds Button.
- The mock went into this session's messages on 2026-11-14: relay#97's
  checklist and note fixtures, drawn by the spike's renderer inside the
  board's panels, between a checklist card and a tracker card. No answer
  yet, so that criterion stays open.
