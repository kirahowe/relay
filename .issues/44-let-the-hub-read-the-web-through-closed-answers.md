# Let the hub read the web through closed answers

## Context

Part of the personal-agent epic Kira started on 2026-10-18. Design
sections D8 of the epic design, which is kept with these issues as
.issues/personal-agent-design.md. Kira's principles: assume the model is
fully compromised; nothing acts in the real world for the operator without
explicit consent, given at the time or in advance; roles draft and the
operator reviews; answers cite sources; the operator never needs to know
relay's internals.

## Acceptance criteria

- [x] webtool ask searches and fetches the top n result URLs (at most 5) under the fetch guards. No free URL goes in.
- [x] The quarantined reader may read through an egress inner tool. The call is decided under the caller's labels.
- [x] The example config and relay init grant the hub web under :quarantine.
- [x] An eval shows a closed web answer leaving the hub clean. A pentest shows a hostile result page giving only a closed answer.
- [x] relay#34 is closed as covered, with the reason.
- [x] The Codex review loop has converged.

## Decisions

- webtool ask takes `n` from 1 to 5, 3 by default, and asks the backend
  for twice `n` results, so a URL that comes twice still leaves `n`
  pages. Pages are fetched one at a time, as check does, which keeps one
  page's text in memory at a time. The tool's `:timeout-seconds` is 150
  to cover a search and five 20-second fetches.
- Each page in ask's output starts with a line holding a random token
  made for the call. A hostile page cannot write a fake page header and
  shift the page numbers a closed answer cites.
- ask is an error when no page could be read, or when the search fails.
  No results is not an error. Each page that failed shows its reason in
  place of its text.
- agentloop: request validation also had to accept an egress
  `quarantine.tools` entry (internal/request), besides decideReader. That
  is outside trifecta's Decide and Tool, so it does not touch the
  concurrent class work. Write and act inner tools are still refused, and
  so will house be, since the check names read and egress only.
- relay still refuses an `:always-hold` tool in `:quarantine`: every read
  would wait for the operator, and the design does not need it.
- A held `read_quarantined` call is shown in `relay holds` and in the
  hold notification as a call to the inner tool, "through the reader",
  with that tool's class and "where it would go" read from the inner
  input. Before, it read as "one of relay's own tools", which would have
  confused the operator once reader calls can be held.
- The hub keeps `web` in `:tools`, since relay and agentloop require the
  reader's inner tools to be granted. So the hub can call `web`
  directly. That is not a safety gap: the pages contaminate it and its
  later egress and act calls are held. It defeats the point, though, so
  resources/AGENTS.md tells roles not to, and the tool's description
  says to read it through read_quarantined. Making a quarantine-only
  tool unreachable directly needs agentloop to offer inner tools only to
  the reader; worth a follow-up issue.
- relay init asks for the webtool binary (suggesting `webtool` on PATH
  or the sibling checkout's build, `none` to leave the web out) and the
  Brave key command (keychain service relay-brave), and says the key is
  used only to send searches to Brave. It writes `webtool.json` beside
  relay.edn, unless one is there already, with snapshots under
  ~/Library/Application Support/webtool/snapshots, outside the synced
  data directory. The init hub gets only `web`, as before it got nothing.
- The eval case uses a new staging stand-in, `web-ask-demo` (egress,
  contaminated), that answers from the pages in `<data>/web` in ask's
  shape and logs each query to `searches.log`. Its foil calls the tool
  directly, so the hub is contaminated and its reminder (remind-demo,
  `:act`) is held. If the D1 work makes remind-demo `:house`, that foil
  no longer holds and its `:fails` set needs updating.
- The pentest (s15-web-ask) runs webtool's fixture build against the
  red-team corpus server, with the hostile email-someone page as the
  first result.
- TODO for integration with relay#45: when `show_operator` exists, tell
  roles in resources/AGENTS.md that when they need prose they can ask
  for it to be shown to the operator. It was not there when this work
  was done, so the docs do not mention it.
- `quarantines?` counts every :act or :egress tool in a role's `:tools`,
  `web` included. Today the hub already holds `rem` (:act), so nothing
  changes. Once D1 makes rem `:house`, `web` alone will keep contaminated
  memos to the hub quarantined. That is the safe side; whether it should
  count a tool the role only reads through the reader is Kira's call.

## Log

- 2026-10-18: verified. webtool `make test`, agentloop `make test` and
  `make build`, relay `bb test` (615 tests) all green. `bb pentest`: 59
  pass, 5 skip (no cleanroom or acp-tui in this workspace), s15-web-ask
  and s13 among the passes. Live eval web-closed-answer-stays-clean: 3/3
  on Haiku (20261018T235236Z-0818), 1/1 on Opus (20261018T235317Z-544c).
  Codex review, round 1: no in-scope findings. It noted that mapping
  page numbers to web: refs in a display is not built yet; that is
  relay#45's display work.
