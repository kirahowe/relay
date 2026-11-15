# Decide how far outside text is broken: file names that are also hosts, and schemes that are not web addresses

## Context

Sent by the relay#118 session on 2026-11-15, from relay#118's first
review round. The text is that session's own, and "I" below is that
session. It waits on Kira.

relay breaks every web address in text an outsider may have shaped.
relay#118's review found two places where relay left something a link
finder follows, and I fixed the cases I could check against Apple's
NSDataDetector: a web scheme written short (`https:/x.pl`,
`https:x.pl`), and a one-dot name ending in `pl`, `rs` or `so`, which
relay took for a file name and the finder follows as a host.

Two wider classes are left as written, on purpose, and are Kira's to
decide.

1. A one-dot name whose ending is both a file's and a domain's:
   `todo.md`, `setup.sh`, `notes.py`, `x.ps`, `x.bb`, `x.zip`, `x.mov`.
   Apple's finder does not follow these bare; I ran it over every ending
   relay treats as a file's. Other readers may (Android's link finder
   goes by the list of real domains, and Proton Mail's has not been
   run). Such a name cannot carry data out, since anything after it that
   only a link has (a path, a port, a query) already gets it broken; the
   risk is a followable link to a site an outsider named. The cost of
   breaking them is that file names in outside text read `todo[.]md`,
   and Kira's mounted folders are outside text, so she would see that
   often.

2. Schemes that are not web addresses, with no `//`: `mailto:`, `tel:`,
   `sms:`, and a bare mail address. Apple's finder makes each a link (a
   click starts a mail, a call or a message, after its own prompt).
   relay shows them as written, and shows a sender's address on purpose
   ("Drew on: a mail from dana@example.org").

## For Kira

1. Should a name like `todo.md` be broken in outside text, or left as
   written unless a reader she uses is shown to follow it?
2. Should `tel:`, `sms:` and `mailto:` in outside text be broken? A bare
   mail address would stay.

## Decision

Kira's, on 2026-11-15. The relay#118 session asked her in a dialog and
sent the relay#93 session the dialog's text, which is quoted below. The
rest reached me through the coordinating session. I did not see the
dialog myself.

relay#130's review round found four more classes of address that Apple's
finder follows in relay's output. Every one needs a character outside
ASCII:

- `example.com²` and `evil.com第1`;
- a modifier letter or a spacing mark in a mail address's domain, 1,108
  code points;
- a Hangul filler escaped after the word was judged;
- circled and squared letters, such as `ⓔⓥⓘⓛ.com`.

The relay#118 session put three choices to Kira, with examples of how
each reads. She chose exemptions for ASCII only and typed no note. The
dialog as that session sent it:

The question, under the header "Exemptions":

> relay#130's review found four more ways a link survives relay's
> breaking in outside text, all using non-ASCII characters and all
> confirmed against Apple's link finder (e.g. `example.com²` with a
> footnote digit, `a@ʹevil.com`, `ⓔⓥⓘⓛ.com`). relay leaves file names
> and mail addresses unbroken, and it keeps disagreeing with link
> finders about what those are once Unicode is involved. How should
> relay decide which dotted words to leave alone?

Option 1, "ASCII-only exemptions (Recommended)":

> A file name or mail address is left alone only when the whole word is
> printable ASCII. Any word with a character outside ASCII gets its dots
> broken. Fixes all four at the root and gives the fuzz an alphabet
> small enough to cover. Cost: `résumé.pdf`, `отчёт.pdf` and
> `jörg@müller.de` always read with `[.]`; plain English text is
> unchanged.

Its preview:

```
todo.md -> todo.md
a@x.example -> a@x.example
résumé.pdf -> résumé[.]pdf
отчёт.pdf -> отчёт[.]pdf
jörg@müller.de -> jörg@müller[.]de
example.com² -> example[.]com²
ⓔⓥⓘⓛ.com -> ⓔⓥⓘⓛ[.]com
“It ended.” she said -> unchanged
€12.50 -> €12[.]50
```

Option 2, "Patch the four shapes":

> Keep exemptions for non-ASCII names and fix each shape the reviewer
> found one by one (footnote digits, modifier letters after an @, Hangul
> fillers, circled letters). `résumé.pdf` stays whole. This is the
> approach the last three review rounds kept finding holes in, so
> another round may find a fifth.

Option 3, "No exemptions at all":

> Break every dot that a letter or digit follows in outside text, ASCII
> included. Nothing is left to get wrong and no pattern to review, but
> every file name and mail address a scout or outside mail mentions
> reads broken.

Her answer: "ASCII-only exemptions (Recommended)", with that option's
preview selected.

The rule as the relay#118 session is building it in relay#130's chain:

- A word that holds anything outside printable ASCII gets no exemption
  as a file name, a mail address, a time or an amount.
- A dot in such a word is broken unless the end of the word, a space or
  punctuation follows it.
- Marks are still judged for a scheme they hide.
- The fuzz then draws random code points from all of Unicode.

The coordinating session's notes:

- Of relay#118's eight review findings, none needed a character outside
  ASCII, so this rule does not touch that class. It bounds what the fuzz
  has to cover.
- The cost falls on text in other scripts, so the rule is worth another
  look under the public-agent goal.

### Replaced later the same day

Kira replaced the rule above later on 2026-11-15. The choice above is
kept as she made it. She said this to the relay#118 session on
relay#130, and the coordinating session passed it on to the relay#93
session:

> we should never be trying to reverse engineer undocumented standards
> or protocols, as a general rule

The new rule is the relay#118 session's nyklpzmn (9f14d9ff), "Break
every dot a host name could have, and recognise no names". As the
coordinating session described it:

- In outside text relay recognises no names: no file names and no mail
  addresses.
- Every dot with anything but a digit after it is broken, unless only
  punctuation follows to the end of the word.
- Dotted quads are broken too, and schemes are broken as before.

So mail addresses and file names now read with `[.]`, as in
`todo[.]md`, while `12.50`, `v1.2`, times and a sentence's full stop
stay as they are.

## Acceptance criteria

- [ ] Kira has answered both, and the answers are recorded here and in
  relay#118.
- [ ] If Proton Mail's link finder is run for relay#117, the same run
  says what it follows among these names and schemes.

## Plan

## Log

- 2026-11-15, from the relay#93 session. relay#95's confirm round found
  the second class wider than the three schemes named here: relay also
  leaves `ssh:user@host` and `magnet:` whole, and Ghostty's link finder
  takes `mailto:`, `file:`, `ssh:`, `tel:`, `magnet:` and `news:` with
  no slashes. I filed those others as a bug, relay#126, since relay
  shows none of them on purpose, and left `mailto:`, `tel:` and `sms:`
  to this question. relay#126 has a table of what relay shows for each
  and what Apple's finder follows. If Kira would rather decide the whole
  class here, relay#126 narrows to the web schemes written short.
- 2026-11-15, from the relay#93 session, on what the Decision leaves
  open here. Kira's choice keeps the exemption for a file name written
  in ASCII, and the preview she picked shows `todo.md` left as written.
  I read that as her answer to the first question, since she passed over
  "No exemptions at all", the option that would have broken it. She was
  not asked the question in its own words. The second question, about
  `tel:`, `sms:` and `mailto:`, is untouched and still waits on her. The
  Proton Mail run in the second criterion is not done either. The issue
  stays open.
- 2026-11-15, from the relay#93 session. The first criterion asks for
  the answers in relay#118 as well. The rule is being built in
  relay#130's chain, so I linked this Decision from relay#130 and did
  not copy it into relay#118, which is in review.
- 2026-11-15, from the relay#93 session. Kira's later rule, under
  Decision, supersedes my reading above: `todo.md` now reads
  `todo[.]md`, so the first question is answered the other way, by her
  rule and not by my reading. The second question is not answered in
  those words either. Under the new rule the dots in a `mailto:`
  address are broken, since no mail address is recognised; `tel:` and
  `sms:` with no dot are not touched by it, as I read the rule. So the
  question of breaking those schemes still waits on her, and the Proton
  Mail run is still not done. The issue stays open.
