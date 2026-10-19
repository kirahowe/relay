# Haiku leaves the memo or journal undone before an act call

## Context

Found by the concert-email-drafted-for-approval eval (relay#46). An act
call is held, and the session pauses there until the operator decides.
resources/AGENTS.md says to finish what you can before you call, and the
case's skill says to close the memo and write the journal before the send.

Haiku 5.5 at effort low gets everything else right but drops the closing
steps. Run `20261019T015404Z-2c31` (records in
`~/code/projects/.relay-evals/`), 3 trials, 0 passed:

- trial 1: memo_send, memo_close, then the send. No journal.
- trial 2: memo_send, then the send. The memo stayed open and there was no
  journal.
- trial 3: memo_send and memo_close in one turn, then the send. No journal.

In every trial the draft, the placeholder, the refs, the memo's wording and
the held send with its preview were right. Opus 5.5 at medium passed 3/3
(run `20261019T015502Z-9090`), journaling before the send each time.

On the first run (`20261019T015228Z-94da`, before the skill said the call
is the ask), Haiku twice never made the send call at all. It asked for
approval in a memo and said it would propose the send afterwards.

The memo and journal are not lost: when the operator decides, the session
resumes and can close them. So this may be a grading question as much as
a behaviour one.

## Acceptance criteria

- [ ] Decide whether a held wake should be graded on closing and journaling before the act call, or after it resumes
- [ ] If before: change the shared instructions (or the hold result the model sees) until Haiku passes the case at least 2/3
- [ ] If after: grade the case across the resumed wake, with a scripted approval step

## Plan

One cheap thing to try: the text agentloop returns for a held call could
say "Close your memos and write your journal now; this session pauses
here." The model reads that after it calls, so it only helps when other
calls follow in the same turn.

## Log
