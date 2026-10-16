# Add public roles that can never see private data

## Context

Research roles read the open web, so they must not be able to leak anything private. A role marked `:public true` in relay.edn is cut off from private data by construction.

## Acceptance criteria

- [ ] `:public true` in relay.edn marks a role as public.
- [ ] A public role's brief and routines are written by the operator with `--public`.
- [ ] Reads of facts, docs and other notes are refused for a public role.
- [ ] A memo to a public role from a private session is held for the operator to declassify: it shows the exact text and needs the typed code.
- [ ] A public role's notes and journal carry only what it saw.
- [ ] A public role has no act tools.
- [ ] Property in spec/relay.allium and in the model test: no session of a public role ever holds `private`.
- [ ] README and resources/AGENTS.md updated.

## Plan

## Log
