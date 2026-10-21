# Reach the relay service from the phone over the tailnet

Part of #62. Needs #64.

## Context

Research on 2026-10-17 found Agmente (App Store, MIT,
github.com/rebornix/Agmente), an iOS ACP client that speaks ACP over wss://
with bearer tokens and supports session/list and session/load. That
research said to try an existing client before building one. With #64 in
place, relay can listen for ACP over WebSocket itself, so no Node shim is
needed.

## Acceptance criteria

- [ ] The service accepts ACP over WebSocket on the tailnet, published with
  `tailscale serve` as ntfy is. Only Kira's devices reach it. A token from
  the keychain is required.
- [ ] Agmente lists the conversations and continues one started in
  acp-tui. Record what works and what does not.
- [ ] ntfy notifications for approvals and finished turns open the right
  conversation, if Agmente supports a link for it.
- [ ] Codex review loop until a round has no in-scope defect.

## Notes

Agmente will not know `_relay/*` methods, so it sees conversations through
`session/list`. If it falls short, record what it lacks before deciding on
a client of our own.

## Log
