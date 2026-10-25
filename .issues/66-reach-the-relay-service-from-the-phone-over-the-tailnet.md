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
  - Receipt: three read-only Codex rounds on mnxzrokx..nvwyywuw. Round 1
    found four (fragment memory, a hanging token command, a length
    overflow, lax close frames), round 2 one (the message deadline), and
    round 3 found no in-scope defect (2026-10-22).

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

## Codex round 1

No auth or origin bypass. Four findings, all fixed in spqpkwuz. Each
test failed before its fix: the reader tests ran against the old
reader, and the shell tests ran with each fix reverted in place.

1. Fragments were kept as a list of arrays and only payload bytes were
   counted, so endless empty continuations grew memory. Now they go into
   one buffer, a message may take at most 1024 frames (control frames
   between fragments included) and 30 seconds by the reader's clock, and
   a timer in the shell closes the socket when a fragmented message's
   next frame never comes. Tests: websocket-test
   `a-message-is-bounded-in-frames-and-in-time` (2000 empty
   continuations with a limit of 0; exactly 1024 frames still pass; a
   fake clock gives 1008); serve-test
   `a-message-whose-next-frame-never-comes-is-stopped`.
2. The token command ran before the Unix socket, with no timeout and
   unbounded output. Now it runs on a thread after the socket listens,
   with 10 s and 4 KB; past either relay kills it and its descendants by
   PID (ProcessHandle), and on any failure the service runs without the
   listener and says why on stderr. Test: serve-test
   `a-token-command-that-hangs-or-says-too-much-gives-no-listener` (a
   command that hangs and one that is `yes`: the socket works while it
   runs, no listener after, the hanging command's child is gone).
   Found on the way: the listener's stderr lines were printed from a
   new thread that did not have serve!'s binding of *out* to *err*.
   They now go to stderr.
3. `(+ size n)` overflowed for a length near Long/MAX_VALUE, and the
   exception skipped the hub connection's close!. Now the length is
   compared with what is left of the limit (1009), and close! runs in a
   finally. Tests: websocket-test
   `a-length-near-the-largest-long-is-refused-not-overflowed`;
   serve-test `whatever-ends-a-websocket-reader-frees-its-place` (a
   reader that throws, and the overflow over a real socket: the place
   is freed both times).
4. Close frames and lengths are now read strictly: a 1-byte close
   payload, a status outside 1000-1003, 1007-1014 and 3000-4999, a
   reason that is not UTF-8, and a length not in its shortest form all
   close with 1002. 1012-1014 are accepted because IANA registered
   them after RFC 6455. Test: websocket-test
   `a-reader-is-strict-about-close-frames-and-lengths`.

After: `bb test --agentloop ../agentloop/bin/agentloop` with
acp-tui-convs' bin on PATH, 859 tests, all pass.

## Codex round 2

Round 1's fixes confirmed. One in-scope finding, fixed in the commit
"Time a WebSocket message from its first byte".

1. The message deadline started only after a first non-final frame had
   arrived whole, and a single-frame message was never timed, so a
   client stalling partway through a header, a mask or a payload held
   its place and up to 8 MB for good. Now `read-message` calls
   `(busy! t)` as soon as a message's first byte arrives and
   `(busy! nil)` when it ends. Every read in between, across all its
   frames, falls under the shell's one timer, which closes the socket
   at `max-message-ms` (30 s), checking every 100 ms. The reader no
   longer checks a clock. A ping or pong between messages counts as a
   short message of its own. Waiting for a first byte is unbounded, so
   an idle connection stays.
   Tests: serve-test
   `a-message-that-stalls-anywhere-is-stopped-at-its-deadline` (stalls
   in the first frame's header, in its mask, in a single-frame
   message's payload, and between fragments; each closed between 350
   ms and 3 s with a 400 ms deadline, and its place freed). The first
   three fail on round 1's code. `an-idle-connection-between-messages-is-never-closed`
   (1.5 s idle with a 200 ms deadline, then still answering pings).
   websocket-test checks when busy! is called, single frames included.

After: `bb test --agentloop ../agentloop/bin/agentloop` with
acp-tui-convs' bin on PATH, 860 tests, all pass.

## Notes

Agmente will not know `_relay/*` methods, so it sees conversations through
`session/list`. If it falls short, record what it lacks before deciding on
a client of our own.

## Kira's phone setup, for later

The code is deployed and relay.serve has run under launchd since
2026-10-22, so only the phone side is left. The README section
"Reaching relay from your phone" has the details.

- [ ] Put a token in the keychain:
  `security add-generic-password -a "$USER" -s relay-websocket -w "$(openssl rand -hex 32)"`
- [ ] Add `:serve {:websocket {:port 8765 :token-command ["security"
  "find-generic-password" "-s" "relay-websocket" "-w"]}}` to
  ~/Sync/relay/relay.edn.
- [ ] Restart the service: `launchctl bootout gui/$(id -u)/relay.serve`,
  then `launchctl bootstrap gui/$(id -u)
  ~/Library/LaunchAgents/relay.serve.plist`. Look for the listener line
  in ~/Library/Logs/relay/serve.log. Restarting ends live conversations.
- [ ] `tailscale serve --bg --https=8765 http://127.0.0.1:8765` (ntfy
  keeps 8443).
- [ ] In Agmente, add `wss://<the Mac's tailnet name>:8765` with the token.
- [ ] Try it out: list conversations, pick up one started in acp-tui, and
  answer an approval on the phone. Record what works and what doesn't in
  the two criteria above.

## Log

- 2026-10-25: Kira closed it as built. The phone setup steps and the
  two unticked criteria (Agmente, ntfy links) moved to #84, since only
  Kira's phone can check them.
