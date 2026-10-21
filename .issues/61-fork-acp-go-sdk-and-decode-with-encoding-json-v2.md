# Fork acp-go-sdk and decode with encoding/json/v2

## Context

relay#58 made relay, agentloop and acp-tui read JSON by one strict
dialect (shared table: `testdata/strict-json/cases.jsonl` in each repo).
agentloop and acp-tui both decode ACP through
github.com/caelis-labs/acp-go-sdk (v1.5.0), which decodes every message
with `encoding/json` v1. v1 matches member names case-insensitively
(`"Method"` fills the `method` field) and keeps the first of two
duplicate names, so it does not read the dialect.

Both repos work around it today with a generic step: decode a line as
the SDK would, encode it again, compare the names with what was written,
and strip any member the SDK would misread (acp-tui
`internal/strictjson` ForV1 and `internal/wire/screen.go`; agentloop
`acp/foldnames.go` and its strict screen). It holds up in review and QA,
but it is a workaround for a decoder we do not control, the kind of
patch layer #58 exists to remove.

Kira, 2026-10-21: fork the upstream SDK and fix it properly in the fork.
Pick this up in a fresh session.

## Plan

- Fork caelis-labs/acp-go-sdk under github.com/kirahowe (Kira creates
  the fork or approves creating it).
- In the fork, decode every message with `encoding/json/v2` (standard in
  Go 1.27): case-sensitive names, duplicates rejected, one value per
  message. Keep raw JSON where the SDK passes values through
  (`RawInput`, `_meta`), so numbers stay exact.
- Run the fork's own tests and the shared strict table through its
  decoder.
- Point agentloop and acp-tui at the fork (go.mod replace or module
  path), then delete the fold-stripping step and its tests in both,
  keeping the table tests and the behaviour tests.
- Decide whether to offer the change upstream (outward-facing: Kira's
  call).

## Acceptance criteria

- [ ] The fork decodes with encoding/json/v2 and passes the shared
      strict table.
- [ ] agentloop and acp-tui use the fork; the fold-stripping code is
      gone; `make test` / `make check` and relay's `bb test` pass.
- [ ] A Codex review round on the change is clean.

## Log
