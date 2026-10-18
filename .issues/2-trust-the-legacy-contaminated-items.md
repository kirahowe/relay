# Trust the legacy contaminated items

## Context

Some items predate the trifecta rollout and are marked contaminated. `relay trust` needs a code typed at a TTY, so only Kira can clear them. The items are: note hub/onboarding, fact 5, doc operator/working-sessions.md, and the roles career-coach and life-coach.

## Acceptance criteria

- [ ] Kira runs `relay trust` on each of the five items and reads the text first.
- [ ] `relay status` no longer lists them as contaminated.

## Plan

## Log

2026-10-18: Dropped. Kira chose a fresh start over trusting the items: the
old data dir is archived at ~/Sync/relay-archive-2026-10-18, and the new
~/Sync/relay began empty with only the hub, re-added from setup/hub.md
with --public. None of the legacy items exist there.
