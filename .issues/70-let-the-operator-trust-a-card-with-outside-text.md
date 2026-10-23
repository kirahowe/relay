# Let the operator trust a card with outside text

## Context

Cards carry labels per text element. A card is contaminated when a session
that had read outside text wrote some of its text. Until now the only way
to clear that was rewriting the text by hand. The operator can now vouch for
a whole card, as for docs, memos and facts. Design approved by Kira: whole
card, at the rev the operator saw, operator only, never clears private,
refused when the card changed since.

Built by extending `:label/trust` with kind `card`; no new mechanism.

## Acceptance criteria

- [x] Only the operator can trust a card; a role's `:label/trust` is refused.
  - Receipt: `only-the-operator-trusts-a-card` (cards_test.clj)
- [x] A clean card is refused as not contaminated.
  - Receipt: `a-clean-card-is-not-trusted`
- [x] A stale rev or a wrong digest trusts nothing ("Nothing was trusted; look again.").
  - Receipt: `a-card-that-changed-since-it-was-read-trusts-nothing`
- [x] After trust every element lacks contaminated, private stays, text and element revs are unchanged, the card's rev moves and `changed-since` reports it.
  - Receipt: `trusting-a-card-clears-contaminated-from-every-element`, property `trusted-cards-lose-only-contaminated`
- [x] A later role edit can re-contaminate an element; the old trust args are then refused.
  - Receipt: `trusting-a-card-clears-contaminated-from-every-element`
- [x] The wire form has `sha256` only when the card is contaminated.
  - Receipt: `the-wire-form-has-a-digest-only-when-the-card-is-contaminated`
- [x] `relay trust card <id>` works through the generic path.
  - Receipt: `relay-trust-card-works-on-the-command-line`
- [x] `_relay/cards/trust`: refused without approvals; unknown params refused; stale digest refused; success answers `{trusted, card}`, logs `via chat`, and a subscribed client is sent `_relay/cards/changed`.
  - Receipt: `_relay-cards-trust-over-relay-acp`, `an-unknown-trust-parameter-is-refused` (cards_acp_test.clj)
- [x] README and spec/relay.allium updated. resources/AGENTS.md does not describe trusting to roles, so unchanged.
  - Receipt: uvvwvwwy changes README.md (trust kinds, `_relay/cards/trust`, the wire `sha256`) and spec/relay.allium.
- [x] An adversarial Codex review finds nothing in scope.
  - Receipt: round 1, 2026-10-23, "No in-scope findings" (see Log).

## Plan

Done in two commits: core+cards+cli path, then wire/hub/docs.

## Log

- Digest text is `cards/text-form` (plain, with ids, ticks, cell states and the
  header's rev). It is deterministic and what `relay trust` shows. Consequence:
  any change to the card (even a tick) changes rev and so refuses the trust; that
  is intended ("the card changed since you looked").
- `:version` is the card's `:rev`; the labels judged are `cards/text-labels`, not
  `core/card-labels`, so private never counts toward "contaminated" anyway.
- `evolve* label/trusted` for card sets the card's `:rev` to the event seq and
  leaves element revs alone, so `seen` stays meaningful. `decide` only refuses a
  `seen` above the card rev, and the new rev is higher, so old `seen` values keep
  working. prompt-cards reads only titles and labels, unaffected.
- The `sha256` rides on the wire form in `workspace/card-wire` (not in
  `cards/wire`), because relay.cards must not require relay.core where sha256
  lives.
- `_relay/cards/trust` accepts `sessionId` (acp-tui sends it) and requires
  `card`, `rev` (integers) and `sha256` (64 hex). The approvals check comes before
  param validation, as for `_relay/trust`. The refusal on missing approvals tells
  the client to run `relay trust card <id>`.
- The trust answer reuses the card-op continuation with a `:trust` flag, so the
  requesting connection is marked as having been sent the new rev and is not sent
  the same card again; other subscribers get `_relay/cards/changed`.
- The acp test checks the push by submitting the trust from another process, since
  relay allows one conversation per role and so one ACP client per role.
- A role with the `card` the operator trusted can still be re-contaminated by a
  later contaminated edit; trust covers the text as it stood.
- 2026-10-23: Codex review round 1 (read-only, threat model given) over
  ypqwnonz..yovxoswy: "No in-scope findings." It ran 42 in-memory checks
  across checklist, tracker and note cards: a role or public role
  refused, a stale rev or digest refused, private kept, later outside
  text re-contaminates and refuses the old digest, a client without
  approvals refused, and other subscribers sent the clean card. A clean
  round, so the review stops here.
- acp-tui's side (t on the board, a confirm with the arm delay, the rev
  and digest sent back, the question closed if the card changes) is in
  the acp-tui workspace .next/acp-tui-boardfix, commit cc94f035.
  scripts/card_fixtures.clj now records the trust exchanges for its
  tests.
