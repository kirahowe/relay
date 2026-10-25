# Review what the operator consents to on a screen of its own, with text relay made

## Context

2026-10-24. Five Codex rounds on relay-tui's saved view and navigation
(relay#74, #77) kept finding ways the dashboard's layout could show the
operator less than they were approving: a tracker grid cutting a
contaminated row, option labels capped at two rows, over-indented input
clipped by its box, a review heading skipping defang, escaping that made
a real control and its spelled-out form look the same. Each fix was
right and the next round found another path, because the claim being
proved ("the operator saw all of this before pressing y") was being
proved from a renderer built for browsing.

Kira agreed to separate the two jobs: the dashboard browses and may be
lossy; consent gets its own full-screen review, and relay owns the text
consented to, so every client (relay-tui, the phone later, a web client)
only has to show a text verbatim. The contract is the "Consent" section
of ~/code/projects/.next/DASHBOARD.md.

## Acceptance criteria

- relay attaches review text and digest to permission requests, card
  wire forms and doc items, and refuses an allow (or trust) whose digest
  is not the one it sent, for clients that declare relay/review.
- relay-tui answers and confirms only from the review pager, whose lines
  are the review text exactly, after every page was drawn, with a live
  key past the arm delay.
- Plain ACP agents still work: relay-tui builds the review text itself.
- The inline armable/certification machinery is gone.
- A Codex loop over the result converges.

## Log

- Generic ACP clients that do not declare relay/review (Agmente) are
  handled as before: relay cannot know what they show. Known gap, to
  revisit with relay#66.
