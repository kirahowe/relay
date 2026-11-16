# Carry a live link as data beside inert text

## Context

Sent by the relay#94 session on 2026-11-14, at the coordinating
session's request. The text is that session's own, and that session
builds it.

relay#94 keeps a set of live links (the web showed one to a scout, or
Kira kept it) and left such a link whole in outside text, between angle
brackets. Three review rounds on relay#94 and three on relay#95 kept
finding the same thing: a link shown as text is followed wherever the
reader's link finder decides it ends, and finders disagree (Apple's,
Ghostty's, kitty's, WezTerm's). Showing one whole needs every finder to
agree exactly, over readers nobody controls. That does not converge.

Kira, 2026-11-14, in the relay#95 session: "I think you have the right
analysis, we should stop trying to fix the symptom and get to the root
of the issue. Links as data is a better direction. I like it, you can
execute in whatever order is most efficient."

So shown text is always inert, and a link is data relay attaches to it.
Only a channel's own mechanism carries the address.

What stays from relay#94: the live set and its fold; how a scout's memo
records the links the web showed it; `relay link`, `relay link drop` and
`relay links`; the link shape as the rule for what may enter the set;
`relay.text/link-in`, which finds the live links a role's text holds;
`clip-shown` and the wrap rule; and resending a card or message that
reads differently once a link is kept or dropped.

What goes: leaving a live link whole in text (`relay.text/defang-but`
and the angle brackets). In outside text every link is broken again, as
before relay#94.

What is new:

- On the wire, a card, a message and a ready item each carry `links`, a
  list of `{"address", "shown"}`: each live link its outside text holds,
  and the broken form the text shows for it, so a client can make that
  text a hyperlink to the address.
- In mail, still text/plain, an item's live links follow it on lines of
  their own, one address to a line, so nothing a role wrote touches an
  address. relay already does this for a link it builds itself.
- On the command line (`relay ready`, `relay cards`, `relay card`,
  `relay inbox`, `relay links`), on a terminal, a link line is the
  broken form inside an OSC 8 hyperlink to the address. Where the output
  is no terminal there is nothing to follow.
- In a push, the body is inert and up to three live links are ntfy
  actions. This is the half of relay#117 that was Kira's to decide; her
  "links as data" covers it, and how the buttons are labelled is still
  worth showing her.

## Acceptance criteria

- [x] In text an outsider may have shaped, relay shows no link that can
  be followed, in any channel or view, whatever is live. A property
  checks it.
  - Receipt: `links_test`: `outside-text-is-broken-whatever-is-live` and
    `cutting-shown-text-leaves-no-link-to-follow`; the fuzz against
    Apple's link finder in `scripts/`, none followed.
- [x] A card, a message and a ready item each carry `links`: every live
  link their outside text holds, in order, each once, with the broken
  form the text shows. docs/client-protocol.md states the contract,
  agreed with the sessions on relay#95, relay#96 and relay#97 before it
  is built.
  - Receipt: `links_test`:
    `a-listed-link-is-live-and-its-shown-form-is-in-the-text`,
    `a-client-is-sent-the-links-beside-the-text`,
    `a-card-carries-its-links-beside-its-text`. A surface card's strings
    join the list once relay#97 has landed; see the Log.
- [x] A mail lists an item's live links after it, one address to a line,
  alone.
  - Receipt: `links_test`:
    `a-mail-lists-an-items-links-after-it-one-address-to-a-line`.
- [x] On a terminal the command line shows a live link as the broken
  form inside OSC 8; piped, it writes no OSC 8.
  - Receipt: `links_test`:
    `a-terminal-is-told-the-address-and-reads-none-from-text`,
    `a-terminal-follows-only-live-links-and-the-text-is-unchanged`, and
    `the-operator-keeps-and-drops-a-link-on-the-command-line`, which
    runs `relay links` piped.
- [x] A push carries up to three live links as actions and says when
  there are more.
  - Receipt: `links_test`: `a-push-carries-up-to-three-links-as-actions`.
- [x] Keeping or dropping a link sends again every card, message and
  ready item whose `links` changed.
  - Receipt: `links_test`:
    `a-link-kept-or-dropped-reaches-a-client-that-holds-it`;
    `links_acp_test` over relay acp.
- [x] Pentest s27 reads the links from the data and from the mail's
  lines, and still finds no address holding the canary that can be
  followed.
  - Receipt: `s27-live-links` passes, the dashboard checks included,
    against relay-tui ed33f90d, the reader on relay-tui's `integration`.
- [x] README, resources/AGENTS.md, spec/relay.allium,
  docs/architecture.md and docs/client-protocol.md say what changed.
  - Receipt: uormplut on this chain.
- [x] `bb verify` passes but for relay#99's two tests.
  - Receipt: 2026-11-15, tip poxzoxsl, with relay-tui ed33f90d; the
    numbers are in the Log's last entry.
- [ ] An adversarial Codex review finds nothing in scope. (Not met as
  written: three rounds, none clean. Kira accepted it on 2026-11-15 with
  the fuzz as the standing check; see the Log.)

## Plan

## Log

- 2026-11-14: the relay#93 session had put relay#117's question about a
  push to Kira before this issue took it over. Her answer: she has no
  strong view yet on what a push should look like, and the session can
  make the call. The call, which is the relay#93 session's: links as
  actions, as the criteria above have it. The mail app she named for the
  other question is in relay#117.
- 2026-11-14, the session building it. Judgment calls, all mine:
  - One `links` list on each card, message and ready item, not one per
    text element. The form the text shows travels with the address, so a
    client finds it in whichever string holds it. The sessions on
    relay#95, relay#96 and relay#97 agreed the contract before it was
    built; it is in docs/client-protocol.md under Live links.
  - A thing with more than 50 live links also says how many were left
    out (`linksLeftOut`), so a missing link reads as a limit.
  - Which occurrence of the shown form a client may anchor is the rule
    relay itself uses to find the link in a role's word: at most six
    marks after it, each one that follows a link in prose or Markdown.
    "The next character cannot continue an address" was tried first and
    is wrong, because a full stop can. `relay.text/anchored` is that
    rule as one pure function, used by the command line.
  - The marks a role may write after a link are the generous set again
    (`>` included, up to six). relay#94 had narrowed them because they
    could ride into an address a finder followed. Nothing reads an
    address out of text now, so they only decide whether the role wrote
    the link.
  - The command line adds no link lines. On a terminal the printer makes
    each broken link that is live a hyperlink in place. The live set
    rides as metadata on a command's result, so what a command says is
    the same value either way, and it is read from the log only when
    some line holds a broken link.
  - `relay ready` no longer cuts a word that holds a link, whole or
    broken. A broken link has to stay one word to become a hyperlink,
    and to be copied for `relay link`. So one line may run past 60
    columns: a single word holding a link. The width property says so.
  - In mail an item's links follow the item, as a link relay builds
    itself already did. A subject's and a heading's follow the body.
    Mail stays text/plain; what Proton Mail follows in those lines is
    relay#117.
  - A push's buttons are labelled "Open" and the host, numbered when a
    host comes twice. More than three links: the body ends "(n more
    links in relay)". Kira has not seen these yet.
  - The link shape is unchanged and still decides what enters the set.
    Mail is the one channel that shows an address as text.
  - Text no outsider shaped is shown as written, as before. Whether that
    changes is relay#119.
  - A surface card (relay#97) will get its list from
    `relay.surfaces/strings`, the strings relay resolved, once relay#97
    has landed.
- Review, read-only, threat model given. The findings were all in link
  breaking older than this change, which the review of inert text
  reached.
  - Round 1 (Codex), 2026-11-14: two findings, both fixed in wxmtmmwq.
    The ready view showed an outside part of the daily note by the
    note's own clean labels, so its links stayed whole in the dashboard
    and in `relay ready`. And `https:/x.pl` was not broken. Checking the
    second against Apple's finder showed it also follows a bare name
    ending in pl, rs or so, which relay took for a file. The wider
    question that raised is relay#121.
  - Round 2 (Codex), 2026-11-15: three findings, fixed in yxmtvusq. A
    name such as `secret.attacker.com-report.txt` was left as a file,
    and a link finder follows the host in it. `.@secret.attacker.com`
    was left as a mail address. And `relay ready` cut a long file name
    after the host inside it.
  - Round 3 (a reviewer agent, not Codex): Codex ran out of quota on
    this account until 19 November, so this round was a fresh agent with
    the same brief, the threat model and the diff, none of this
    session's conclusions, and Apple's link finder to try inputs
    against. Three findings, fixed in the commit after yxmtvusq. A host
    written straight after a port's digit was left whole
    (`docs.example.org:1secret.attacker.com/x`). An `@` after a lone
    `+`, `-` or `_` still counted as a mail address. And `relay ready`
    cut a long file name into a piece that reads as a host
    (`...-v02.ini` to `...-v02.in`). It found nothing in the rework
    itself: the links list, the terminal hyperlinks, the mail's address
    lines and the push's actions held, and it found no way for a role to
    add a live link.
  - Three rounds, eight findings, none clean, so by AGENTS.md this goes
    to Kira and no fourth round was run. Every finding was in
    `relay.text/defang`'s exemptions (file names, mail addresses, ports)
    or in what cuts its output, not in links as data. The reviewer's
    fuzz is now in the repo (`scripts/inert-text-fuzz.clj` and
    `scripts/inert-text-check.swift`): random hostile strings as mail, a
    client and `relay ready` show them, with Apple's link finder asked
    whether any can be followed. After the fixes: 1,980,000 cases over
    five seeds, none followed.
- `bb verify` on the rework before the review fixes: agentloop,
  relay-tui, webtool and eventkit pass; relay 1,188 of 1,189 and the
  pentests 30 of 31, the rest being relay#99's two. relay's suite after
  the fixes: 1,190 of 1,191.
- 2026-11-15: relay#124, relay#125 and relay#126 came out of the reviews
  of relay#97 and relay#95 while this waited. All three are in
  `relay.text`, so they are fixed on this chain, in slposyyt, and each
  issue has its receipts. relay#124 was round 3's port finding, already
  fixed; its three strings are tests now. relay#126 is half relay-tui's
  and stays open.
- 2026-11-15: rebased over the first part of relay#95 (`_relay/links`,
  keeping and dropping from a client, and s27 run against the real
  relay-tui).
  - Its tests that expected a live link whole in a card's text now check
    the card's `links` beside text that stays broken: `links_acp_test`,
    and three tests in `hub_test`. Three sentences of its docs and spec
    say "listed" where they said "whole".
  - s27 keeps relay#95's dashboard checks: relay-tui is given the
    address the web showed as a hyperlink's target, writes no address as
    text, and is given nothing once the link is dropped.
  - relay-tui on its `integration` (c1716fc8) finds a live link by
    reading a whole address in the text. Against this chain it is safe
    but has nothing to follow, and s27 fails that one check. The
    relay#95 session built the reader for the `links` list (relay-tui
    a17a265d, not landed when this was written), and s27 passes every
    check against it. So relay#118 and that relay-tui change have to
    reach `main` together.
  - `bb verify --relay-tui-dir` on an export of a17a265d, tip slposyyt:
    agentloop, relay-tui, webtool and eventkit pass; relay 1,196 of
    1,197 and the pentests 30 of 31, the rest being relay#99's two.
  - The fuzz after the rebase, seed 11: 1,350,000 cases, none followed.
- 2026-11-15: Kira's answer on the review. Asked how to close it after
  three rounds with none clean, with three choices (accept with the fuzz
  as the standing check, a fourth round, or remove defang's exemptions
  and break every dot in outside text), she chose to accept with the
  fuzz. So no fourth round was run.
  - To make the fuzz a standing check and not a script someone has to
    remember, it is now a step of `bb verify`
    (`scripts/inert-text-fuzz.sh`): 100,000 new strings each run, 300,000
    cases, about 25 seconds, with the seed printed. A hit fails the run
    and prints the string, which goes into `relay.text-test` before the
    fix. The seed changes every run on purpose, so a run can fail on
    text nobody changed; that is a real defect in link breaking each
    time, and the reason the step exists.
  - What a hit means for a change that is not about link breaking is
    in VERIFY.md, and the rule is the coordinating session's:
    the hit is filed with its seed and string, a change that touches
    none of `relay.text`, `relay.messages` and `relay.render` is not
    held back by it, and nothing reaches `main` on a run with one.
  - This goes to review with the last criterion unticked, as relay#94
    did.
- 2026-11-15: landed on `integration`.
  - The relay#95 session's reader for the `links` list reached
    relay-tui's `integration` first, as relay-tui ppzxzvzp (ed33f90d).
    It is the a17a265d of the entries above after its own review: a
    card or a ready item that has a `links` key is read from the list
    alone, an empty one included, and a list it cannot read leaves the
    thing in place with no links. relay always sends the key.
  - `bb verify --relay-tui-dir` on an export of ed33f90d, tip poxzoxsl:
    agentloop, relay-tui, webtool, eventkit and fuzz pass (seed
    1794748575, 300,000 cases, none followed); relay 1,196 of 1,197 and
    the pentests 30 of 31, s27 among those that pass. The two failures
    are relay#99's.
  - Not in this change: a surface card's `links`. relay#97 lands after
    this and adds them, read through `relay.surfaces/strings`.
- 2026-11-15 approved: 2026-11-15: Kira accepted it through the coordinating session's question dialog. The review criterion stays unticked: she chose to accept with the fuzz.
