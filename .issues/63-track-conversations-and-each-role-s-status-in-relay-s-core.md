# Track conversations and each role's status in relay's core

Part of #62. Read its decisions first.

## Context

Every client needs the same two lists, built by relay rather than worked
out by each client:

- Roles: the active roles with their titles (`core/active-role-ids` and
  `render/roles` already make this for `relay roles`) and a status for
  each: idle, working, waiting on Kira's approval, or has a reply Kira has
  not seen.
- Conversations: one per role at most, with an id, role, title, status,
  start time, last activity and the ACP sessions it holds.

No ACP method exposes roles today. The ACP agent is fixed to one role.

## Acceptance criteria

The design brief (`.next/CONVERSATIONS.md`, section 2) is the contract
this work followed; where it narrowed a criterion below, the receipt
says so.

- [x] The log records a conversation's lifetime (opened, ended), which turns
  run in it, and when Kira last saw it. Decisions are pure (decide*/evolve*);
  writes go through relay.inbound.
  - Receipt: sszwwlsm. `wake/started` and `wake/ended` with `:chat`
    (unchanged) bound the conversation; new commands
    `:conversation/turn-start`, `:conversation/turn-end` and
    `:conversation/seen` emit `conversation/turn-started`,
    `conversation/turn-ended` and `conversation/seen`. Tests:
    core-test `a-turn-is-numbered-recorded-and-refused-when-it-cannot-run`,
    `the-end-of-a-conversation-ends-its-turn`,
    `seen-says-how-far-the-operator-has-read`.
- [x] A conversation holds its role only while a turn runs (the #62
  decision). The dispatcher can wake a role whose conversation is idle; a
  turn cannot start while a wake of that role runs. spec/relay.allium
  OpenConversation and RunWake say so.
  - Receipt: sszwwlsm (core), kkxzqllx (spec: StartTurn, EndTurn,
    SeeConversation, is_busy/is_open, OneTurnAtATime). Tests: core-test
    `a-conversation-holds-its-role-only-during-a-turn`,
    `a-conversation-may-open-while-a-wake-works-but-only-one-at-a-time`,
    and the model-based `the-rules-hold-under-any-sequence-of-commands`
    with turn-start/turn-end/seen steps and new invariants (one working
    wake, one conversation, no turn over a wake and no wake over a turn,
    turns count up); `bb soak 3000` passed.
- [x] A pure function gives each role's status, and one gives the
  conversation list. Property tests cover the state machine.
  - Receipt: sszwwlsm. `relay.roster/roster` gives each active role
    with its open conversation; with one conversation per role that is
    also the conversation list, so there is no second function (judgment
    call below). Property tests: roster-test
    `the-roster-is-total-and-follows-the-log` (total over reachable
    states; busy iff a working wake or a running turn; unread iff a turn
    ended after the last seen; JSON round trip), plus the model test.
- [x] The wire forms of both lists are viewer-agnostic data, like the cards
  wire form, and carry their labels.
  - Receipt: sszwwlsm. `relay.roster/wire-role` and `relay.roster/wire`
    give the brief's section 4 Role shape with the role's labels; roster-test
    `the-roster-says-what-each-role-is-doing`. `relay roles` renders from
    the roster (render-test `relay-roles-shows-what-each-role-is-doing`).
- [x] `_relay/roles` and `_relay/conversations` work in today's `relay acp`,
  with `.../changed` notifications.
  - Receipt: moved to #64 by the brief, which puts roster subscriptions
    in the hub; #63 delivers the data and its wire form, and the minimal
    turn gating today's `relay acp` needs (wpqpzxoz; turns-acp-test
    `a-prompt-is-a-turn-and-waits-for-scheduled-work`). See judgment
    calls.
- [x] README, resources/AGENTS.md and the spec updated. bb test passes.
  - Receipt: kkxzqllx. `bb test --agentloop <build of agentloop main>`:
    776 tests, 775 pass. The one left is display-test
    `the-pane-in-relay-chat-shows-a-display-and-trusts-it`, which needs
    `../acp-tui/bin/acp-tui` and reports itself skipped in this
    workspace; with an acp-tui build of main linked there for one run,
    it and all of acp-test passed (46 tests).

## Open questions

- Is a conversation still one wake record with `:chat true`, or a new
  entity whose turns are short wakes? **Answered: one wake.** The brief
  settled it, and it keeps every wake-keyed record as it is: labels,
  permissions, displays, cost, traces and lost-wake reaping all still
  key on the wake. Turns are events within it, so cost and sessions are
  still recorded once, at the wake's end, and a lost conversation is
  still reaped by the tick, which now ends its running turn too.
- Conversation titles. **Not needed now.** Each role has at most one
  conversation, and every client shows the role's title next to it (the
  roster carries it), so a conversation needs no title of its own. That
  also avoids a model-made title, which would be turn text needing
  labels. Revisit if a role ever gets several conversations.

## Judgment calls

- Predicates, in relay.core: `working-wake` (the role's running wake that
  is not a conversation), `open-conversation`, `in-turn?`, `busy?`
  (a working wake, or a conversation turn running) and `open?` (any
  running wake). `running?` is gone. `running-wake` stays as "the wake a
  role's call is taken to come from when it names none": the working
  wake, else the conversation. Nothing in src uses it now; the tests use
  it about a hundred times as the role's session, and with both open the
  working wake is the right answer there.
- Call sites:
  - `roles-to-wake`: `busy?`. An idle conversation must not keep the
    dispatcher away, which is the point of #62's decision.
  - `wake/start`: a conversation is refused `:busy` only when the role
    has one open; a dispatched or by-hand wake when `busy?`. A wake by
    hand (`relay wake`) is treated as a dispatched one: it works through
    memos and the role takes one turn at a time.
  - `role/remove`: `open?`. Removing a role under an idle conversation
    would leave a process running as a removed role.
  - `role/fresh`: `open?`. Ending the session under an idle conversation
    that may still use the old tool definition would accept it unseen.
  - `trip-guards`: `busy?`. With an idle conversation the dispatcher
    would wake the role but for the guard, so the guard is what stops it
    and the operator should hear about it.
  - `kept-memo-ids`: `working-wake`, as the brief says. A conversation
    carries no held session on, so every held session's memos stay kept
    for it.
  - render status (relay status): shows `[working]`, `[conversation
    open]` or `[in a conversation turn]` instead of `[running]`.
  - `deploy/busy-wakes`: unchanged, every running wake. An open
    conversation's `relay acp` runs the code from before a deploy, which
    could not read an event the new code writes (evolve throws on an
    unknown type). #64's service changes this, since a deploy restarts
    it.
  - Found by grep beyond the brief's list: `relay.week`'s walk kept one
    running wake per role, so a conversation ending while a wake worked
    lost that wake's closes and journal entry. It now tracks the working
    wake and the conversation apart, and attributes a role's own event to
    the working wake first. Test: week-test
    `a-conversation-ending-mid-wake-leaves-the-wake-its-work` (fails on
    the old walk). The model test's `:wake`, `:burst` and `:report`
    steps use `working-wake` for the same reason.
- Turn rules: only the operator starts a turn and says what was seen;
  only the dispatcher ends one. `turn-start` is refused `:not-found` for
  a wake that is not a conversation, `:invalid` when the conversation
  has ended, is in a turn, or the session is not an ACP session id
  (1 to 128 of `[A-Za-z0-9._:-]`, which covers agentloop's UUIDs and
  keeps client text out of the log), and `:busy` when a wake works.
  `turn-end` is total for any wake: a turn not running records nothing.
  `seen` takes a turn from 1 to the turns started, a running one
  included, since a client marks what is on screen as it streams, and
  records nothing unless it is past what was seen.
- Stop reasons are kept only from a fixed set (ACP's five, plus `error`,
  `ended` and `other`), so no agent text reaches the log through them.
- `wake/end` of a conversation during a turn writes
  `conversation/turn-ended` with stop reason `ended` before `wake/ended`,
  for every path that ends a wake (relay acp, the tick's lost-wake
  reaping).
- Roster: `(roster state config now)` as the brief says, though no field
  depends on config or now yet; they are there so one can without
  changing callers. `:labels` are the role's id and title labels only,
  not its instructions', which the roster does not show; `relay roles`
  adds the reports-to id's labels for its own line. The conversation's
  `:session` is the last turn's session, nil before the first turn. The
  wire form leaves the wake id out, as section 4's Role has none.
- relay acp gating: a hook on the client's `session/prompt` (beside the
  extension hook) submits `:conversation/turn-start` with the prompt's
  sessionId. Any refusal, busy or otherwise, answers the prompt with a
  JSON-RPC error (-32000, relay's message) and the agent never sees it; a
  prompt whose id relay cannot key is refused (-32600). proxy! gained an
  `:on-response` hook, called for an agent response that settles a
  request the client sent; when it answers a gated prompt, relay submits
  `:conversation/turn-end` before the client can read the answer, so a
  client that sends its next prompt at once never finds the turn
  running. No queueing: #64 replaces this.
- `relay acp` now opens while a wake works (the first turn waits), so
  acp-test's busy-role test checks a second conversation instead.
- No Codex review loop: the change touches neither labels, holds, tools,
  the network nor approvals. It changes when the dispatcher may wake a
  role, which the model-based property test covers.

## Log
