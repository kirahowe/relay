# Report check workspaces a Ctrl-C could not forget

## Context

From the review of relay#24 (lywtwxkr, 2026-10-17). It is low impact: no data is lost, and the leftover is one entry in jj's workspace list of a live checkout.

On Ctrl-C during verification, the interrupt hook prints "Nothing was changed and relay is not paused" even when `jj workspace forget` failed for a check workspace. The `:workspaces` cleanup registered in `verify!` (deploy_run.clj) ignores jj's result, and `run-exit-cleanup!` ignores what `run!` returns for `:workspaces`. On the normal path, `verify!` lists stuck workspaces with the commands to forget them by hand. The hook says nothing changed instead.

## Acceptance criteria

- [ ] A failed forget during an interrupt is reported with the by-hand commands, as on the normal path.
- [ ] A test covers it.

## Plan

## Log
