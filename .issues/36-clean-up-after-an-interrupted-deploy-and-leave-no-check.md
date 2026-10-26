# Clean up after an interrupted deploy, and leave no check dirs in TMPDIR

## Context

On 2026-10-17 a `bb deploy --paused-externally` was stopped with SIGTERM
during `bb test`. Its cleanup threw `NoSuchFileException` from
`fs/delete-tree` (deploy_run.clj, run-command) on a file under the check
dir's agentloop copy that was already gone, then printed "The check
workspaces could not all be forgotten" with commands to run. When run, jj
said each workspace was already forgotten, so the message was wrong.

`$TMPDIR` also held a dozen `relay-deploy-check-*` dirs, likely from test
runs. Nothing has confirmed where they came from.

## Acceptance criteria

- [ ] Deleting the check dir tolerates files that vanish mid-walk.
- [ ] The interrupted-deploy message lists only workspaces that are still
      registered.
- [ ] Tests and deploys leave no `relay-deploy-check-*` dirs behind.

## Plan

## Log

## Dropped (2026-10-26)

bb deploy and its pause file are gone (relay#88). relay runs from its
Homebrew install, and an upgrade is `brew upgrade` plus `brew services
restart relay`, so there is no deploy to stop serve or to clean up after.
