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
set, character for character. In outside text relay shows it between
angle brackets, `<https://x.example/a>`, so that whatever reads the text
follows exactly that address. Two things put a link in the set:

1. A scout's memo. When a scout sends a memo, relay records the http and
   https addresses in it that an earlier `egress` result of the same
   session showed whole. It reads the session's own record, the one the
   fetch rule uses, so Kira may open what the scout could have fetched. An
   address the scout built stays broken, since it could carry what the
   steward told it.
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

- [x] A scout's memo records the addresses in it that its session was
  shown, and no other.
  - Receipt: `a-scout-s-memo-keeps-the-links-it-was-shown-live`
    (scout_test.clj), through the real tool path;
    `a-scout-hands-on-the-links-it-was-shown` (links_test.clj);
    pentest `s27-live-links`.
- [x] An address the scout built, one changed by a character, and one with
  a scheme other than http or https are not recorded.
  - Receipt: `a-link-the-scout-built-is-not-handed-on`,
    `a-link-to-the-operators-own-network-is-not-handed-on`, property
    `a-scout-hands-on-only-what-it-could-have-fetched`.
- [x] A message and a card that quote a recorded link show it whole in the
  client's wire form, in mail and in a push. Every other link in the same
  text is still broken.
  - Receipt: `the-operator-can-open-what-a-scout-was-shown`
    (scout_test.clj); `s27-live-links`.
- [x] A live link is whole only as a whole word: text run on after it
  breaks it.
  - Receipt: `text-run-on-after-a-live-link-breaks-it`,
    `what-comes-before-a-live-link-in-its-word-is-still-broken`,
    `a-live-link-is-shown-between-angle-brackets`.
- [x] Kira can add a link from the command line, pasted whole or in its
  broken form. A role cannot.
  - Receipt: `the-operator-keeps-and-drops-a-link-on-the-command-line`,
    `only-the-operator-keeps-or-drops-a-link`; `s27-live-links` tries
    four tool names as the steward.
- [x] relay refuses to add an address with hidden characters, with a
  scheme other than http or https, or with any character outside
  printable ASCII.
  - Receipt: the same two tests, and `a-live-link-has-one-plain-shape`.
    The command line takes hidden characters out of a paste first and
    says so, as it does for every paste, so the refusal for those is
    tested where the command is decided.
- [x] Property: text with no live link in it is shown exactly as before.
  Property: no link outside the set is ever shown whole in contaminated
  text.
  - Receipt: `text-with-no-live-links-is-broken-as-before`,
    `no-link-outside-the-live-set-is-ever-whole`,
    `keeping-links-live-changes-nothing-else`,
    `cutting-shown-text-leaves-no-link-outside-the-live-set`.
- [x] README, resources/AGENTS.md, spec/relay.allium, docs/architecture.md
  and docs/client-protocol.md say what changed.
  - Receipt: zlxwuxkm "Describe live links for Kira, roles and clients",
    and the pentest list in yxslrllw.
- [ ] `bb verify` passes.
  - It passes but for relay#99's two tests, which need Apple's container
    service and fail on the baseline too. See the Log.
- [ ] An adversarial Codex review finds nothing in scope.
  - Three rounds, twelve findings, all fixed; no clean round. With Kira,
    per AGENTS.md. See the Log.

## Plan

1. Pure core in `relay.text` and `relay.shown`: find the links in a text,
   and break all but the live ones.
2. `memo/send` by a scout records its shown links. A new event records
   Kira's. The fold keeps the set.
3. Pass the set to messages, cards and the ready items. The command line's
   own views follow.
4. Docs, verify, review.

## Log

Judgment calls, all mine, 2026-11-14:

- The test for a scout's link is "the web showed it whole", which is
  stricter than the fetch rule's "appears somewhere". The fetch rule lets
  a scout fetch the start of a longer address; a link Kira clicks should
  be one a page or a search really gave.
- A scout hands on only links to a dotted host name. A bare name or a
  number is a machine on Kira's own network. She can still keep one
  herself.
- A live link is plain ASCII with no user name before the host, so it
  reads on a screen as exactly what it is, and `visible` never changes
  it. A host in other letters has to be given in its `xn--` form.
- A dropped link stays dropped when a scout is shown it again. Dropping
  is how Kira takes a link back, so nothing but Kira undoes it.
- `relay link` and `relay link drop` take the address, and `relay links`
  lists both sets. The address can be pasted in the broken form relay
  shows, since that is what Kira can copy off a screen.
- Live links reach messages (client, mail, push), cards, the ready view,
  `relay card`, `relay cards` and `relay inbox`. The other command-line
  views (`relay status`, `relay log`, notes, the calendar feed) still
  break every link. They are the safe default, and nothing Kira reads a
  scout's findings in.
- Conversation text in relay-tui is broken by the client, from the
  session's labels, so it does not know the live links yet. That is
  relay#95.
- A live link is shown between angle brackets. A bare link in running
  text is followed wherever each reader decides it ends, and readers
  disagree. I ran Apple's `NSDataDetector`, which mail and the phone
  use, over about 400 combinations of address and surrounding text: with
  the brackets it follows exactly the bracketed address in every one,
  except an address holding a round bracket, which it balances across
  what follows. So a live link holds no round bracket. A Wikipedia
  address such as `.../Fund_(arts)` therefore stays broken.
- With the brackets doing that work, a role's word is read generously
  (`relay.text/link-in`): a link in Markdown or before a question mark is
  found. The web's text and what Kira types are cut once, where Apple's
  finder cuts an address (`relay.text/address-in`).
- Where relay cuts or wraps text it has already made safe, it never
  leaves part of a link that can be followed: a cut inside one breaks
  it, and `relay ready` leaves a long one on a line of its own.
- The dashboard still breaks the links relay now sends whole. relay-tui
  re-breaks any whole link in outside text as its own check, and it cuts
  text to a pane's width. That is relay#95, with a proposal there.

Found while verifying, not part of this change:

- `a-calendar-feed-is-read-and-a-redirect-is-not-followed` had failed
  for everyone since 2026-10-29: the command line read the real clock.
  Fixed in qqollkvo.
- The live shell test and pentest `s18-shell-walls` fail because Apple's
  container service is not running on this Mac. Filed as relay#99.

Codex review, read-only, threat model given:

- Round 1, 2026-11-14: four findings, all in scope, all fixed in
  vyvvknto.
  1. A push body was cut after its links were judged, so a live link at
     the limit came out as a shorter address. Every cut of shown text now
     breaks a link it falls inside (`relay.text/clip-shown`,
     `break-cut-word`).
  2. "Shown whole" took the start of a longer address when a letter
     outside ASCII or a bracket the address had opened came next. The
     addresses in a result are now cut by the same function as the ones
     in the memo (`relay.shown/addresses-shown`).
  3. Marks such as `**`, `:` and `?` after a live link are taken into the
     address by Apple's link finder. They no longer count as closing
     marks.
  4. Keeping or dropping a link moved no rev, so a client that held a
     card or a message with the link kept the old reading. Those are now
     sent again (`relay.workspace/cards-changed`, `messages-changed`).
- Round 2, 2026-11-14: five findings, all in scope. One I had fixed
  while it ran (the ready list's cut mark). The other four were one
  class with round 1's: relay judged a link and left it bare in running
  text, where the reader decides where it ends. Fixed at the source in
  snpmmkou by showing a live link between angle brackets.
  1. Dropping a link missed a message that held it as a ref, since the
     resend scanned raw text. What to send again is now decided by
     comparing wire forms (`relay.workspace/reads-differently`).
  2. The ready list's cut mark took two characters off a line and could
     leave the start of a link. Fixed in wyznnwxo.
  3. Apple's finder balances round brackets across what follows an
     address. A live link now holds none.
  4. relay's own tracker line puts a colon straight after a row's text,
     which Apple's finder takes into the address. Between angle brackets
     it no longer does.
  5. The web's text was split at a pipe, so the start of a longer
     address counted as shown. It is now split only where Apple's finder
     stops.
- Round 3, 2026-11-14: three findings, all in scope, all fixed. The
  rule in AGENTS.md is that a third round which still finds something
  goes to Kira, so no fourth round was run.
  1. Apple's finder dropped the last `]` of `<https://x/?q=[[1,2]]>`. I
     fuzzed it to find the whole class: 400,000 random addresses between
     angle brackets, with text before and after. Every one it followed
     differently held a bracket, or had a full stop, comma, semicolon or
     exclamation mark after its last letter or digit. `relay.text/link?`
     now admits neither, and of the 203,013 addresses of that shape the
     finder followed all exactly. The check is in the repo as
     `scripts/link-finder-fuzz.swift`.
  2. A soft hyphen or a zero-width joiner after an address in the web's
     text was taken for its end, and Apple's finder takes both into the
     address. The web's text is now split only at a space, a tab, a line
     break and `< > " { }`, so an address a hidden character runs into is
     no address.
  3. `relay links` and the answer to `relay link drop` showed a dropped
     link whole. Both show it broken now.
- A second session (relay-c3) read the whole diff independently and found
  no in-scope defect. It did find that `relay link drop` refused a live
  link pasted with its angle brackets, fixed in vpolxxoz.
- `bb verify`, run by this session and again by relay-c3 on the same
  chain: agentloop, relay-tui, webtool and eventkit pass; relay passes
  1,182 of 1,183 tests and the pentests 30 of 31, `s27-live-links`
  among them. The two that fail are relay#99's: Apple's container
  service is not running on this Mac.
- 2026-11-14, Kira, asked whether to accept the change as it stood or
  run a fourth Codex round: "yes I think your recommendation is
  correct." The recommendation was to accept it. No fourth round.
- 2026-11-14, after that: relay#95's review found that Ghostty's link
  finder stops at an apostrophe, so `<https://example.org/o'brien>`
  printed by `relay ready` would be followed as `https://example.org/o`.
  Ghostty is the terminal Kira runs. Nothing private leaves that way, but
  it is an address nobody took. With relay-34 and relay-32 I narrowed a
  live link to what every common finder reads whole: after the host,
  letters, digits and `- . _ ~ / ? # & = % : + @`, ending in a letter or
  a digit, or a slash right after one. relay-tui draws a link as text
  under the same rule. The cost: an address with a comma, an apostrophe
  or a bracket, or one that ends in `=`, stays broken, and `relay link`
  refuses it.
- The final slash has to come right after a letter or digit. relay-tui's
  rule allowed any final slash, and Apple's finder cut 66 of 291,850
  such addresses (`.../a._/`). With the stricter ending it followed all
  188,381 of 600,000 exactly. Only Apple's finder was run. Ghostty's was
  checked by relay-34's reviewer from its matcher expression, and the
  other terminals by reading their rules, not by running them.

