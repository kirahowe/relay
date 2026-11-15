# Carry a live link in a push as an action, and check the readers nobody has run

## Context

Sent by the relay#94 session on 2026-11-14, at the coordinating
session's request. The text is that session's own. It waits on Kira and
is not ready to build.

relay#94 shows a live link in outside text between angle brackets,
`<https://x.example/a>`, and holds it to a shape that every common link
finder reads whole. That rests on a link finder reading text. Three
terminals were found to read a bracketed link differently (Ghostty,
kitty, WezTerm), which is why the coordinating session has asked Kira
whether terminals should show the broken form inside an OSC 8 hyperlink
instead (relay#95 for the dashboard, the same question for `relay
ready`, `relay cards` and `relay inbox`).

A push has the same weakness and a way out. Its body is read by whatever
link finder the ntfy app uses on the phone, which nobody has run. ntfy
can carry a click target and up to three action buttons, so the body
could be the broken form with each address as an action. That changes
how a push looks and caps a push at three links, so it is Kira's to
decide.

## What has been checked, and what has not

- Apple's NSDataDetector: run. Of 600,000 random bracketed addresses it
  followed all 188,377 of relay's shape exactly
  (`scripts/link-finder-fuzz.swift`).
- Ghostty: its matcher expression was run by relay#95's reviewer, not
  the app.
- kitty, WezTerm, iTerm2, Alacritty, VTE: read from their rules, not
  run.
- Not checked at all: the app Kira reads relay's mail in on her phone
  (Apple Mail, or Proton's own app, which has its own link finder);
  whatever shows an ntfy push on her phone; an ssh client on the phone
  running `relay ready`.

## For Kira

1. Which app do you read relay's mail in on your phone?
2. Should a push carry its links as ntfy actions, with the body in the
   broken form?

## Acceptance criteria

- [ ] Kira has answered both questions, and the answers are recorded
  here.
- [ ] If she reads mail in an app other than Apple Mail, its link finder
  is run against relay's link shape, or mail stops depending on one.
- [ ] If she says yes to 2: a push shows no followable text, each of up
  to three live links is an action that opens exactly that address, and
  a message with more says so.

## Plan

## Log
