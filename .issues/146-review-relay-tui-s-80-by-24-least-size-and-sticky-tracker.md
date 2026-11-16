# Review relay-tui's 80 by 24 least size and sticky tracker header

## Context

Sent by relay-54 on 2026-11-17, as an optional follow-up to relay#110.
Kira chose to land these without it, so this waits on her in the inbox.

Two commits of relay#110 landed without a review round of their own:
oyposnpp "Require a terminal of at least 80 by 24" and rrnwtulr "Keep a
tracker's column names in view as its rows scroll". The rest of
relay#110 had six rounds; see its Log. oyposnpp ignores every key but
ctrl+c below 80 by 24 (Model.update in internal/ui/model.go) and deletes
the hub's narrow layout and what only it used (internal/render/hub.go).
rrnwtulr pins a tracker grid's header row in cardDraw
(internal/render/pane.go).

## Acceptance criteria

- [ ] A read-only review round on both commits (an agent reviewer
  counts, by Kira's word of 2026-11-17), briefed with the 80 by 24 least
  size as a given and with pre-existing findings filed rather than
  fixed, finds no in-scope defect, or what it finds is fixed or filed.
- [ ] No key, paste or mouse event acts below the least size, in either
  mode, including while connecting and while keys are held.

## Plan

## Log
