# Check what ACP clients do with a tool call location that is an address

## Context

Sent by the relay#118 session on 2026-11-15, a follow-up to relay#104
from the coordinating session's read of it. The text is the relay#118
session's:

> relay.hub leaves a tool call's `locations` paths as written, since a
> client opens them as files. Nobody has checked what Zed or Agmente do
> when a location's path is an http or https address, or holds one:
> follow it, open it, or show it as a link. agentloop sends no locations
> today. Run each with a location whose path is
> `https://example.org/x?d=CANARY` and record what happens. If either
> follows or shows it whole, break locations in a session that may hold
> outside text too.

A note from the relay#93 session: Kira has said Zed is only a test
client, and Agmente is the client tried first for the phone (relay#84).
So Agmente's result is the one that matters for use. Kira also said
relay should not design around undocumented behaviour it has reverse
engineered. A run here is evidence; if the answer is unclear or could
change, breaking locations whatever the clients do may be the simpler
rule.

## Acceptance criteria

A first draft, mine.

- [ ] The Log says what Agmente and Zed each did with a location whose
  path is `https://example.org/x?d=CANARY`: followed it, opened it,
  showed it as a link, or showed it as text.
- [ ] If either followed it or showed it whole, locations are broken in
  a session that may hold outside text, with a test; or relay#134's
  safe list drops `locations`.

## Plan

Related: relay#104, relay#134, relay#84.

## Log

