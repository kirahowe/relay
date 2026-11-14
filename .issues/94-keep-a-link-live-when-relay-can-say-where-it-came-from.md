# Keep a link live when relay can say where it came from

## Context

relay breaks every link in text an outsider may have shaped
(`relay.text/for-operator`). Everything a scout reports drew on the web,
and the steward that passes it on has read the scout's memo, so every link
in a grant digest reaches Kira broken. Finding links is what scouts are
for.

Kira, 2026-11-14:

- "Trusted" means where the URL came from, not which host it points at.
- Scouts must be able to share links.
- "I need the ability to say 'yes render that, I'm calling it safe'."

The house rework removed `relay trust`, and card trust (relay#70) with it,
so nothing lets Kira call anything safe today. This brings back a narrow
piece of it: it is optional, and it covers one link at a time.

## Design

relay keeps one set of live links in its state. A link shown to Kira stays
whole when its text is clean, when relay built it, or when it is in that
set, character for character. Two things put a link in the set:

1. A scout's memo. When a scout sends a memo, relay records the http and
   https addresses in it that an earlier `egress` result of the same
   session showed, verbatim. This is the fetch rule's own test
   (`relay.shown/refusal`): Kira may open what the scout could have
   fetched. An address the scout built stays broken, since it could carry
   what the steward told it.
2. Kira. She gives the address, on the command line or from a client, and
   relay records it as hers.

One set for all of relay, and no mark on each text, because the steward
rewrites what the scout found, so a link has to be recognized wherever it
turns up. A role that writes a link from the set adds nothing to it. Which
link it picks is a channel of low bandwidth, outside the threat model.

A link, and not a whole card or message as in relay#70, because the link
is the exact thing vouched for. There is no rev or digest to go stale, and
a link Kira called safe once is live in tomorrow's digest too.

A missed place stays safe: any text relay shows without the set still has
its links broken.

## Acceptance criteria

- [ ] A scout's memo records the addresses in it that its session was
  shown, and no other.
- [ ] An address the scout built, one changed by a character, and one with
  a scheme other than http or https are not recorded.
- [ ] A message and a card that quote a recorded link show it whole in the
  client's wire form, in mail and in a push. Every other link in the same
  text is still broken.
- [ ] A live link is whole only as a whole word: text run on after it
  breaks it.
- [ ] Kira can add a link from the command line, pasted whole or in its
  broken form. A role cannot.
- [ ] relay refuses to add an address with hidden characters, with a
  scheme other than http or https, or with a host that mixes scripts.
- [ ] Property: text with no live link in it is shown exactly as before.
  Property: no link outside the set is ever shown whole in contaminated
  text.
- [ ] README, resources/AGENTS.md, spec/relay.allium, docs/architecture.md
  and docs/client-protocol.md say what changed.
- [ ] `bb verify` passes.
- [ ] An adversarial Codex review finds nothing in scope.

## Plan

1. Pure core in `relay.text` and `relay.shown`: find the links in a text,
   and break all but the live ones.
2. `memo/send` by a scout records its shown links. A new event records
   Kira's. The fold keeps the set.
3. Pass the set to messages, cards and the ready items. The command line's
   own views follow.
4. Docs, verify, review.

## Log
