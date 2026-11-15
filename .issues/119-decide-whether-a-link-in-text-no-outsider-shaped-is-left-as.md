# Decide whether a link in text no outsider shaped is left as written

## Context

Sent by the coordinating session on 2026-11-14. The text is that
session's own. The question came out of the second Codex round on
relay#100, and relay#118's wording raises it too.

relay breaks a link only in text an outsider may have shaped. A card, a
surface or a message whose text carries no outside label shows a link
exactly as the role wrote it, and a terminal's or a mail app's own link
finder can follow it. That is the rule today for every card, and
relay#118 (links as data) keeps it: its wording is "every link in
outside text is broken", and it lists as data only the live links that
outside text holds.

relay#100's Codex review raised the case this leaves open: a role that
has read no outside text builds an address that holds private data, such
as a search or a map link with something of Kira's in it, and shows it.
Nothing outside shaped it, so relay shows it whole, and a click sends it
to that host. No attacker is involved; it is the role's own mistake, and
Kira's click on an address she can read is the only check.

The same text also meets the finder problem relay#118 removes for
outside text: a finder may cut a clean link differently from how it was
written (Ghostty stops at an apostrophe), though every character in it
then comes from the same trusted writer.

## For Kira

Two rules are possible.

- As today: text no outsider shaped is left as written. A role can hand
  Kira a working link in its own words, and relay vouches for nothing
  about it.
- All shown text is inert: every link is broken wherever it appears, and
  each address a text holds is listed as data with where it came from
  (the web showed it to a scout, Kira kept it, relay built it, or a role
  wrote it with no outside text). A client follows only a listed
  address, so Kira sees the origin before she clicks.

The second changes what the absence of an outside label means for what
is shown, so it is hers to decide. The coordinating session's
recommendation is to keep today's rule while relay#118 lands, and to
decide this after it, when the list of links with their origins exists
and the second rule would be a small step.

## Acceptance criteria

- [ ] Kira has decided, and the decision is recorded here, in relay#118
  and in docs/architecture.md.

## Plan

## Log
