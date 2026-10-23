# Keep an emoji with a variation selector inside the width relay-tui draws

## Context

Found 2026-10-23 by phase 4's agent: `render.TestWidthBound` in relay-tui
failed once on a random seed. A thought entry of "AAAA" followed by
U+FE0F measured 9 columns at width 8. It is in the entry renderer and
unrelated to the dashboard work. The failing rapid input was saved at
the time in a session scratchpad that will not last; rerun
`go test ./internal/render -run TestWidthBound -rapid.checks=20000` to
find it again.

## Acceptance criteria

- The renderer measures a variation selector the way the terminal draws
  it, and the property holds over many seeds.
- The failing input becomes a fixed regression case.

## Log
