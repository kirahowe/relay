# Decide whether _relay/roles sends a role's source labels whole

## Context

Found by relay#120's reviewer (a fresh read-only agent) on 2026-11-16 and
sent by relay-ec. The reviewer's text:

> _relay/roles sends a role's labels as raw strings beside its title
> (relay.roster/wire-role). A source label can hold an outsider's
> address, such as web:https://evil.test/?d=..., and the label is sent
> whole. The protocol makes labels data, not text to show, and relay-tui
> never draws them. A client that did, whether by mistake or as a debug
> view, would show a link that could be followed. Decide whether relay
> should send only the label kinds a client needs (contaminated,
> private), or break the value after the kind as outside text.

## Acceptance criteria

- [ ] The choice is made and recorded here with its reason, and
  `_relay/roles`, docs/client-protocol.md and their tests follow it.

## Plan

## Log
