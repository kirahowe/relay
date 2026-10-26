# Refuse a hung-up or wrong-id ACP answer as relay again

## Context

Found on 2026-10-24 while verifying relay#64 for review. Two pentests in
`pentest/relay/pentest/scenarios/acp.clj` fail on main:

- `s5-acp-disconnect`: a client hangs up while a permission request is
  open. The call is still refused (`reject_once`, nothing posted), but
  `permission/answered` records `:by "client"` when the client never
  answered, and the operator gets no "went away" push.
- `s5-acp-wrong-request-id`: a client answers under the wrong id. The
  turn no longer ends; the prompt gets no result within 90 s. The
  refusal is also recorded `:by "client"`.

Both pass on tossnmsx, the parent of the hub work, and fail on yxqurkpz
("Run conversations in relay serve, with relay acp as a bridge") and on
every later hub commit, main included. Nothing leaks: the post never goes
out and no answer is recorded as allow. What broke is what the log says
and what the operator is told.

A likely cause, not yet confirmed: the proxy now sees the hub as its
client, so a refusal the hub makes (`refuse-perm` in relay.hub) reaches
dispatch's `from-client` as a client answer, recorded `:by "client"`.
The old proxy's stray-answer path refused every open request; in the hub
a wrong-id answer may leave the request open, waiting for another device
to answer (first answer wins).

#64's receipts list `bb test` and `bb tck` but never `bb pentest`, so
the hub work never saw these two scenarios.

## Acceptance criteria

- [ ] `bb pentest s5-acp-disconnect s5-acp-wrong-request-id` pass on the
      embedded path, through the bridge, or both.
- [ ] A refusal relay makes is recorded `:by "relay"` with its reason,
      however many hops sit between the client and the agent.
- [ ] A client that hangs up mid-request leaves the operator told, as
      before the hub.
- [ ] A wrong-id answer cannot leave a turn hanging. If, now that several
      clients can share a conversation, the request should stay open for
      another device, Kira decides that (it changes when a call is held),
      and the scenario changes with the decision.
- [ ] Hub-test or serve-test cases that would have caught both.
- [ ] Codex review loop, since this touches the operator's approvals.

## Plan

## Log

## Dropped (2026-10-26)

Obsolete since the house: relay no longer asks for permission. It never
sends session/request_permission (pentest/relay/pentest/acp.clj checks
that), and both scenarios went with the permission machinery. All 30
pentests pass on main. relay#76 and relay#85 were the same failure.
