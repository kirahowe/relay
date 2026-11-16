# Do not panic on Markdown quoted deeper than half the width

## Context

relay-tui. Sent by the relay#95 session on 2026-11-15, from the confirm
round on relay#95. It is older than relay#95 and is not a security
finding.

The reviewer's text:

> Crash, pre-existing. `markdown.go:122` panics on a negative width. 45
> nested `> ` then `---` at width 80 does it (ran). It needs nesting
> deeper than half the width, so a targeted attacker: out of scope.

The relay#95 session's note: on a phone's 40 columns that is 21 levels,
which a model can write without anyone aiming at it, and a panic takes
the dashboard down until it is started again.

## Acceptance criteria

- [x] A test draws 45 nested `> ` followed by `---` at every width from
  1 to 80 and nothing panics. It fails on today's code.
  - Receipt: relay-tui ppqnkvxw dbebb09f, on relay-tui's `integration`:
    `TestMarkdownNestedDeeperThanTheWidthDraws` in
    internal/render/markdown_deep_test.go draws that and deep lists,
    code, headings and tables at widths 1 to 80, every row within the
    width. With the fix taken out it panics with "strings: negative
    Repeat count".
- [x] `make check` passes.
  - Receipt: `make check` on ppqnkvxw passed; full `bb verify` with relay
    at nvtvtrup passed every step (relay 1266/1266, pentest 33/33). A
    read-only review ran 666,600 renders (nesting 0 to 100, widths 1 to
    120): no panic, no row over the width.

## Plan

## Log

- 2026-11-16, the relay#95 session (relay-34): fixed in relay-tui
  ppqnkvxw. Each quote level and list item takes columns from the
  width, and the thematic break's `strings.Repeat` got a negative count.
  A block's width is now never less than one column.
