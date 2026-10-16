# Developing relay

This file is for agents working on the relay repo. The role instructions that relay gives its own roles are in `resources/AGENTS.md`, a different file.

## Where to work

Work in a jj workspace under `~/code/projects/.next/`, or another directory outside the live checkout. Never work in `~/code/projects/relay` itself: launchd's `relay.tick` runs that checkout every minute against Kira's live data.

Never read or write `~/Sync/relay` or `~/.config/relay`. Test with artificial data and pass an explicit `--data <dir>` to every relay command. Commit in your workspace's own chain of changes. Do not push and do not move `main`.

## What to work on

Take work from the issues tracker: `issues --dir <workspace> next`. Start an issue with `issues start <id>`. Record judgment calls in the issue's details file as you make them, with the reason. When the work is verified, update the details and move the issue to review. Kira closes it.

## Verification

`bb test` runs everything. Once the staging relay and scenario runner exist, run the scenarios too. Fix a failing test; do not skip it.

Security-relevant changes get an adversarial review loop with Codex until it converges: changes to labels, holds, tools, anything that touches the network, and anything that touches the operator's approvals. Run it read-only:

    CODEX_HOME=~/.codex-kira codex exec --sandbox read-only "<review prompt>"

Triage each finding: fix it, or reject it with a written reason in the issue. Repeat until Codex reports nothing left.

## What needs Kira

Ask; do not decide, on these:

- Changes to what a label means, or to when a call is held.
- Any new paid service or account.
- Anything that touches `~/Sync/relay`, the live checkout, launchd jobs or the live ntfy server.
- Deploying. Kira runs `bb deploy` and `bb rollback`; agents never do.
- Pushing to GitHub.
- Anything that cannot be undone.

Everything else is yours to decide. Record why in the issue and move on.

## Engineering rules

- No environment variables for configuration or wiring. Use config files or explicit arguments. A minimal base env (PATH, HOME) for child processes is fine.
- Functional core, imperative shell. Decisions are pure functions; IO sits at thin edges. Writes go through `relay.inbound`, and rendering is data.
- Write property-based tests wherever a property is natural: parsers, round trips, state machines.
- Every bug gets a test that would have caught it.
- Update `README.md`, `resources/AGENTS.md` and `spec/relay.allium` when behaviour they describe changes.
- Kill processes by PID, never with `pkill` or `killall`.
