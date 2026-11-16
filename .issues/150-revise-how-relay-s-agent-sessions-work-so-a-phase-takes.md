# Revise how relay's agent sessions run a phase

## Context

Kira, 2026-11-17, ending relay#93's phase: "no more scope creep, no more
rounds. anything outstanding gets flagged to investigate later, we are
wrapping up this phase. it's been 3 days, we obviously need to revise
the process."

Filed by the coordinating session, relay-32. What it saw over those
three days, for whoever takes this up:

- Review loops ran to the three-round limit on relay#94, #95, #100,
  #118, #130 and #106. After the first round, most findings were low or
  about wording, and each fix gave the next reviewer something new to
  read.
- Follow-ups grew the work as it went: relay#104, #120, #126, #130,
  #134 and #136 came out of other issues' reviews and were built in the
  same phase.
- Landing waited on Kira: sessions' permission settings refused the
  `integration` bookmark move and live evals, and every session waited
  on her prompt.
- Every code chain waited for the coordinating session to read it.
- Several sessions shared the default checkout. On 2026-11-16 a commit
  from another session ("Triage before anything blocks, and build the
  slice first") landed on `integration` underneath the coordinator's
  commit without being read.

Already under way: relay's AGENTS.md now points at factory's
docs/process.md (triage at the end of each phase and before each
dispatch, build the first slice first), and a further change naming
factory's triage workflows is waiting off trunk (nyymowkn).

## Acceptance criteria

- [ ] Kira has chosen what changes, and relay's AGENTS.md (or factory's
  process) says it.

## Plan

## Log
