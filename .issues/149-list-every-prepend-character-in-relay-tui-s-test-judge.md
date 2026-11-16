# List every Prepend character in relay-tui's test judge

## Context

Sent by relay-34 on 2026-11-17, from relay#130's relay-tui half. Minor,
not security.

relay-tui's test judge `skipped` (internal/render/links_test.go) writes
out only some Grapheme_Cluster_Break=Prepend characters. No test input
uses the missing ones today.

## Acceptance criteria

- [ ] `skipped` also holds U+11A84 to U+11A89, U+11F02 and U+113D1, so
  it covers the whole list `core.NoAdvance` uses.

## Plan

## Log
