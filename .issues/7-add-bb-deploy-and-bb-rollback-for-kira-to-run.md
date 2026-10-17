# Add bb deploy and bb rollback for Kira to run

## Context

Deploys are Kira's to run. Agents never run these tasks against the live checkout. The tasks gate on tests and staging scenarios, then move the live checkout to main.

## Acceptance criteria

- [ ] `bb deploy` requires tests and staging scenarios to pass first.
- [ ] It moves the live checkout to main, rebuilds agentloop and acp-tui, and checks that `relay status` loads.
- [ ] It records the previous commits of each repo so `bb rollback` can restore them.
- [ ] `bb rollback` restores the recorded commits and rebuilds.
- [ ] AGENTS.md says agents never run either task.

## Plan

## Log

- 2026-10-17: landed on main with the staging work (rntkuwvy, xlvnmvsp): the pause file, `bb deploy` and `bb rollback`. The round-9 NITs are filed separately.
