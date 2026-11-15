# Decide whether checklist, tracker and note become built-in surfaces

## Context

Part of relay#93. The epic says A2UI replaces the closed set of card
kinds and the card wire form. The first build does less. A role-built
surface is a fourth card kind beside checklist, tracker and note, and
the three keep their events, ops and wire form (the coordinating
session's decision on 2026-11-14, recorded in relay#93). Until someone
decides otherwise, relay has two ways to put a checklist on the board.

The choices as they look now:

- Leave the three kinds as they are, with surfaces as the fourth.
- Make the three kinds surfaces that relay builds itself, so a client
  draws one thing. The log is append-only, so the fold goes on reading
  the `card/added` and `card/changed` events already in it.
- Stop making the three kinds and let roles build their own.

## For Kira

Wait until role-built surfaces have had real use, then choose.

## Acceptance criteria

- [ ] Role-built surfaces have been in real use, and what that showed is
  recorded here.
- [ ] Kira has decided, and the decision is recorded here and in
  relay#93.

## Plan

## Log

2026-11-14. Kira asked whether a tracker can be built from A2UI's own
components, so I drew one with relay#96's renderer (`a2uiview` in
relay-tui) from the basic catalog alone. Each line of the grid is a Row.
The label has weight 3 and each cell weight 1, and a header Row has the
same weights. A cell is a borderless Button around one Text. At 60
columns:

```
╭──────────────────────────────────────────────────────────╮
│ Catch up monthly financials                              │
│ ○ open, ● done, × skipped                                │
│                 JAN    FEB    MAR    APR    MAY    JUN   │
│ Operating       ●      ●      ●      ●      ○      ○     │
│ checking                                                 │
│ Savings         ●      ●      ●      ○      ×      ○     │
│ reserve                                                  │
│ Business        ●      ●      ○      ○      ○      ○     │
│ credit card                                              │
│ Payroll         ●      ●      ●      ●      ●      ○     │
│ liability                                                │
│ Line of credit  ●      ×      ×      ○      ○      ○     │
╰──────────────────────────────────────────────────────────╯
```

What it showed:

- The columns line up at 60 and at 100 columns, because every child of
  every Row has a weight. At 40 columns the labels break inside words.
  A grid of 12 columns still fits at 60, and at 40 every cell lands on a
  line of its own. The board's tracker falls back to a list for each row
  when the grid does not fit, and a Row has no such form.
- A cell has three states and a CheckBox has two. A ChoicePicker with
  three options draws all three in every cell, three lines high. A
  Button draws one glyph, and its press can run a relay function, which
  Kira's rule in relay#98 allows. It costs two components a cell: 40
  rows by 24 columns, the largest tracker relay takes, is 2,030
  components, and the renderer stops at 2,000. With Text cells, which
  nobody can press, the same grid is 1,070.
- The renderer's focus walks the cells one at a time in the order they
  are drawn. The board's tracker moves by row and by column.

So a tracker of the usual size can be built from primitives, and a role
may build one once Button is in the catalog. Replacing the tracker card
kind with one needs three things first: a narrow form for Rows, movement
by row and column, and room for the largest grid.
