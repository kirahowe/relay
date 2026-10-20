# Let roles keep cards on a board that every client renders

## Context

The spec is /Users/kira/code/projects/.cards/BRIEF.md ("relay core (#54)"),
approved by Kira on 2026-10-20. The acp-tui board is #55; `relay serve` is
#56 and parked.

## Acceptance criteria

- [x] relay.cards: kinds, op checks, apply, progress, text form, wire form, pure
- [x] card/added and card/changed through relay.inbound (decide*/evolve*)
- [x] labels per text element; reads withhold contaminated cards
- [x] tools cards, card_add, card_op for roles that are not public
- [x] refs: [[card:N]]
- [x] wake and conversation prompts list the active cards within their labels
- [x] CLI: relay cards, relay card <id>, relay card <id> <op>, relay card add
- [x] relay acp: _relay/cards, _relay/cards/op, _relay/cards/changed; agent _relay/ lines dropped
- [x] README, resources/AGENTS.md, spec/relay.allium
- [x] tests from the brief; bb test passes

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
  does). The card_op tool adds private to them, since saying an item is
  already ticked says something about a private card.
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
- card_add answers "Added card N." with the text form built from its own
  card/added event, not from a fresh read, so it shows only the caller's
  text and carries only the session's labels.
- A public role gets no board section in its prompt (prompt-cards is nil),
  and the `cards` tool answers "You have no tool called cards." to a
  public reader, as `ledger` does. The tools carry `:grant :private`,
  held by every role that is not public.
- A conversation has no wake prompt, so its board section goes at the end
  of its system prompt, after the conversation's start time, filtered by
  the same labels.
- The prompt section counts a card in when its title's labels plus
  private are within the session's labels; item labels do not keep it
  out, since only the title and the counts are shown.
- relay card ops from the command line are submitted with no labels and
  never public, whatever --untrusted or --public say (decision 3).
  --item, --row and --column collect repeated values (for `card add`);
  an op takes exactly one number.
- relay acp: an extension answer may now be a function, which proxy!
  calls with its locked writer to the client. The board's answers and
  the watcher's notifications are written holding the conversation, so a
  client never gets an older copy of a card after a newer one. An op's
  answer marks that card as sent, so the watcher does not send it again.
- `_relay/cards/op` answers `{"card", "changed"}`; bad params are -32602,
  relay's refusal -32000 with its message.
- relay acp prints a line to its stderr when it drops an agent line with
  a `_relay/` method.
- The watcher polls the last log segment's path, size and modified time
  every second, and replays only when it changed and the client has
  subscribed. A failed look is reported on stderr and tried again.
- Pentest: the S6 table in pentest display.clj gets two card_add writes by
  the contaminated researcher (a checklist with a link, a zero-width space
  and RTLO; a note with fake approve lines) and the `relay cards` view,
  plus checks on the wire form. Ran it with an agentloop built from
  agentloop's main into the scratch directory: passes.

## Log

- 2026-10-20: built relay.cards, core, tools, refs, prompts, CLI, relay
  acp methods and docs. The workspace has no sibling agentloop or acp-tui
  build, so bb test ran with `--agentloop` pointing at a build of
  agentloop main made in a scratch directory. The one test that needs
  acp-tui (display-test's pane on a pty) cannot run here.
