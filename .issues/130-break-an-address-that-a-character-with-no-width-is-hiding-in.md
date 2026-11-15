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
relay#95's landing nor the surface work.

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
- [ ] relay: the same inputs go through `relay.text/for-operator` in a
  test. The three things relay leaves as written are broken, or the Log
  says why each may stay.
- [ ] `hidden?` takes U+1107F and U+1BC9D in relay and in relay-tui, or
  the Log says why not.
- [ ] Ordinary text in scripts that use marks comes out unchanged. A
  test covers combining marks on Latin letters, a script with vowel
  signs and conjoining jamo.
- [ ] The fuzz's alphabet in scripts/inert-text-fuzz.clj has a combining
  mark, U+1107F, U+1BC9D and keycap glue, and the Log says what Apple's
  finder followed in relay's output.
- [ ] The Log says which terminal's link finder was run against these,
  or that none was.
- [ ] Kira has confirmed or changed the triage: in scope as hidden text,
  P2.
- [ ] `bb verify` passes, and an adversarial Codex review finds nothing
  in scope.

## Plan

Related: relay#95, where it was found. relay#118 and relay#126 are the
other link-breaking work. relay#104 matters here because conversation
text reaches a client unbroken by relay, so for that text relay-tui's
breaker is the only one.

## Log

