# Merge the proxy's request bookkeeping into the hub

## Context

Raised by the independent review of #64 on 2026-10-22. The hub and each
conversation's `proxy!` both keep an id map and owed-answer tracking over
the same stream, and both check permission answers. Each layer is tested,
but two layers over one stream can drift, and most of the Codex findings
on #64 came from bookkeeping like this.

The longer-term shape is for the proxy's screens (permission gating,
previews, displays, relay-method dropping, owed responses, oversized
lines) to become pure functions the hub calls. That would leave one
bookkeeping layer, in `relay.hub`, with the proxy reduced to byte
plumbing and the agent process.

## Acceptance criteria

- [ ] One place tracks each request between client, hub and agent.
- [ ] Every guarantee `relay acp` gave before keeps its test.
- [ ] The hub simulator covers the merged screens.

## Plan

## Log
