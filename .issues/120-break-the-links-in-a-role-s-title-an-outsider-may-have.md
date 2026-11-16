# Break the links in a role's title an outsider may have shaped

## Context

Found by Codex in the fourth review round on relay#95 and sent by the
relay#95 session on 2026-11-15. The dashboard's half is fixed in
relay#95: relay-tui now breaks every link in a role's title, in a ready
item's `from` line and in relay's refusal text. What is left is relay's
half.

The reviewer's text:

> G1/G2 — contaminated roster titles reach the terminal as unbroken
> URLs. In render/hub.go:122, `roleName` calls `paneLine(r.Title,
> false)`, disabling defanging. wire/hub.go:65 discards the roster's
> labels. The new gate at links.go:195 leaves this text untouched
> because it contains no OSC 8 sequence. Trigger: a contaminated private
> role legitimately granted `:may-add` calls `role_add` with
> {"id":"helper","title":"https://evil.test/?d=CANARY","instructions":"Help."}.
> Relay accepts it and sends the title with ["contaminated","private"].
> At width 60, the dashboard displays the complete address with an empty
> live set. I reproduced both the accepted relay command and the
> resulting terminal screen through the real UI and terminal emulator.
> Normal URL detection can make it clickable, for example WezTerm's
> default rules. Scope: ordinary prompt injection into a role with
> legitimate registry permissions can place private data in the URL;
> clicking sends it out. No targeted attacker, local shell, precise
> timing, or second failure is required. This is a pre-existing roster
> path left uncovered by the claimed guarantee.

What relay does with a title, checked against the code by the relay#93
session on 2026-11-15:

- `relay.core` keeps `title` as written when a role is added or
  updated.
- `relay.roster/wire-role` sends the title as it is, beside the role's
  labels, in `_relay/roles` and `_relay/roles/changed`.
- `relay.hub/list-sessions` answers `session/list` with the same title
  as each conversation's `title`, with no labels beside it. The reviewer
  did not name this path. A plain ACP client, such as the phone's
  (relay#84), shows it as the conversation's name.
- `relay roles` on the command line is covered already.
  `relay.render/roles` passes each line through `text/for-operator`
  with the labels of the role's id and title.
- Not checked: whether a role's title reaches mail or a push.

The relay#95 session also asked about the "Drew on" line, which relay
sends as a ready item's `from` and a message's `drewOn`.
`relay.provenance/phrase` breaks the links in the two names an outsider
chooses, a web page's and a mail's sender. The name of a GitHub
repository, a calendar feed, a files mount or a tool goes in as written.
As far as I read it those come from relay.edn; I did not trace each one.

## Acceptance criteria

- [x] A title whose labels are contaminated reaches no client with a
  link that can be followed: not in `_relay/roles`, not in
  `_relay/roles/changed`, and not in a `session/list` answer.
  - Receipt: relay.roster/wire-role and relay.hub/list-sessions send
    `relay.text/safe-line`; `relay.role-title-test` checks all three.
- [x] Every other place relay shows a role's title is listed here, and
  each breaks its links or is shown not to need to.
  - Receipt: the Log entry of 2026-11-16 that lists each path.
- [x] The names in a "Drew on" line that are not broken are shown to
  come only from Kira's settings, or they are broken too.
  - Receipt: relay.provenance/phrase defangs every name;
    `relay.provenance-test/no-name-in-a-drew-on-line-can-be-followed`
    (its own oracle) fails on the old phrase.
- [x] A test adds a role as the reviewer did, from a contaminated
  session with an address as the title, and finds no whole address in
  any of those answers. It fails on today's code.
  - Receipt: `relay.role-title-test/a-title-an-outsider-shaped-reaches-no-client-as-a-link`;
    with roster.clj and hub.clj put back as they were, it fails on all
    three paths.
- [x] A pentest case does the same through a plain ACP client.
  - Receipt: pentest `s5-acp-role-title`, which fails on the old code
    (session/list and _relay/roles both show the whole address).
- [x] docs/client-protocol.md says a role's title is ready to show, as a
  card's text is.
  - Receipt: docs/client-protocol.md, `_relay/roles`.
- [x] `bb verify` passes, and an adversarial Codex review finds nothing
  in scope.
  - Receipt: bb verify passes across relay.ec, relay-tui.ec,
    agentloop.ec and webtool.ec, fuzz and pentest included. Codex was
    out of quota, so a fresh read-only reviewer agent was given the
    threat model. Round 1 found nothing in scope (see the Log).

## Plan

Related: relay#95 and relay#118. relay#104 is the same gap for
conversation text, and relay#84 is the phone trial both should land
before.

## Log

- 2026-11-15: the dashboard's half is relay-tui pzkztxvr (9ae8a086),
  with kxsoqlxu (c1716fc8) on top, as the relay#95 session reports. Both
  land with the first part of relay#95.
- 2026-11-16, relay-ec. Where relay sends a role's title, and what each
  path does with it:
  - `_relay/roles` and `_relay/roles/changed` (relay.roster/wire-role,
    through relay.hub/roles-wire) and `session/list`
    (relay.hub/list-sessions) now send `relay.text/safe-line`: links
    broken when the title's labels (its id's and its own) are
    contaminated, then every hidden character and newline shown as an
    escape. That is a card's text, as one line. safe-text moved from
    relay.cards to relay.text so that relay.roster can use it.
  - `relay roles` (render/roles) and `relay status` (render/role-status)
    already went through for-operator with the same labels.
  - The `roles` read tool (relay.tools) shows the title to a model, not a
    person.
  - Pushes, mail, ready items, messages, digests and notices name a role
    by its id, never its title. A sweep of src for (core/role ...) and
    :title found no other path.
  - relay.prompt puts the title in the role's own system prompt, for the
    model.
- 2026-11-16, relay-ec. A title stays a plain string and carries no
  `links`: the criterion asks for its links to be broken, and no client
  draws a title's links. Listing them would mean a new wire shape for
  every client.
- 2026-11-16, relay-ec. Every name in a "Drew on" line is defanged now
  (relay.provenance/phrase). The names do not come only from relay.edn.
  A tool call's envelope (relay.tools, about line 1775) and an external
  tool's output (parse-result) accept any `kind:what` label that
  known-label? passes, so `github:evil.test/x` or `files:evil.com` can
  come from outside. Defanging all of them is simpler than proving each
  name's origin, and a name with no host in it reads the same.
- 2026-11-16, relay-ec. relay-tui's roleName escaped a title itself
  (EscapeLabel), which would have doubled the backslashes relay now
  sends. It now does what cardText does: EscapeNonPrinting, newlines
  shown as \n, and Defang always. relay-34 was told, since it was working
  in the same package for relay#130. The two commits have to reach main
  together.
- 2026-11-16, relay-ec. The provenance property's oracle is its own. It
  counts a dot before a letter, a dotted quad, or a whole http, https,
  ftp or file scheme as followable. Its first draft also counted a dot
  before a digit and three numbers joined by dots. defang leaves both
  alone on purpose (12.50, v1.2; its docstring, from relay#130), and
  without a scheme only an undocumented link finder would follow them.
  So the oracle follows defang's documented rule and does not reopen it.
- 2026-11-16, relay-ec. Review round 1, by a fresh read-only reviewer
  agent given the threat model, since Codex was out of quota until
  2026-11-19: nothing in scope. It probed fullwidth and ideographic
  dots, zero-width characters and soft hyphens around a dot, newlines,
  combining marks, dotted quads, `https:3405803777`, and every
  punctuation mark that defang's tail rule lets a final dot keep (no
  top-level domain starts with one). It checked the Drew-on names and
  relay-tui's roleName, including that Defang leaves relay's broken
  text alone. Its one remark falls outside this issue: `_relay/roles`
  sends a role's labels as raw strings, and a source label like
  `web:https://evil.test/?d=...` holds a whole address. Labels are data
  that no client is meant to show, and relay-tui does not show them. It
  went to the coordinating session to file.
- 2026-11-16 approved: 2026-11-16: Kira accepted it in the coordinating session's question dialog; every criterion has its receipt.
