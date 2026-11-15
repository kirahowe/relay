# Compare relay-macos's copies of relay's shared test data in bb verify

## Context

Sent by the relay-macos session on 2026-11-15, through the coordinating
session. The text is that session's own.

relay-macos, a native Mac client that Kira started on 2026-11-14, keeps
a copy of relay's strict-JSON case table at
~/code/projects/relay-macos/Tests/RelayCoreTests/Fixtures/strict-json/cases.jsonl.
It matched relay's testdata/strict-json/cases.jsonl on `integration`
when it was copied, and `swift test` runs every case through the Swift
reader. It will keep copies of resources/a2ui/relay_catalog.json and
testdata/surfaces/ too, once relay#97 has landed.

`bb verify` already compares agentloop's and relay-tui's copies of the
case table with relay's, and relay#97 adds the same for relay-tui's copy
of the catalog and the surface fixtures. Nothing compares relay-macos's
copies, so they can drift without anyone seeing it.

relay-macos is a local repo that not every checkout of relay has beside
it. `bb verify` fails a run when a sibling it expects is missing, so
this comparison has to run only when the relay-macos checkout is there,
or take its directory as an argument like the other siblings.

## Acceptance criteria

- [ ] With a relay-macos checkout present, `bb verify` fails when its
  copy of the strict-JSON case table differs from relay's, and names the
  file.
- [ ] The same for its copies of the catalog and the surface fixtures,
  once it has them.
- [ ] Without a relay-macos checkout, `bb verify` behaves as it does
  today.
- [ ] VERIFY.md says so.

## Plan

## Log

- 2026-11-15: when this was filed, the relay#93 session compared the two
  case tables with `cmp`. relay-macos's copy was the same as relay's on
  `integration` at tuzooksz.
