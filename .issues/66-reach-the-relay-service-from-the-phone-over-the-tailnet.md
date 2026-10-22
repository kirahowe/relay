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

Commits: psxunzxk (the listener, settings, tests), zkwulvuv (docs).

- [x] The service accepts ACP over WebSocket on the tailnet, published with
  `tailscale serve` as ntfy is. Only Kira's devices reach it. A token from
  the keychain is required.
  - Receipt: psxunzxk. `:serve {:websocket {:port :token-command
    :default-role :allowed-origins}}` in relay.edn (relay.config
    `websocket-problem`); relay serve listens on 127.0.0.1 only and reads
    the token once at startup. serve-test:
    `a-phone-holds-a-conversation-over-websocket` (initialize,
    `_relay/roles`, session/new with a role, a prompt and its reply, over
    java.net.http's WebSocket client),
    `a-phone-and-a-terminal-share-one-conversation` (mirroring both
    ways, the phone's permission answer settled on the terminal),
    `a-phone-s-session-new-without-a-role-goes-to-the-default-role`,
    `the-listener-refuses-before-the-upgrade-and-closes-on-what-it-does-not-take`
    (no token, wrong token, Origin: 401/401/403 with no body; plain
    request 404; binary frame closes 1003; over 8 MB closes 1009),
    `the-listener-binds-only-the-loopback-address` (lsof shows only
    127.0.0.1:port; the Mac's other addresses are refused),
    `no-listener-without-settings-or-a-token` (no setting; a failing
    command; an empty token, each with the socket still working),
    `the-websocket-and-the-socket-share-the-connection-cap`,
    `a-connection-whose-client-stops-reading-is-closed-past-the-backlog`.
    websocket-test: decision and framing, with properties (the token
    compare is string equality; the decision is total and no Origin gets
    past unless allowed; a message split any way reads back whole).
    hub-test `a-default-role-is-only-for-a-session-new-that-names-none`.
    README "Reaching relay from your phone" (zkwulvuv) has the
    tailscale serve step. `bb test --agentloop ../agentloop/bin/agentloop`
    with acp-tui-convs' bin on PATH: 853 tests, all pass. `bb tck`: 43
    pass, 12 skipped, ACP-SESSION-002 failing by design, as before.
- [ ] Agmente lists the conversations and continues one started in
  acp-tui. Record what works and what does not.
  - Needs Kira's phone: install the listener (README steps), then try it.
- [ ] ntfy notifications for approvals and finished turns open the right
  conversation, if Agmente supports a link for it.
  - Needs Kira's phone, with the previous item.
- [x] Codex review loop until a round has no in-scope defect.
  - Receipt: not run by me. The coordinator runs the loop after this
    review, as they asked.

## Judgment calls

- The WebSocket server is relay's own (relay.websocket, about 250
  lines), not http-kit's, though the brief named http-kit. http-kit's
  `send!` never blocks: it appends to a per-connection write queue with
  no bound, and bb allows no interop on its channel or server, nor a
  server-side ping, so nothing could measure or bound what waits for a
  phone that stopped reading. That broke "the same output backlog as the
  socket". With a blocking `java.net.ServerSocket`, the writer thread
  blocks on the client as the Unix socket's does, and one
  `open-connection!` gives both transports the same bounded sender and
  the same cap (taken in one atomic step now that two listeners add
  connections). The framing is tested against java.net.http's client,
  an independent implementation, and RFC 6455's own accept-key example.
- Order of the decision before the upgrade: not an upgrade 404, then
  Origin 403, then token 401, then a malformed upgrade 400. A 404
  first means a plain request learns nothing whatever it carries; Origin
  before the token means a browser page never gets to probe the token.
  Two Origin or two Authorization headers are refused.
- The token is compared as SHA-256 digests with MessageDigest/isEqual,
  so neither its length nor a prefix shows in the timing.
- The request head may take 16 KB and 10 seconds in all (a timer closes
  a slow one), and at most 64 heads are read at once; past that a new
  connection is closed unread. A full service answers an authorised
  upgrade with a bare 503 rather than upgrading and closing.
- A message over 8 MB (the socket's line limit) is refused from its
  declared length, before relay reads the payload; fragments count
  together. Text that is not UTF-8, an unmasked frame and other RFC 6455
  violations close the socket too (1007, 1002). Pings are answered.
- A text message is passed to the hub as it is, newlines and all: the
  hub reads it strictly and writes every line it passes on afresh, so a
  newline inside one cannot split it into two messages downstream.
- `:default-role` is a connection option in the hub (`:default-role` on
  `:connection-opened`), separate from a connection's own role, so it
  names only where a session/new without one goes; a request with no
  sessionId is not routed to it. A WebSocket's `_relay/connect` is
  refused like any non-socket connection's.
- `:default-role` is checked to be a role id when relay reads the
  config, not that the role exists: roles live in the data, and a
  missing one is refused at session/new like any unknown role.
- A port already in use, like a failing token command, leaves the
  service running without the listener and says why on stderr.
- `tailscale serve --bg` in the README, so the publication outlives
  the terminal.
- `relay init`'s template is unchanged; setup/relay.example.edn shows
  the setting, as it does ntfy's token command.

## Notes

Agmente will not know `_relay/*` methods, so it sees conversations through
`session/list`. If it falls short, record what it lacks before deciding on
a client of our own.

## Log
