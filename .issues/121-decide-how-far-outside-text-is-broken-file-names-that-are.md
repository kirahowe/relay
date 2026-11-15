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

## Acceptance criteria

- [ ] Kira has answered both, and the answers are recorded here and in
  relay#118.
- [ ] If Proton Mail's link finder is run for relay#117, the same run
  says what it follows among these names and schemes.

## Plan

## Log
