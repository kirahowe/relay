# Fix the labels-test property that fails on seed 1792892445485

## Context

Found 2026-10-24 during relay#79. In `bb test`,
`labels-test/every-marker-carries-its-writers-labels-wherever-it-turns-up`
fails with seed 1792892445485. The same seed fails the same way at
`rvlyzwyo`, before relay#79, so the failure predates that work. Other
seeds pass.

## Acceptance criteria

- Rerunning with that seed passes, either because the code is fixed or
  because the property is corrected, with the reason recorded.
- The failing case becomes a fixed regression example.

## Log
