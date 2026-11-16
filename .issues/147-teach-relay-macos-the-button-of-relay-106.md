# Teach relay-macos the Button of relay#106

## Context

Sent by relay-ae on 2026-11-17, from relay#106. relay-macos has no tracker of its own, so this is filed here.

relay#106 adds Button to relay's catalog (`urn:relay:catalog:v1`) and a `press` op to `_relay/cards/op` (docs/client-protocol.md, "A surface card"). relay-macos keeps byte copies of `resources/a2ui/relay_catalog.json` and `testdata/surfaces/` (new: `buttons.json`, `buttons.card.json`). Its SurfaceTests check them against relay-tui's, and that check fails once relay-tui's half lands.

## Acceptance criteria

- [ ] relay-macos's copies match relay's again.
- [ ] relay-macos draws Button and sends `press` by the contract (only from a live, armed key, with the button wholly on screen, never acting locally), or shows a surface holding a Button as its text form until it does.

## Plan

## Log
