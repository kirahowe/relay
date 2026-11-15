# Keys held while connecting are replayed onto a restored card and act on it

## Context

relay-tui. Sent by the relay#100 session on 2026-11-15, from relay#100's
fourth review round. It is how the board behaves on trunk for every card
kind, and relay#100's chain does not fix it. The reviewer did not count
it as a defect, because it needs relay to answer out of order.

The reviewer's text:

> Keys held while the screen says "connecting…" are replayed onto the
> restored surface card and tick a box that was never on screen (hub
> mode). Where: internal/ui/connect.go:152 (replay), and
> internal/core/authority.go:76 (only archive-yes and end-yes are held
> back from a replayed key; PaneMark is not). Sequence: saved view {Hub
> focus dev, PaneFocus, Card 10, Detail, Line 2}. Start; press j, x
> while "connecting…" shows. Then HubConnected, CardsLoaded with card 10
> as it now is (Passport added above Tent), RosterUpdate. What happens:
> the card is restored open on its title, j lands on the first box, x
> sends `input /items/0/done=true seen 12`. The single-agent variant and
> a live key before the board both send nothing. Scope: borderline, not
> counted. It needs the board's answer to reach the client before the
> first roster. The client asks for the roster first (agent.go:112) and
> the board third in a queue, so today's relay would have to answer out
> of order. The existing tests TestAReplayedKeyAuthorizesNothing and
> TestHeldXAndYArchiveNothing use that same order, so the author already
> treats it as possible for archive. The same replay ticks a checklist
> item on the base. Confidence: high for the event sequence, low that
> the order occurs.

The relay#93 session read the places the reviewer names on relay-tui's
`integration` (13c740c5) before filing. The replay loop is in
internal/ui/connect.go. `authorityOf` in internal/core/authority.go
holds back only a yes on the archive surface and a yes to ending a
conversation. Both tests are in internal/ui/restore_keys_test.go. I did
not run the sequence.

## Acceptance criteria

The first is the relay#100 session's suggestion.

- [ ] A replayed x, space or enter changes nothing on a card, as a
  replayed y archives nothing today. A test replays j and x over a
  restored open card and no op goes out.
- [ ] `make check` passes.

## Plan

## Log

