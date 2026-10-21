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
    AskTheClient; AGENTS.md). After Codex round 1, `bb test --agentloop
    ../agentloop/bin/agentloop` with acp-tui-convs' bin on PATH: 814
    tests, all pass, the two acp-tui pty tests included. `bb tck`: 43 pass,
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
- The socket is `~/.relay/run/<first 16 hex of sha256(absolute data dir)>.sock`,
  and the lock `~/.relay/run/<same>.lock`, whatever `--socket` says, so
  one service runs per data directory (changed in Codex round 1; it was
  per socket). `--socket` and `--run-dir` exist for tests;
  relay.serve/*run-dir* is bound away from home in-process.
- `--trace` in relay acp now records what the client and relay said to
  each other (in either mode), not relay and agentloop: that is what the
  client saw, and the trace test's counts still hold.
- The replay keeps the last 4 MB of updates per conversation.
- A session takes one prompt at a time: a prompt while one runs or waits
  is refused (Codex round 1). A waiting prompt is mirrored to the others
  when it starts, so the replay keeps it before its reply.
- session/cancel from any attached connection cancels the running turn,
  whoever started it, and drops that connection's waiting prompt: one
  person on several devices, and acp-tui's ctrl-c relies on it.
- `agentInfo.version` is "1" and session/list filters by cwd, for the ACP
  kit (ACP-INIT-004, ACP-LIST-002).
- ACP-SESSION-002 (two session/new calls get different ids) fails by
  design: a role has one live conversation and session/new attaches to
  it. scripts/tck.sh lists it in ALLOWED_FAILS and reports it rather
  than counting it. Kira may prefer to see the kit's verdict as is.
- relay chat runs `acp-tui [--open <role>] -- relay acp --approvals`. The
  two tests that drive a real acp-tui skip unless that acp-tui has
  `--open` (relay#65); with acp-tui-convs' build on PATH and at
  ../acp-tui they run and pass.
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

## Codex round 1

Six in-scope findings, all fixed, each with a test that failed before
the fix.

1. The replay cap used subvec, which kept every dropped update. Fix
   uryxmzzt: the buffer is a PersistentQueue. Test: hub-test
   `the-replay-keeps-only-what-it-shows`.
2. Stopping a conversation queued only an end of input, so an agent that
   stopped reading during a large prompt was never killed and
   `_relay/conversations/end` hung. Fix lnywpzmz: stop delivers the
   proxy's own stop, and the proxy closes stdin on its own thread, so a
   blocked write cannot hold up the kill. Test: serve-test
   `ending-a-conversation-stops-an-agent-that-stopped-reading` (1 MiB
   prompt to an agent that stops reading; ends within grace + margin).
3. session/new waiters were matched by position: a creator that left
   cost the next waiter its answer, and an agent that died while creating
   answered the creator twice. Fix uryxmzzt: the creator leaves the
   waiting list when its request goes to the agent, and is answered only
   under that id. Tests: hub-test
   `a-session-new-waiting-for-creation-is-answered-once-whoever-leaves`;
   the simulator's agents can now hold back initialize and session/new
   (:slow, :release), so leaving and dying during creation are in the
   properties.
4. session/cancel with a queued prompt left the running turn going. Fix
   uryxmzzt: cancel drops the connection's waiting prompt and cancels the
   running turn, whichever attached connection started it. Tests: hub-test
   `one-prompt-at-a-time-per-session`,
   `cancel-from-any-attached-client-stops-the-running-turn`.
5. No bounds on the event queue, conversation input or queued prompts.
   Fixes: one prompt per session, others refused (uryxmzzt; hub-test
   `one-prompt-at-a-time-per-session`, a thousand retries keep nothing);
   conversation input bounded at 64 MB unread, past which relay stops the
   conversation, the same sender as connections' output (lnywpzmz;
   serve-test `a-sender-gives-up-past-its-backlog`); the event queue holds
   1024 and producers wait, with the loop's own events on a side queue
   (lnywpzmz; serve-test `the-event-queue-holds-producers-back`).
6. The lock was per socket. Fix lnywpzmz: per data directory under the
   run directory. Test: serve-test `one-service-per-data-directory` (a
   second service on another --socket refuses).

`bb tck` after the fixes: 43 pass, 12 skipped as not advertised,
ACP-SESSION-002 failing by design, as before.

## Codex round 1 (acp-tui finding 4)

The bounded replay could drop the update that carried the session's
labels (agentloop's session_info_update with `_meta` agentloop/labels, or
any tool_call, tool_call_update, usage_update or permission request that
carries them) and keep the agent text after it. A client attaching then
showed that text as clean, with its links live.

Fix: the hub keeps, per conversation and outside the capped buffer, the
latest labels any agent message carried, and every replay starts with
them, after the truncation notice and before any other entry, as the
session_info_update agentloop sends when only the labels change. It
keeps the latest usage_update the same way.

Decided from acp-tui's wire (internal/wire): the session-level state it
reads is the labels (from those five places) and usage (context window
and cost). Usage is display state rather than needed to read the rest
safely, but it is cheap to keep and a replay that dropped it showed no
usage at all. The rest of a session_info_update (title) and config or
mode updates are not read by acp-tui, so they are not kept. The latest
labels, not the labels in force where the kept history begins, start the
replay: agentloop's labels only grow within a session, so earlier text
is shown with at least the labels it carried, the safe side.

Tests: hub-test
`a-replay-starts-with-the-latest-labels-even-when-the-cap-dropped-them`
(also a permission request's labels) and the property
`a-late-attacher-starts-with-the-latest-labels` (the simulator's agents
now send label updates, and a 400-character cap drops them); the
service and embedded properties check it too. All fail on the hub before
the fix.

## Codex round 2

Eight in-scope findings. Each fix has a test that failed before it.

1. The truncated replay lost the session's labels. Fixed in puolpqoy;
   see "Codex round 1 (acp-tui finding 4)" above.
2. serve deleted whatever was at the socket path. Fix uwwtpvsp: it takes
   over only a socket that no service answers on, refuses a file or a
   directory without touching it, and chmods only its own run directory
   (it used to chmod the --socket's parent too). Test: serve-test
   `serve-never-removes-what-is-not-its-socket`.
3. A client could reuse an outstanding request id, so the agent's late
   answer to the first answered the second. Fix zloktxvr: the hub tracks
   each connection's outstanding ids, refuses a reused one (-32600), and
   every answer to a client goes through `respond`, which frees its id;
   answers are still looked up by the hub's upstream id. Tests: hub-test
   `a-request-id-still-outstanding-cannot-be-reused`; cards-acp-test's
   fake agent now answers the id it was sent, so the regression test
   really exercises remapping.
4. The agent's `$/cancel_request` was forwarded with its own id, and a
   cancelled turn's permission requests stayed open. Fix zloktxvr: it goes
   to each client under that client's id and the request is settled; a
   client's `$/cancel_request` goes to the agent under relay's id; when a
   turn ends, is cancelled, or the conversation is ended, its permission
   requests are answered cancelled (as ACP asks of a client), which
   closes the proxy's record, and every client is told they are settled.
   Tests: hub-test `a-turn-that-ends-retires-its-permission-requests`,
   `a-client-cancelling-its-own-request-is-passed-on-under-relay-s-id`;
   the simulator's agents now cancel requests too.
5. session/new waiters were unbounded. Fix zloktxvr: one waiter per
   connection and role (-32600), and at most 256 requests outstanding per
   connection (-32000), which bounds every list the hub keeps for a
   client (waiters, enders, forwarded requests); uwwtpvsp caps the service
   at 64 connections. Tests: hub-test `waiting-for-a-session-is-bounded`,
   serve-test `the-service-takes-only-so-many-connections`.
6. An agent response with `"method": ""` was a response to the proxy and
   a request to the hub. Fix zloktxvr: `relay.acp/kind` is the one
   classification (request, notification, response), used by the proxy's
   readers (workspace/client-request-key, agent-response, acp/stray-answer?,
   answer-of, the observer) and the hub. Test: hub-test
   `a-response-with-an-empty-method-is-a-response`.
7. A card one client changed never reached another client subscribed
   through the same conversation. Fix zloktxvr: the hub turns a
   successful `_relay/cards/op` answer into `_relay/cards/changed` for
   the other subscribers through that conversation. Chosen over
   per-connection rev tracking in the hub so the proxy's per-conversation
   record stays the one source of what its subscribers were sent: the
   others had been sent everything the conversation had, so the op's card
   is exactly what they lack. Test: hub-test
   `a-card-changed-by-one-client-reaches-the-others`.
8. The lock and socket hashed the path as written. Fix uwwtpvsp: they
   hash the real path. `--socket` and `--run-dir` are documented as for
   relay's own tests in the CLI help and README. Test: serve-test
   `a-data-directory-is-known-by-its-real-path` (a symlink, and /tmp vs
   /private/tmp).

After the fixes: `bb test --agentloop ../agentloop/bin/agentloop` with
acp-tui-convs' bin on PATH, 825 tests, all pass; `bb tck` 43 pass, 12
skipped, ACP-SESSION-002 failing by design.

## Codex round 3

Five in-scope findings, fixed structurally in nponvvzz so the classes go
away rather than the instances. Rounds 1 to 3 kept finding edge cases in
the same two places: request bookkeeping spread over several maps, and
board delivery split between the hub and each proxy.

A. One request table (findings 1 and 4). Every client request the hub
   takes is one record (connection and id, conversation, upstream id,
   state) from the moment it is taken until it is answered; every lookup
   is a query on it, and :pending, :out, :waiting, :queue, :prompt,
   :enders and the connections' permission-id maps are gone. Answers go
   through `reply`, which is the only thing that frees an id, so a
   malformed line carrying an outstanding id gets no answer and frees
   nothing. A record whose connection went away stays, an orphan, until
   its answer comes and is then dropped. Records are bounded per
   conversation (256, orphans included) and per connection (256), so
   reconnecting cannot get past the bound. Tests: hub-test
   `an-id-stays-reserved-while-its-request-is-outstanding`,
   `orphans-are-bounded-per-conversation-across-reconnects`; the
   simulator now reuses outstanding ids and sends unreadable lines with
   them (`:reuse`, `:garble`), checks every reuse is refused and no
   answer is given twice, and the property
   `requests-stay-bounded-however-clients-come-and-go` (bound 4, agents
   holding answers back, clients leaving requests behind) checks the
   bound after every action. Breaking either fix makes them fail.
B. Cancel is per session (finding 2): session/cancel from any attached
   connection answers every waiting prompt cancelled, each to its own
   connection, and cancels the running turn. Test: hub-test
   `cancel-drops-every-waiting-prompt-in-the-session`.
C. The board is the hub's (finding 3). The hub holds the core state
   (`:log-changed` now carries it), which connections subscribe, and the
   rev of each card it sent each one; on every log change it sends each
   subscriber the cards it lacks (relay.workspace's pure functions).
   `_relay/cards` and `_relay/cards/op` are answered by the hub and update
   only that subscriber's record; an op's result comes back with the
   state after it. The proxy's board watcher, its remembered revs and its
   `:watch` hook are gone, and `_relay/cards*` no longer need a sessionId
   (one is still accepted). Tests: hub-test
   `the-board-is-the-hub-s-and-each-subscriber-gets-what-it-lacks` (the
   changer leaving before its answer, a second client subscribing before
   the hub read a change, nothing sent twice); cards-acp-test still
   passes end to end. The watcher-race test went with the watcher; the
   hub test covers the same race.
D. Log writes the hub depends on (finding 5): every submit whose outcome
   matters has a continuation in :tags. A turn end the log refused is
   tried again on every log change and on a :tick the shell sends every
   5 s; until it is recorded no other turn of the role starts, and the
   operator is told once, on stderr (a :warn effect) and through core's
   new `:conversation/turn-unrecorded` notification. A turn start the log
   refused refuses the prompt. An unattended-permission notice that could
   not be recorded is asked for again. Tests: hub-test
   `a-turn-end-relay-could-not-record-is-tried-until-it-is`,
   `a-turn-relay-could-not-record-does-not-run`; serve-test
   `a-turn-end-the-log-refused-is-recorded-once-it-can-be` (inbound/submit!
   throwing, then recovering, through the real engine).

Also found while doing C: the log watcher took its first stamp after the
caller read the state, so a write in between could be missed for good;
it now reads the state on its first look.

hub.clj went from 944 to 1059 lines: the board and the turn-end
continuations moved in, while the request bookkeeping shrank to one
table. `bb test --agentloop ../agentloop/bin/agentloop` with
acp-tui-convs' bin on PATH: 831 tests, all pass. `bb tck`: 43 pass, 12
skipped, ACP-SESSION-002 failing by design.

## Notes

- No env vars for wiring. The socket path comes from config.
- No resume of ended conversations (#62 decision). Reattaching to a live
  conversation is not a resume: the session never closed.

## Log
