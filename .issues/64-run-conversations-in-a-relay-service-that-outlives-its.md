# Run conversations in a relay service that outlives its clients

Part of #62. Needs #63.

## Context

A conversation now lives as long as the `relay acp` process its client
started. To keep a turn running after the client closes, and to let
another device pick the conversation up, something longer-lived has to own
it. Kira agreed on 2026-10-21 to an always-on service on the Mac.

## Acceptance criteria

- [ ] A relay service (launchd job; Kira installs it) owns running
  conversations. It starts one agentloop per conversation and keeps the
  proxy's hooks: permission gating, previews, displays, the board and the
  observer.
- [ ] Clients connect over a local socket. One connection carries every
  conversation as ACP sessions, with `session/new` taking the role.
  `relay acp` becomes a stdio bridge to the service, so `relay chat`, Zed
  and the existing traces keep working.
- [ ] A client can detach and reattach. A running turn keeps going while
  nothing is attached. On attach, the client gets the conversation's
  history replayed and then live updates.
- [ ] Several clients may be attached at once (one person, several
  devices). Each sees every update. The first answer to a permission
  request wins; the others see it resolved.
- [ ] A call held for approval while no client is attached reaches Kira
  through ntfy, as held calls do today.
- [ ] Kira ends a conversation explicitly, or by starting a new one with
  the role. A conversation is never ended because its client went away.
- [ ] Restarting the service (deploy, crash) leaves a state Kira can
  recover: conversations it held end and are recorded as interrupted.
- [ ] Codex review loop until a round has no in-scope defect.
- [ ] README, resources/AGENTS.md, spec updated. bb test passes.

## Notes

- No env vars for wiring. The socket path comes from config.
- No resume of ended conversations (#62 decision). Reattaching to a live
  conversation is not a resume: the session never closed.

## Log
