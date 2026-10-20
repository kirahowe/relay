# Let roles keep cards on a board that every client renders

## Context

The spec is /Users/kira/code/projects/.cards/BRIEF.md ("relay core (#54)"),
approved by Kira on 2026-10-20. The acp-tui board is #55; `relay serve` is
#56 and parked.

## Acceptance criteria

- [ ] relay.cards: kinds, op checks, apply, progress, text form, wire form, pure
- [ ] card/added and card/changed through relay.inbound (decide*/evolve*)
- [ ] labels per text element; reads withhold contaminated cards
- [ ] tools cards, card_add, card_op for roles that are not public
- [ ] refs: [[card:N]]
- [ ] wake and conversation prompts list the active cards within their labels
- [ ] CLI: relay cards, relay card <id>, relay card <id> <op>, relay card add
- [ ] relay acp: _relay/cards, _relay/cards/op, _relay/cards/changed; agent _relay/ lines dropped
- [ ] README, resources/AGENTS.md, spec/relay.allium
- [ ] tests from the brief; bb test passes

## Plan

1. relay.cards and core decide/evolve, with property tests.
2. Tools, refs, prompts.
3. CLI.
4. relay acp methods, the watcher and the agent-line filter.
5. Docs and spec.

## Judgment calls

- Element ids: rows and columns of a tracker have counters of their own,
  as the brief's example shows (row 5 beside columns 1 to 4). The event's
  `next` is a map: `{item n}` for a checklist, `{row n, column m}` for a
  tracker, `{}` for a note. A new element's id is written into the op, and
  evolve only moves the counter past it.
- `seen` is left out of the decided op the event records: it is a check
  decide makes, not part of the change.
- A text element's `rev` is the seq of the event that last wrote its text.
  A tick or a cell state moves the card's `rev` but not the element's, so a
  tick never makes a pending edit stale.
- "Already" answers: an op that changes nothing gets `{:unchanged message
  :labels}` from relay.cards/decide, and core appends nothing. The labels
  are those of the text the message says something about ("Item 3 already
  says that." carries item 3's labels, as doc_put's "already says that"
  does). Ticks and cells add none.
- archive on an archived card is refused (the brief: any op but restore on
  an archived card is refused); restore on an active card is an unchanged
  answer. Restore is refused when 30 cards are active.
- Line breaks refused in one-line text: \n, \r, VT, FF, NEL, U+2028 and
  U+2029. Text is trimmed first, so a trailing newline is simply trimmed.
- A kind given contents of another kind (a checklist with `text`) is
  refused, so a model learns what each kind takes.
- The dispatcher keeps no cards: only the operator and roles do.
- A note's text is kept as given (not trimmed), up to 8000 characters.
- A note has no progress; its wire form leaves `progress` out.
- relay.cards writes the ref as `[[card:N]]` itself rather than through
  relay.refs, which requires relay.core and would make a cycle.
- relay.cards refuses with an ex-info carrying `:relay/rejected`, the same
  shape relay.core/reject! throws, so core and the tools treat its refusals
  like any other. None quotes card text, so none carries labels.

## Log
