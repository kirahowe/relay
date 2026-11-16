# Check what Proton Mail on the phone follows in relay's mail

## Context

Sent by the relay#94 session on 2026-11-14 as a wider issue, "Carry a
live link in a push as an action, and check the readers nobody has
run". The half about a push is now part of relay#118. This issue keeps
the question about readers.

relay#118 makes shown text inert in every channel and carries a live
link as data. Mail is the one channel where that still ends in text: an
item's live links follow it on lines of their own, one address to a
line, and the mail app's link finder decides what is followed.

Kira reads relay's mail in Proton Mail on her phone (her answer on
2026-11-14). Proton's app has its own link finder, and nobody has run
it.

What the relay#94 session had checked for a link shown in text, and
what it had not:

- Apple's NSDataDetector: run. Of 600,000 random bracketed addresses it
  followed all 188,377 of relay's shape exactly
  (`scripts/link-finder-fuzz.swift`).
- Ghostty: its matcher expression was run by relay#95's reviewer, not
  the app.
- kitty, WezTerm, iTerm2, Alacritty, VTE: read from their rules, not
  run.
- Not checked at all: the app Kira reads relay's mail in on her phone;
  whatever shows an ntfy push on her phone; an ssh client on the phone
  running `relay ready`.

Once relay#118 is built, a terminal gets the broken form inside an OSC 8
hyperlink and a push gets actions, so of that list only the mail app
still reads an address as text.

## Acceptance criteria

- [x] Kira has said which app she reads relay's mail in on her phone.
  - Receipt: "I use protonmail on my phone", Kira in the relay#93
    session, 2026-11-14.
- [ ] Proton Mail's link finder is checked against the link lines
  relay#118 puts in a mail, and it follows exactly the address on each
  line. Or mail stops depending on a link finder.

## Plan

Needs relay#118 for the form of the lines. Two ways to do the check,
both mine:

- Send a test mail with link lines of relay's shape and look at what
  Proton Mail makes followable on the phone. That needs Kira's phone.
- Give mail an HTML part beside the plain one, with each address in the
  markup of a link, so that no finder decides. That is a change to
  relay#118's "still text/plain" and belongs to whoever builds it.

Proton publishes the source of its phone apps, which may show what finds
the links before anything is run.

## Log

- 2026-11-14: the relay#93 session put both of the first version's
  questions to Kira. She answered the one about mail, above. On a push
  she left the call to the session; that is recorded in relay#118.
- 2026-11-15: unblocked; relay#118 is done (Kira accepted it). The
  Proton Mail run needs Kira's phone.
