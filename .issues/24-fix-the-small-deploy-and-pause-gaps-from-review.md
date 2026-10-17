# Fix the small deploy and pause gaps from review

## Context

Round 9 of the staging and deploy review (2026-10-17) converged with these NITs. None breaks a guarantee. Each leaves a state Kira can recover by hand.

- A Ctrl-C between registering the pause and writing the pause file leaves relay paused.
- The notice text does not check the pause file.
- `*inherited-pause*` is read before the lock is taken.
- A Ctrl-C during the wake wait prints nothing.

## Acceptance criteria

- [x] Each case has a test, or a one-line reason it is not worth fixing.

## Plan

## Log

2026-10-17: All four fixed, each with a test in deploy_test that fails
on the old code (checked by running them against the previous
deploy_run.clj).

1. Pause registered, file not yet made. Registering `:pause` and
   writing the file now happen under one monitor (`pause-gate`), and the
   hook takes it for its swap. A stop that comes first means the file is
   never made; one that comes second waits for the file and lifts it.
   Test: `a-stop-between-registering-the-pause-and-making-it-lifts-it`
   runs the hook on another thread while `pause!` is held up.
2. The notice checked nothing. The notice is now one function
   (`paused-notice`) that prints only while the pause file is there.
   Test: `the-interrupt-notice-says-paused-only-while-it-is` stops a
   lift-only rollback just after it removed the file.
3. `*inherited-pause*` before the lock. It is read again once the lock
   is held, inside `with-lock`. The early read stays for the preflight
   refusals, which only use it for advice text. Test:
   `the-pause-is-read-again-once-the-lock-is-held` makes the pause
   appear just before the lock is taken.
4. Ctrl-C during the wake wait printed nothing. The hook now says
   "Interrupted before any checkout was touched. Nothing was changed and
   relay is not paused." whenever it lifted the run's own pause, which
   it only holds before the first touch. If lifting fails it falls back
   to the paused notice. Test: the wake-wait case of
   `an-interrupt-before-anything-is-touched-lifts-the-pause-and-the-workspaces`
   now checks the message. README and the deploy_run docstring say so.

