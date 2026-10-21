# Run conversations in a relay service that outlives its clients

Part of #62. Needs #63.

## Context

A conversation now lives as long as the `relay acp` process its client
started. To keep a turn running after the client closes, and to let
another device pick the conversation up, something longer-lived has to own
it. Kira agreed on 2026-10-21 to an always-on service on the Mac.

## Acceptance criteria

The design brief (`.next/CONVERSATIONS.md`, sections 3 and 4) is the
contract this work followed. Commits: tossnmsx (the hub), yxqurkpz (the
service, the bridge, docs).

- [x] A relay service (launchd job; Kira installs it) owns running
  conversations. It starts one agentloop per conversation and keeps the
  proxy's hooks: permission gating, previews, displays, the board and the
  observer.
  - Receipt: yxqurkpz. `relay serve` (relay.serve/serve!) runs each
    conversation as relay.dispatch/run-conversation!, the old acp! body
    with every hook, with the hub as the proxy's client side.
    `setup/launchd/relay.serve.plist`, not installed. Tests: serve-test
    (all `^:volley/serial`).
- [x] Clients connect over a local socket. One connection carries every
  conversation as ACP sessions, with `session/new` taking the role.
  `relay acp` becomes a stdio bridge to the service, so `relay chat`, Zed
  and the existing traces keep working.
  - Receipt: yxqurkpz. Socket `~/.relay/run/<hash>.sock` (dir 0700, socket
    0600) or `--socket`; `relay acp [<role>]` bridges when a service
    listens, sending `_relay/connect` first, else runs the hub embedded.
    serve-test `two-clients-share-one-conversation`; acp-test,
    cards-acp-test, turns-acp-test, display-test, traces-test pass on the
    embedded path.
- [x] A client can detach and reattach. A running turn keeps going while
  nothing is attached. On attach, the client gets the conversation's
  history replayed and then live updates.
  - Receipt: serve-test `a-turn-outlives-its-client-and-a-new-client-gets-the-history`;
    hub-test `a-second-session-new-attaches-with-the-replay`,
    `session-load-replays-then-answers`, `a-replay-past-its-cap-starts-with-a-notice`,
    and the replay invariant in `the-hub-keeps-its-promises-as-a-service`.
- [x] Several clients may be attached at once (one person, several
  devices). Each sees every update. The first answer to a permission
  request wins; the others see it resolved.
  - Receipt: serve-test `a-permission-answered-on-one-device-is-settled-on-the-other`;
    hub-test `a-permission-request-goes-to-every-approving-client-and-the-first-answer-wins`
    and the settling invariant in the properties.
- [x] A call held for approval while no client is attached reaches Kira
  through ntfy, as held calls do today.
  - Receipt: core `:permission/unattended` (tossnmsx) makes a
    `permission-waiting` notification, pushed by relay.inbound like any
    other. core-test `an-unattended-permission-request-tells-the-operator`;
    serve-test `a-permission-with-nobody-to-answer-waits-and-tells-the-operator`.
- [x] Kira ends a conversation explicitly, or by starting a new one with the
  role. A conversation is never ended because its client went away.
  - Receipt: `_relay/conversations/end`; serve-test
    `the-operator-ends-a-conversation`; hub-test
    `ending-a-conversation-answers-everything-owed`,
    `embedded-ends-every-conversation-with-its-connection` (service keeps
    it). Starting a new one goes through ending the old one first: the
    brief's session/new attaches to a live conversation.
- [x] Restarting the service (deploy, crash) leaves a state Kira can
  recover: conversations it held end and are recorded as interrupted.
  - Receipt: serve-test `a-restart-ends-what-the-old-service-held` (SIGTERM
    ends each conversation and a running turn as "ended"; after a kill -9
    the next service reaps the lost one) and `one-service-per-data-directory`.
- [x] Codex review loop until a round has no in-scope defect.
  - Receipt: not run by me. The coordinator runs the loop after this
    review, as they asked.
- [x] README, resources/AGENTS.md, spec updated. bb test passes.
  - Receipt: yxqurkpz (README: relay chat, ACP clients, The relay service;
    spec: ConversationService, TellTheOperatorAPermissionWaits,
    AskTheClient; AGENTS.md). `bb test --agentloop <agentloop main build>`:
    807 tests, 805 pass; the two that do not run need an acp-tui with
    `--open` (relay#65) and say so. `bb tck` with that agentloop: 43 pass,
    12 skipped as not advertised, and ACP-SESSION-002 fails by design (see
    below).

## Judgment calls

- The hub is pure (relay.hub/step); relay.serve is the shell: one loop
  thread owns the hub, every other thread only enqueues events, and a
  submit is carried out on the loop with its result stepped at once, so
  an accepted turn's prompt goes on before anything else. Writes go
  through a queue per connection (closed past 64 MB unread) and per
  conversation, so a client that stops reading holds up nobody.
- Effect vocabulary: :write, :send, :submit, :start, :stop. "Notify" is a
  :submit of `:permission/unattended`, so core decides the notification.
- "First valid answer wins": an answer from an approving connection that
  picks no offered option is passed to the proxy, which refuses it, and
  settles the request as reject_once, as relay acp did before. Treating it
  as no answer would let a confused client leave the turn hanging. A line
  that cannot be read, carrying the id of a request the connection holds,
  becomes an unreadable line with the agent's id, which the proxy refuses
  as "client-error", as before. An answer to nothing refuses what that
  connection holds open, as before.
- The hub's ids for its requests to a client are strings `relay-<n>`, so
  a late answer to one already settled (acp-tui sends one) is told apart
  from a client's own ids and dropped without an error.
- `session/close` detaches and, as ACP asks, cancels a turn the closing
  connection started; turns other connections started go on. Without it
  ACP-CLOSE-002 failed (the prompt never resolved).
- `_relay/replayed` also follows the answer to the session/new that
  created the conversation, so every session/new ends with it.
- A request with no sessionId goes to the only conversation the
  connection is attached to, else to its own role's (relay acp <role>),
  even before that has a session. The pane's and board's methods, and
  any other request (a plain ACP client's), keep working on relay acp
  <role> that way; cards-acp-test needed only a different "the agent is
  running" check (the hub passes a session/update only to attached
  clients).
- `_relay/cards/changed` goes to every connection that asked for the
  board through that conversation, attached or not.
- `_relay/trust` from a connection that cannot approve is refused in the
  hub, and `_relay/items` answers to it carry canTrust false: in service
  mode the proxy itself always runs with approvals, since the hub decides
  who may answer.
- An agent's request other than a permission request is answered -32601:
  the hub advertises no client capabilities to the agent.
- The agent is initialized with the initialize params of the connection
  whose session/new opened the conversation, so a client's capabilities
  (session notices, say) reach agentloop as before.
- `relay acp <role>` with no service opens the role's conversation before
  any traffic, so a refusal still exits 1 with nothing on stdout.
- The socket is `~/.relay/run/<first 16 hex of sha256(absolute data dir)>.sock`;
  tests bind relay.serve/*run-dir* away from home. The lock is a file
  beside the socket, so the one-service rule holds per socket path; a
  service started with another `--socket` is not stopped by it. --socket
  exists for tests.
- `--trace` in relay acp now records what the client and relay said to
  each other (in either mode), not relay and agentloop: that is what the
  client saw, and the trace test's counts still hold.
- The replay keeps the last 4 MB of updates per conversation.
- A prompt waits in a queue per conversation; more than one may wait, and
  they run in order. A prompt is mirrored to the others when it starts,
  so the replay keeps it before its reply.
- `agentInfo.version` is "1" and session/list filters by cwd, for the ACP
  kit (ACP-INIT-004, ACP-LIST-002).
- ACP-SESSION-002 (two session/new calls get different ids) fails by
  design: a role has one live conversation and session/new attaches to
  it. scripts/tck.sh lists it in ALLOWED_FAILS and reports it rather
  than counting it. Kira may prefer to see the kit's verdict as is.
- relay chat runs `acp-tui [--open <role>] -- relay acp --approvals`. The
  two tests that drive a real acp-tui skip unless that acp-tui has
  `--open` (relay#65). A build of acp-tui-convs as of this work answered
  initialize and then sent nothing in a pty, so the full path through
  acp-tui is not yet checked.
- Deploy: unchanged, as asked. It waits for every running wake, and a
  service's conversations are running wakes, so the README says to stop
  the service before `bb deploy` and start it after, which ends the live
  conversations. A deploy that restarts the service itself is a
  follow-up.
- On SIGTERM the service ends each conversation (the proxy closes the
  agent's stdin and kills it after 5 seconds) and waits up to 15 seconds
  for the wakes to be recorded; the plist's ExitTimeOut is 30.
- Commit tossnmsx also carries a change to
  `.issues/65-...md` that was in this working copy, not mine; moving it
  out was refused by the permission system, so it is left as it is.

## Notes

- No env vars for wiring. The socket path comes from config.
- No resume of ended conversations (#62 decision). Reattaching to a live
  conversation is not a resume: the session never closed.

## Log
