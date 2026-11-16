# Make relay's docs and relay-tui agree on Markdown backslash escapes

## Context

Sent by relay-34 on 2026-11-16, from relay-tui qxlyolmz. Cosmetic.

relay#104 escapes CommonMark's `](`, `]:` and `<` in a contaminated
session's text and doubles every backslash, and docs/client-protocol.md
says a Markdown client reads each escape as the character it escapes.
relay-tui's Markdown renderer draws backslash escapes and entities
literally, so `]\(` and `\\` show as written. relay-macos renders no
Markdown and shows relay's text as sent.

## Acceptance criteria

- [ ] Either relay-tui's renderer reads CommonMark's backslash escapes,
  or docs/client-protocol.md says what a client that does not should
  show; the choice and the reason are recorded here.

## Plan

## Log
