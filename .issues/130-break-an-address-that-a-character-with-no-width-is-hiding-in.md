# Break an address that a character with no width is hiding in

## Context

Sent by the relay#95 session on 2026-11-15, at the coordinating
session's request, from the review of the hidden-character rule. A fresh
read-only reviewer standing in for Codex found it on relay-tui yuttxulq
(082f8c24). It ran each input through relay-tui's contaminated reply
path. Nobody had tested a terminal or a mail reader against these when
it was sent.

The reviewer's text:

> Zero-width characters that are not hidden still split an address (ran;
> same premise, weaker since most show; owner's call). Marks and
> conjoining jamo are kept by rule: `https:`+U+0301+`//…`,
> `ww`+U+0323+`w.…`, `https:`+U+1161+`//…`. Two are invisible by design
> and in neither list: U+1107F and U+1BC9D. Glue can be moved in:
> `https:*`+U+FE0F+U+20E3+`//evil.test*`. Markdown takes the `*`, and
> the reply draws the selector and keycap mark inside `://`. `bare`
> keeps marks, so the property cannot see this.

The scope as the relay#95 session sent it: `relay.text/defang` and
relay-tui's `render.Defang`, the same gap in both. Each finds a link by
its letters, so a character with no advance inside `://`, a scheme,
`www.` or a host hides the link from the breaker, while a reader that
skips such characters may still follow it. Hidden characters are already
taken out or shown as escapes. These are the ones that are not hidden:
combining marks, which show on the letter before them, and U+1107F
(Brahmi number joiner) and U+1BC9D (Duployan thick letter selector),
which are invisible by design and in neither relay's `default-ignorable`
nor Unicode's Default_Ignorable_Code_Point.

The fix that session proposes: judge a word with its zero-advance
characters skipped (general categories Mn, Me and Cf, and the
default-ignorable ranges). Where the word as skipped holds a link that
the word as written does not, break the word as skipped. No mark is
dropped anywhere else, so ordinary text in scripts that use marks is
untouched. `hidden?` takes U+1107F and U+1BC9D. For emoji glue that
Markdown moves in, the same judging applied to a row as drawn covers it
in relay-tui.

A first step it names that costs little: the fuzz's alphabet in
scripts/inert-text-fuzz.clj has U+200B and U+00AD but no combining mark
and no keycap glue (U+FE0F then U+20E3 after `*`, `#` or a digit).
Adding them shows whether Apple's link finder follows such an address in
mail and on the phone, which decides how urgent relay's half is.

The coordinating session's triage, for Kira to confirm: in scope as
hidden text, P2. An address written this way does not work in ordinary
readers, so untargeted text is unlikely to carry it. The readers at risk
are finders that go by cells and skip marks. It holds up neither
relay#95's landing nor the surface work. It has since withdrawn the
sentence about ordinary readers and changed the triage. Both are in
the Log.

### What the relay#93 session ran before filing

On 2026-11-15 I ran the inputs three ways on Kira's Mac: through Apple's
NSDataDetector as written, through `relay.text/for-operator` with the
label `contaminated` on `integration` (c2d0d1df, which has relay#118),
and through `render.Defang` from relay-tui 082f8c24. I then gave each
output to NSDataDetector. Every address was `evil.com/steal?d=1`. The
results differ from the scope above in two ways.

Apple's finder does follow these when the character sits in or just
after the scheme. It gave `https://evil.com/steal?d=1` for each of the
first seven rows.

relay's half already breaks the host's dots in all of them, whether or
not it knew the scheme, so the finder followed nothing in relay's
output. relay-tui's breaker left six whole, and the finder followed all
six.

| Where the character sits | As written | After relay | After relay-tui |
| --- | --- | --- | --- |
| U+0301 after `https:` | followed | `https:`+U+0301+`//evil[.]com/…`, not followed | whole, followed |
| U+1161 after `https:` | followed | host broken, not followed | whole, followed |
| U+1107F after `https:` | followed | host broken, not followed | whole, followed |
| U+1BC9D after `https:` | followed | host broken, not followed | whole, followed |
| U+FE0F U+20E3 after `https:` | followed | host broken, not followed | whole, followed |
| U+0301 between the two slashes | followed | `hxxps:/`+U+0301+`/evil[.]com/…`, not followed | whole, followed |
| U+0301 inside `https` | followed | `htt`+U+0301+`ps[:]//evil[.]com/…`, not followed | the same as relay, not followed |
| U+0323 inside `www` | not followed | `ww`+U+0323+`w[.]evil[.]com/…` | whole, not followed |
| U+0301 inside a bare host | not followed | `evil`+U+0301+`[.]com/…` | whole, not followed |
| U+0301 inside the ending, no path | not followed | `evil[.]co`+U+0301+`m` | whole, not followed |

With U+0301 after `https:`, relay leaves three things as written:

- the scheme, which stays `https:` where the same address without the
  mark gets `hxxps:`;
- a host with no dot: `localhost`, `3232235777`, `0xC0A80001`,
  `[2001:db8::1]`;
- a one-dot host that ends like a file, with nothing after it:
  `evil.md`, `setup.sh`, `notes.zip`, `notes.py`.

Apple's finder followed none of those three with the mark in place.
Without the mark relay breaks `https://3232235777/steal?d=1` and
`https://evil.md`.

Not run: a terminal's link finder, Proton Mail, Android, and a phone
(the finder ran on macOS only). I did not run the Markdown case with
`*` through relay-tui's reply path; I ran `Defang` on its own.

The scratch scripts are not in the repo. The finder is ten lines of
Swift around `NSDataDetector(types: .link)`, the one used for relay#124.

## Acceptance criteria

A first draft, mine, for whoever builds this to tighten.

- [ ] relay-tui: none of the inputs in the table, and none of the
  reviewer's, reaches the screen from a contaminated session with an
  address that a finder skipping zero-width characters would follow. A
  test covers each one and judges with its own rule for what is skipped,
  not with `Defang`'s.
- [ ] relay-tui: the Markdown case, `https:*`+U+FE0F+U+20E3+
  `//evil.test*`, is drawn by the reply path with no such address.
- [x] relay: the same inputs go through `relay.text/for-operator` in a
  test. The three things relay leaves as written are broken, or the Log
  says why each may stay.
  - Receipt: `relay.text-test/a-mark-in-an-address-does-not-hide-it-from-the-breaker`,
    the table's rows and the reviewer's, and the three things under
    "the three things relay used to leave as written".
- [ ] `hidden?` takes U+1107F and U+1BC9D in relay and in relay-tui, or
  the Log says why not. (relay's half done:
  `relay.text-test/the-two-marks-that-draw-as-nothing-are-hidden`.)
- [x] Ordinary text in scripts that use marks comes out unchanged. A
  test covers combining marks on Latin letters, a script with vowel
  signs and conjoining jamo.
  - Receipt: `relay.text-test/ordinary-text-outside-ascii-comes-out-as-written`
    (Latin with marks, Devanagari, Thai, Hebrew, jamo, Cyrillic, Chinese).
    A dot with a name after it in such a word is now broken, by Kira's
    choice in relay#121; see the Log.
- [x] The fuzz's alphabet in scripts/inert-text-fuzz.clj has a combining
  mark, U+1107F, U+1BC9D and keycap glue, and the Log says what Apple's
  finder followed in relay's output.
  - Receipt: the Log, 2026-11-15, the relay#118 session.
- [x] The Log says which terminal's link finder was run against these,
  or that none was.
  - Receipt: none was run. Ghostty's pattern was read; see the Log.
- [ ] Kira has confirmed or changed the triage in the Log: in scope as
  hidden text, relay's half P2, relay-tui's half ahead of it, and
  relay#104 as the root fix with no stopgap in relay-tui.
- [ ] `bb verify` passes, and an adversarial Codex review finds nothing
  in scope.

## Plan

Related: relay#95, where it was found. relay#118 and relay#126 are the
other link-breaking work. relay#104 matters here because conversation
text reaches a client unbroken by relay, so for that text relay-tui's
breaker is the only one.

## Log

- 2026-11-15, from the coordinating session. It withdraws "an address
  written this way does not work in ordinary readers" from its triage in
  the Context. Apple's finder follows seven of the forms as written.
  What holds is that relay's own breaker stops all of them, because it
  breaks a host's dots whether or not it knew the scheme, and
  relay-tui's does not.
- 2026-11-15, the coordinating session's triage, still for Kira to
  confirm. relay's half is P2: the three things relay leaves as written,
  `hidden?` taking the two characters, and the fuzz alphabet. relay-tui's
  half matters more, since for conversation text its breaker is the only
  one and a second client is being written.
- 2026-11-15, the coordinating session's recommendation. The root fix is
  relay#104, in place of a second and a third port of the breaker. With
  conversation text broken in relay's proxy by `relay.text`, the one
  breaker that is fuzzed against Apple's finder covers every client, and
  a client's own breaker becomes a second line. It recommends no stopgap
  in relay-tui before that. It is putting this to Kira and asking the
  relay#118 session whether it can take relay#104.
- 2026-11-15, from the relay#93 session. This issue's review round found
  four more classes of address that Apple's finder follows in relay's
  output, each needing a character outside ASCII. Kira chose to keep
  relay's exemptions for words in ASCII only. Her decision, and the rule
  the relay#118 session is building from it in this chain, are in
  relay#121 under Decision.
- 2026-11-15, the relay#118 session: relay's half, built on four
  commits (pnlmpvsl, qrrlrtyo, nyklpzmn, tropuwkx).
  - The fuzz first. With combining marks, U+1107F, U+1BC9D and keycap
    glue in its alphabet, Apple's finder followed 36 things in relay's
    own output over three seeds (1,350,000 cases), every one holding a
    mark: the table holds, but a mark also beat relay's exemptions
    (`a@` + U+0301 + `a-b.a1.rs` was left as a mail address's domain,
    and the finder followed the host). So relay's half broke I10 on
    trunk. The coordinating session now reads it as P1, for Kira to
    confirm.
  - pnlmpvsl: a word with a mark gets no exemption; its schemes are
    judged as written and with marks out, and where those differ it is
    shown without them, broken as the address it spells. This settles
    the three things left as written. `hidden?` takes U+1107F and
    U+1BC9D. The fuzz also writes emoji held by a kept joiner and shows
    each string a fourth way, as relay#104 will send it; a joiner alone
    got nothing followed, before or after.
  - Review round 1 (a fresh read-only reviewer, not Codex, with Apple's
    finder and a sweep of every code point in 20 places): four older
    classes, all outside ASCII and all followed: a superscript or
    another script after an ending (`example.com²`), a modifier letter
    or spacing mark in a mail domain (`a@ʹevil.com`), a Hangul filler
    escaped after breaking, circled and squared letters. Also: breaking
    was not idempotent after a dot.
  - Kira was asked how to decide which dotted words to leave alone, and
    chose exemptions for printable ASCII only (relay#121, Decision).
    nyklpzmn builds it: any other word has a dot broken wherever a name
    could go on after it; a full stop that ends a sentence stays.
    Words are parted at any space. scripts/inert-text-sweep.clj puts
    every assigned code point into 34 places of an address: 14,631,118
    cases, none followed. The fuzz draws from all of Unicode now.
  - qrrlrtyo: from the relay#95 session's port, `x[.]ssh:u@evil.test`.
    Apple's finder followed nothing web in it. Ghostty's pattern
    (src/config/url.zig, read today; no terminal was run) has no left
    boundary and takes `ssh:` mid-word, so slashless and short web
    schemes are broken after anything but an ASCII letter or digit.
    That also fixed round 1's idempotence finding.
  - Review round 2 (another fresh reviewer): every ASCII pair and
    triple in the places that matter, about 20M cases, old against new
    on 1.2M ASCII strings, its own sweep of 9.6M. One finding, older
    than the chain and in ASCII: a mail domain whose label starts or
    ends with `-` or `_`, or holds `--` (`a@-evil.com`), is no mail
    address to Apple's finder, which follows the host. tropuwkx takes a
    domain only when every label is well formed, and the fuzz makes
    the shape now (297 followed on the code before, none after).
  - Not done here: relay-tui's half, held by the coordinating session
    until Kira answers on relay#104; Kira's word on the triage.
  - `bb verify --relay-tui-dir` on an export of relay-tui `integration`
    (a2ce30c4), tip tropuwkx: agentloop, relay-tui, catalog, webtool,
    eventkit and fuzz pass (400,000 cases, none followed); relay 1,232
    of 1,233 and the pentests 30 of 31, the two failures being
    relay#99's.
- 2026-11-15, the relay#118 session: review round 3 (a fresh reviewer)
  found three more ways into the mail-domain exemption, each followed
  by Apple's finder: U+202F before an address, a digit, comma and digit
  before the `@` (`12,5@host`), and a domain of some 300 characters
  with a hyphen. Three rounds, none clean, so it went to Kira with the
  root cause: every exemption predicted an undocumented link finder.
  Her answer: "we should never be trying to reverse engineer
  undocumented standards or protocols, as a general rule." She approved
  replacing the exemptions.
  - nyklpzmn (now "Break every dot a host name could have, and
    recognise no names") holds the new rule and absorbs the ASCII-only
    commit and the mail-domain one. In outside text a dot with a letter,
    or anything but a digit, after it is broken unless only punctuation
    follows to the word's end, because every top-level domain begins
    with a letter. Dotted quads are broken on their own. The ASCII-only
    rule recorded in relay#121 is superseded.
  - Review round 4, on the new rule (a fresh reviewer, aimed at the
    digit assumption): no in-scope findings, over some 1.2M cases
    through all four ways, 0 followed. Two observations out of its
    scope. The planned conversation path's strip keeps unassigned code
    points in the emoji blocks, so relay#104 must still escape them.
    And `éwww.` now counts as an address, which is stricter.
  - The sweep on the new rule: 14,631,118 cases, none followed. `bb
    verify --relay-tui-dir` on an export of relay-tui `integration`
    (a2ce30c4), on the final chain: agentloop, relay-tui, catalog,
    webtool, eventkit and fuzz pass (400,000 cases, none followed);
    relay 1,232 of 1,233 and the pentests 30 of 31, the two failures
    being relay#99's.
  - Still open here: relay-tui's half (held by the coordinating session
    until Kira answers on relay#104), and Kira's word on the triage.
- 2026-11-16, the relay#95 session (relay-34): relay-tui's half as the
  coordinating session recorded it in relay#104's Log is on relay-tui's
  `integration`, qxlyolmz cb8f80d7. With `relay/inertText` relay-tui
  draws a contaminated session's text as relay sent it (no breaker of
  its own, no DropHidden), takes a reply's links only from relay's
  `relay/links`, and keeps `gate` last; without the flag it is as
  before. relay's conversation fuzz at relay-fb's last commit (200,000
  cases) through it: 0 rows followable, 0 frames gate changed. Full
  `bb verify` passed every step; a read-only review found nothing in
  scope.
- What this does not yet meet, for whoever picks up the rest: the first
  two criteria ask for a test whose judge skips zero-width characters,
  combining marks included; relay-tui's tests read past format
  characters and variation selectors but not marks. relay-tui's
  `core.DropHidden` (now used only against an older relay) does not
  yet take U+1107F and U+1BC9D. Kira's confirmation of the triage, and
  a Codex round (its quota is out until 2026-11-19), are still open.

