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
- Review fixes, 2026-10-20. The text form's header now ends with the
  card's rev (`(tracker, 6 of 24 done, rev 1842)`). Roles and the CLI
  only see the text form, so without it they had no way to give `seen`
  and the stale-edit check never applied to them. I kept `seen` rather
  than dropping it from the docs, because dropping it loses that check
  for roles. This changes the brief's example header by one field.
- relay acp drops an agent line by parsing it, not by looking for the
  substring `_relay/`. Any line that starts with `{` or `[` is parsed,
  so an escaped method (`\u005frelay/...`) or a batch array holding one
  is dropped too. Cheshire parses a top-level array lazily, so the walk
  stays inside the try: an unclosed `[0` otherwise threw from the reader
  thread and broke the byte-exact pass-through (P6-2 caught it). Client
  lines still use the substring check: what the operator sends is the
  operator's, and an escaped method from them only reaches agentloop.
- Second review, 2026-10-20: the board watcher took the log stamp
  outside the conversation's lock and kept it while the client was not
  subscribed. A write landing between _relay/cards reading the state and
  subscribing could then count as seen, and its card waited for a later
  write. The watcher now keeps no stamp until the client subscribes, and
  takes it holding the lock, before it reads the state. The test plays
  the race in order by hand rather than relying on timing.
- Rewrapped the start-of-wake paragraph in resources/AGENTS.md. Older
  long lines elsewhere in that file are not part of this change.

- Codex review loop, 2026-10-20 (read-only, given the threat model).
  Round 1 found two things. Fixed: when the agent's output ended on a
  line with no newline, pump! copied that line to the client without
  screening it, so a `_relay/cards/changed` line could reach the client
  that way. The last line now goes through the screen like any other;
  the regression test drives pump! directly. Rejected: card text reaches
  roles (the cards tool with accept_contamination, the prompt summary,
  card_add's reply) and `_relay/resolve` without visible or defang. That
  is how every kind works in relay: role reads carry raw text guarded by
  labels and withholding, and visible and defang belong to the
  operator's display edges (the CLI render, the wire form, the client's
  ref renderer). Cards do the same as docs, facts and memos.
- Round 2 found one thing, rejected: an agent line spelled
  `"Method":"_relay/..."` or starting with a no-break space slips past
  relay-method-line?, and Go's JSON decoder would still read it. The
  agent is agentloop, whose JSON framing no model or outside text
  controls; producing such a line takes a crafted agent built against
  relay's filter and the client's parser. The threat model puts that
  out of scope (an attacker who studies relay's code, or someone with a
  shell on the Mac). Round 2 had no in-scope defect, so the loop
  stopped there.

- QA round, 2026-10-20. Four findings.
  - Fixed: the agent-line screen failed open. A line with
    `"Method":"_relay/..."`, or one Jackson refuses but Go reads (nesting
    past 1000, a number past 1000 digits), reached the client. This
    reverses round 2's rejection. The brief says relay acp drops such
    lines, and failing closed costs nothing, so the screen now drops any
    agent line that is not one JSON value, and any line whose object (or
    any object in a batch) has a JSON-RPC field (`jsonrpc`, `id`,
    `method`, `params`, `result`, `error`) spelled in another case, or a
    method that starts with `_relay/` ignoring case. A permission request
    the screen drops is refused as unreadable, so the agent is not left
    waiting; that also closes a `"Method":"session/request_permission"`
    line reaching the client unseen by relay. P6-2's pass-through
    property now holds for JSON lines; it was byte-for-byte over random
    bytes, which the fail-closed screen no longer passes.
  - Fixed: a missing kind or op was reported as `nil`. `relay card add`
    with no kind prints its usage.
  - Fixed: a `seen` past the card's rev (or below 0) is refused, since no
    client can have read it. Checked against the card's rev, not the
    log's seq: every client and tool sends the card's rev.
  - Rejected, as not a defect: `bb test` exits 101 in .cards without
    `--agentloop`, because there is no sibling agentloop. The log already
    records that bb test runs with `--agentloop`; that is the
    verification setup for this workspace.

## Log

- 2026-10-20: built relay.cards, core, tools, refs, prompts, CLI, relay
  acp methods and docs. The workspace has no sibling agentloop or acp-tui
  build, so bb test ran with `--agentloop` pointing at a build of
  agentloop main made in a scratch directory. The one test that needs
  acp-tui (display-test's pane on a pty) cannot run here.
- 2026-10-20: fixed the review findings (rev in the text form, the
  agent-line drop, the AGENTS.md wrap). bb test passes in full (735
  tests) with `--agentloop` pointing at agentloop main built in the
  scratch directory.
- 2026-10-20: fixed the watcher's first-subscription race. bb test
  passes in full (736 tests) with `--agentloop` pointing at agentloop
  main built in the scratch directory.
- 2026-10-20: Codex review, two rounds, converged. bb test passes in
  full (737 tests) with `--agentloop` pointing at agentloop main built in
  the scratch directory.
- 2026-10-20: fixed the QA findings (fail-closed agent-line screen,
  plain-words refusals, seen past the card's rev). bb test passes in
  full (739 tests) with `--agentloop` pointing at agentloop main built in
  the scratch directory.
- 2026-10-20: follow-up round. Refused unknown `_relay/cards/op`
  parameters (a misspelled `seen` would otherwise turn off the stale-edit
  check) and counted text limits in code points. bb test passes (740).
- 2026-10-20: second Codex loop, on the fail-closed screen and the
  tightened op params. Three rounds; every round found an in-scope defect,
  each fixed with a regression test: duplicate and Go-folded envelope keys
  (Jackson keeps the last duplicate, Go the first), escaped client methods
  reaching the agent, `relay card` ignoring unknown options, client lines
  over 8 MB reaching the agent unscreened, a qualified `card/seen` key
  slipping past card_op's schema, and a second JSON value on one line
  (`{...} null`) passing the screen and, with a stray quote, crashing the
  pump. No round has reviewed that last fix, so the loop did not
  converge. Per AGENTS.md it stops here for Kira. Two findings are filed
  as #57. The issue stays in progress until Kira decides.

