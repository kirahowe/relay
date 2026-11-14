# Draw A2UI surfaces in relay-tui

## Context

Part of relay#93. Before any role authors a surface, find out what a
renderer costs and how the board looks through one. The terminal renderers
listed at a2ui.org are community projects on v0.9 and pre-release, so
relay-tui gets its own, for a small catalog.

## Acceptance criteria

- [ ] A pure package in relay-tui reads A2UI v1.0 `createSurface`,
  `updateComponents`, `updateDataModel` and `deleteSurface` strictly, and
  folds them into surfaces.
- [ ] It draws Text, Column, Row, List, Card, Divider, CheckBox, Button,
  TextField and ChoicePicker, and relay's own Tracker, to lines of a given
  width.
- [ ] Image, Video, AudioPlayer and any unknown component draw as one
  plain line that names them. Nothing is fetched.
- [ ] Every string drawn goes through relay-tui's existing path for text
  that may hold control characters.
- [ ] Fixtures express a checklist, a tracker and a note as surfaces, with
  golden renderings.
- [ ] Properties: no input panics the fold; no drawn line is wider than
  asked; a data-model write reads back.
- [ ] A command prints a surface file's rendering, for mocks.
- [ ] `make check` passes.

## Plan

Nothing is wired into the dashboard and nothing in relay changes. The
result is a mock to show Kira and a count of what the renderer took.

## Log
