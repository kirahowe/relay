# Let a key act only on a target the frame drew (hit-testing in relay-tui)

## Context

relay-tui. Proposed in relay#110 and filed at Kira's request on
2026-11-16.

A key in relay-tui can tick a box, mark a cell, answer a question or send
what an editor holds. The rule is that it acts only on what the operator
can see. Today each place enforces that on its own, and each check is a
guess by one part of the drawing about a layout decided elsewhere:

- `core.CardScroll.Shown`: the focused line of an open card is all in
  the pane's window.
- `AskShown`, and `Hidden` on `PaneKey` and `ReadyKey`: a link question's
  whole host is on the screen.
- the arming delay on yes answers, and the least terminal size (80 by
  24) for everything else.

The hub, the single-agent view, the pane and the card each lay out and
cut rows themselves. Review of relay#110 kept finding places where one
check and the final screen disagreed: the hub cut a pane after the pane
said a box was shown, a cell line was cut in half, and single-agent mode
asked the hub's state for a link question. The 80 by 24 least size
removed the cases on tiny screens. It did not give the app one answer to
"what is on the screen".

The standard answer is hit-testing. The finished frame, after every cut,
lists the targets it actually drew: each box, cell, line, question and
editor, by an id the core knows. A key that acts names its target, and
the core acts only when that target is in the list the frame on screen
handed back. One mechanism then replaces the separate visibility flags,
and a new widget gets the rule by drawing its target, with no check of
its own.

## Acceptance criteria

- [ ] The frame on screen hands the core the set of targets it drew,
  computed after every cut the outer layout makes, in hub and
  single-agent mode alike.
- [ ] Every key that acts (x and space, y to a question, enter in the
  board's editor, d on a link, and the ready view's acts) acts only on a
  target in that set. Otherwise it does nothing, or brings the target
  into view, as x does today.
- [ ] `CardScroll.Shown`, `AskShown` and `Hidden` are gone, replaced by the
  set.
- [ ] A property test drives random keys, sizes and board changes, and
  checks that nothing is acted on unless it was in the last frame drawn.
  It is judged on the screen's text, not on the set (see
  independent-test-oracle).
- [ ] `make check` passes.

## Plan

Wants a design pass first: what a target's id is for each kind (a box is
a surface copy and a component, a cell a row and a column, a question
its subject), and where the set is assembled so that the outer layout's
cuts are counted. Not started.

## Log

- 2026-11-16: filed from relay#110 (the relay#110 session). Kira agreed it
  should be its own issue.
