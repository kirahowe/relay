# Report check workspaces a Ctrl-C could not forget

## Context

From the review of relay#24 (lywtwxkr, 2026-10-17). It is low impact: no data is lost, and the leftover is one entry in jj's workspace list of a live checkout.

On Ctrl-C during verification, the interrupt hook prints "Nothing was changed and relay is not paused" even when `jj workspace forget` failed for a check workspace. The `:workspaces` cleanup registered in `verify!` (deploy_run.clj) ignores jj's result, and `run-exit-cleanup!` ignores what `run!` returns for `:workspaces`. On the normal path, `verify!` lists stuck workspaces with the commands to forget them by hand. The hook says nothing changed instead.

## Acceptance criteria

- [x] A failed forget during an interrupt is reported with the by-hand commands, as on the normal path.
- [x] A test covers it.

## Plan

## Log

- 2026-10-17: verify! now builds one `forget-all!` that forgets every check workspace and returns the by-hand commands for any it could not, or nil. The finally block and the interrupt hook both call it, so the two paths print the same advice. The hook keeps "Nothing was changed" only when every workspace was forgotten; otherwise it says relay is not paused and lists the commands.
- 2026-10-17: Verification runs before the pause is made, so a Ctrl-C there usually has no pause to lift. The hook then prints "Interrupted." with the commands. Before, it printed nothing in that case.
- 2026-10-17: Test: deploy_test `an-interrupt-reports-check-workspaces-it-could-not-forget` interrupts during the test commands with every forget failing, and checks the hook's own branch for a lifted pause. `bb test relay.deploy-test`: 80 tests, 0 failures.
