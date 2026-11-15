# Let a surface show relay's own data without the role rewriting it

## Context

Sent by the relay-macos session on 2026-11-14, through the coordinating
session. The text is that session's own.

Part of relay#93. relay resolves a surface to final strings before a
client sees it, and those strings come from the data model the role
wrote. A surface that shows something relay keeps, such as the facts
under a topic or what is ready, is right only until that thing changes.
To keep it right the role has to be woken to write it again. That costs
a model call each time, and the surface is stale in between.

A press that runs one of relay's functions already needs no model turn
(relay#98). Reading could work the same way: a surface names something
relay keeps, and relay fills it in whenever it sends the surface. Each
text filled in this way would carry its own labels and go out through
the path cards use.

Raised in the relay-macos session on 2026-11-14. Kira has not decided
it.

## For Kira

May a surface read relay's own data directly? If so, which data first?

## Acceptance criteria

- [ ] Kira has decided, and the decision is recorded here and in
  relay#93.

## Plan

## Log
