# Draw A2UI surfaces in relay-tui

## Context

Part of relay#93. Before any role authors a surface, find out what a
renderer costs and how the board looks through one. The terminal renderers
listed at a2ui.org are community projects on v0.9 and pre-release, so
relay-tui gets its own, for a small catalog.

## Acceptance criteria

- [x] A pure package in relay-tui reads A2UI v1.0 `createSurface`,
  `updateComponents`, `updateDataModel` and `deleteSurface` strictly, and
  folds them into surfaces.
  - Receipt: `internal/a2ui` (`Decode`, `ParseLines`, `Apply`);
    `TestDecodeRejects`, `TestDecodeNotSupported`, `TestDecodeAccepts`,
    `TestFoldLifecycle`, `TestApplyKeepsItsInput` in `a2ui_test.go`.
  - Receipt: a second session compared the wire form with a2ui.org on
    2026-11-14: the v1.0 specification page,
    `catalogs/basic/catalog.json` and `json/common_types.json`. The
    envelope, the six message kinds, `@path`, `@call`, `@catalogId`,
    inline `components` and `dataModel`, the template form of
    `children`, a button's `action` and null as the delete all match
    what `Decode` reads and the fixtures hold. v1.0 calls a
    `createSurface` that carries `theme` invalid, and `Decode` refuses
    one.
- [x] It draws Text, Column, Row, List, Card, Divider, CheckBox, Button,
  TextField and ChoicePicker, and relay's own Tracker, to lines of a given
  width.
  - Receipt: `render.A2UI` in `internal/render/a2ui.go`;
    `TestFixturesGolden`, `TestFixturesFitNarrow`,
    `TestChecklistFocusAndToggle`, `TestTracker`.
- [x] Image, Video, AudioPlayer and any unknown component draw as one
  plain line that names them. Nothing is fetched.
  - Receipt: `TestA2UIPlaceholdersAndLinks`. I also ran a surface with an
    image URL carrying a query string through `a2uiview`: it printed
    `[image not shown]` and no part of the URL.
- [x] Every string drawn goes through relay-tui's existing path for text
  that may hold control characters.
  - Receipt: `TestA2UIDrawsNoControlBytesFromTheSurface`,
    `TestA2UIDefangsLinks`. The same hostile surface held an OSC 8 escape
    sequence, which came out as visible `\u001b` text.
  - Receipt: a second session put escape sequences, an OSC 8 link, BEL,
    a C1 control and a bidi override into every string a surface can
    carry (texts, labels, values, placeholders, options, tracker labels
    and cell states, a component type, a missing child's id) and ran it
    through `a2uiview`, piped and under a pseudo-terminal, which turns
    the colours on. The only escapes in the output were the renderer's
    own colour codes, and every link came out broken.
  - Receipt: the surface's id in `a2uiview`'s heading was the one
    exception. Its control characters were dropped, but a URL in it
    showed whole. Fixed in relay-tui 3645847b:
    `TestRunNamesASurfaceSafely` failed before the fix, and
    `TestA2UINameIsSafe` is the property.
- [x] Fixtures express a checklist, a tracker and a note as surfaces, with
  golden renderings.
  - Receipt: `internal/a2ui/testdata/*.jsonl` and
    `testdata/TestFixturesGolden/*_60.golden`, `*_100.golden`.
- [x] Properties: no input panics the fold; no drawn line is wider than
  asked; a data-model write reads back.
  - Receipt: `TestNoInputPanicsTheFold`, `TestWellFormedMessagesFold`,
    `TestA2UINeverDrawsPastTheWidth`, `TestSetReadsBack`,
    `TestDataModelMessageReadsBack`.
  - Receipt: `TestSetReadsBack` failed for another session on seed
    15639078114469465116, and relay-tui 9a143c2d fixes it; the seed's
    case is `TestSetNullClosesAnArray`. The four properties in
    `internal/a2ui` then passed at 300,000 cases each, and
    `TestA2UINeverDrawsPastTheWidth` at 200,000.
- [x] A command prints a surface file's rendering, for mocks.
  - Receipt: `cmd/a2uiview`, `go run ./cmd/a2uiview -width 60 <file>`.
- [x] `make check` passes.
  - Receipt: run in the main session on 2026-11-14 at relay-tui ae0ce7d2,
    every package ok, the deep surface-rules run included.
  - Receipt: a second session ran it on 2026-11-14 at ae0ce7d2 and again
    at 3645847b, the second time with `make build` after it as `bb
    verify` does. Every package was ok both times.
  - Receipt: and at 9a143c2d. It can still fail about once in two
    thousand runs, on `TestA2UIDefangsLinks`; the Log says why and where
    that is to be closed.

## Plan

Nothing is wired into the dashboard and nothing in relay changes. The
result is a mock to show Kira and a count of what the renderer took.

## Log

- 2026-11-14: built by a subagent from a written brief, reviewed and
  verified in the main session, committed as relay-tui ae0ce7d2 ("Draw
  A2UI v1.0 surfaces, as a spike").
- What it cost: 1,741 lines of Go and 996 of tests, in about 13 minutes of
  agent time. The model, fold, bindings, focus and actions are in
  `internal/a2ui` (1,172 lines). The drawing is in `internal/render`
  (459 lines), because it reuses render's own boxes, wrapping and the
  board's tracker grid, which are not exported.
- The tracker needs a component of relay's own. A2UI's basic catalog has
  no grid, so `Tracker` lives in relay's catalog, `urn:relay:catalog:v1`
  (`internal/a2ui/relay_catalog.json`), and is drawn by the board's own
  grid code. The checklist and the note need only basic components.
- My change in review: the subagent's relay catalog listed Image, Video
  and AudioPlayer. relay#93 leaves them out, so I removed them, and
  `TestCatalogs` now checks that. From the basic catalog they still draw
  as the one-line placeholder.
- What v1.0 looks like on the wire, which differs from the v0.9 most
  renderers target: a binding is `{"@path": ...}`, a function call is
  `{"@call": ..., "args": ...}`, and a component's own keys start with
  `@` (`@catalogId`, `@checks`). `createSurface` can carry components and
  the data model inline. A component whose catalog cannot be resolved
  draws as a placeholder, since A2UI gives no fallback.
- Not built, on purpose: `@checks`, `justify` and `align`, schema
  validation of each component's properties, and typing into a TextField
  or choosing in a ChoicePicker (both are drawn and take focus). Icon,
  Tabs, Modal, Slider and DateTimeInput draw as placeholders.
- A Card has no title slot in A2UI, so a card's title sits inside the box
  as Text and not in the border, unlike the board today.
- For relay#97: the renderer breaks every link in a surface's strings
  itself, as relay-tui does for conversation text. Once relay sends
  surfaces it will have made the text safe and kept its live links whole
  (relay#94), so the renderer must then take relay's word and stop
  breaking links a second time.
- The limits the package sets: 64 surfaces, 2,000 components, 1,500 drawn
  nodes, depth 16, 500 template items.
- 2026-11-14, a second session, working in the `i96` workspaces of relay
  and relay-tui: checked every receipt above again, from the code and
  the published spec. Each one holds, and every test named exists and
  passes.
- Found and fixed in relay-tui 3645847b ("Break links in the surface id
  a2uiview prints"): `a2uiview` printed a surface's id in its heading
  through `render.Faint`, which drops control characters and leaves a
  URL whole. `render.A2UIName` now draws the heading through the path
  the drawing's other strings take, so the command prints no string from
  a surface by itself.
- The test count above read 986. The commit's own stat gives 996, and
  the line now says so.
- Judgment, mine: no Codex review for the spike. No role can author a
  surface yet, and the renderer is wired to nothing: not the dashboard,
  the network, labels or approvals. The review is due with relay#97,
  when surfaces a role wrote reach this code.
- The mock: the three fixtures at 60 columns, and the tracker at 34
  where the grid gives way to a list, are in the hand-off message to
  Kira.
- 2026-11-14, after the hand-off: the seed failure above. The code was
  right and the property was wrong about arrays. A null write takes an
  element out and the rest move down, as JSON Patch's remove does, so the
  path can hold the element that came after; the property said nothing
  may be left there. A2UI says only that null removes a key, and `Set`'s
  comment now records the choice.
- Running the render properties at 200,000 cases found one more rare
  failure, in `TestA2UIDefangsLinks`. A broken link that is hard-wrapped
  between `hxxps` and `://` leaves a row that starts
  `://evil[.]test/...`, and the dashboard's own check counts that as a
  link. Nothing can follow that text, so nothing leaks. It is left for
  relay#100, which will send every row of a surface through the board's
  last check on drawn rows, with the saved rapid case as its test. It is
  not patched here because relay#95 rewrote that path.
