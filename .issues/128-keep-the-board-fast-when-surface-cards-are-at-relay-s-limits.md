# Keep the board fast when surface cards are at relay's limits

## Context

relay-tui. Sent by the relay#100 session on 2026-11-15, from relay#100's
third review round. The reviewer did not count it as a defect of the
change.

The reviewer's numbers, measured on Kira's Mac:

- The worst single surface at relay's limits takes about 0.115 s to
  draw: two texts of about 2,600 spaced emoji with a skin-tone modifier,
  Rows 12 deep, from outside, at width 120.
- Ordinary English words take 0.05 s.
- 1,000 components of 16-character leaves take 0.03 to 0.044 s.
- A 199-row checklist a role might really write takes 4.5 ms.

The cost is the measuring a Row does to find each child's narrowest
width. The same two texts take 4 ms in a Column and 86 ms in a Row.
Nothing is cached, and the list draws every card whole before it cuts
it to twelve lines (`internal/render/board.go`, `cardLinesFor` and
`boardParts`). So ten such cards cost about 1 s a frame, and thirty
about 3 s for the pane and 6.6 s for a whole 110-column view.

One surface cannot hang the dashboard. Seconds a frame need many cards
filled to the limit on purpose, which the threat model leaves out.

Two fixes the relay#100 session named: keep a card's drawing by its rev
and width, or stop drawing a card in the list once it has its twelve
lines.

## Acceptance criteria

A first draft, for whoever builds this to tighten.

- [ ] A benchmark draws the list with thirty of the reviewer's worst
  surface at width 120. The Log has the time for a frame before and
  after.
- [ ] A test shows the saving: an unchanged card is not drawn again for
  the next frame at the same width, or the list stops drawing a card at
  its twelve lines.
- [ ] `make check` passes.

## Plan

## Log
