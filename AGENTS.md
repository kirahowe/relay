# Developing relay

This file is for agents working on the relay repo. The role instructions that relay gives its own roles are in `resources/AGENTS.md`, a different file.

## Where to work

Work in a jj workspace under `~/code/projects/.next/`, or another directory outside the live checkout. Never work in `~/code/projects/relay` itself: launchd's `relay.tick` runs that checkout every minute against Kira's live data.

Never read or write `~/Sync/relay` or `~/.config/relay`. Test with artificial data and pass an explicit `--data <dir>` to every relay command. Commit in your workspace's own chain of changes. Do not push and do not move `main`.

## What to work on

Take work from the issues tracker: `issues --dir <workspace> next`. Start an issue with `issues start <id>`. Record judgment calls in the issue's details file as you make them, with the reason. When the work is verified, update the details and move the issue to review. Kira closes it.

## Threat model

relay is a personal tool. It runs on Kira's always-on Mac, and the only
way in from outside is Kira's phone over the tailnet. Judge every design
and every review finding against this section.

relay must make these structurally impossible, or hold them for Kira's
approval, rather than asking a model to avoid them:

- A mistake that exposes Kira's private data, acts in Kira's name outside
  the house, spends money, or destroys data. The mistake can be Kira's, a
  role's, or an agent's working on relay. Prefer designs where a mistake
  stays recoverable: rem cannot delete, the log is append-only.
- Generic attacks from the internet: prompt injection in web pages, mail,
  documents and repositories, and crawlers or agents probing anything
  reachable. Assume such text reaches roles.
- The widely used ways of hiding such text, such as invisible Unicode,
  lookalike characters and links that carry data out.

relay does not defend against:

- An attacker who studies relay's code to target Kira.
- Anyone with a shell on the Mac, access to Kira's accounts, or a device
  on the tailnet that is not Kira's.
- A tool Kira configures with the wrong class or labels, or the model
  provider itself. Both are trusted.
- Covert channels of low bandwidth (timing, number encodings, fractional
  seconds) and races that need precise timing.
- Two unlikely failures at once, such as kill -9 during a Ctrl-C during a
  deploy. For those it is enough to leave a state Kira can recover by
  hand, and to say so.

## Verification

`bb test` runs everything with volley (parallel workers; config in `tests.edn`). A test that uses a fixed port or a shared file must be marked `^:volley/serial`. Once the staging relay and scenario runner exist, run the scenarios too. Fix a failing test; do not skip it.

Security-relevant changes get an adversarial review loop with Codex until it converges: changes to labels, holds, tools, anything that touches the network, and anything that touches the operator's approvals. Run it read-only:

    CODEX_HOME=~/.codex-kira codex exec --sandbox read-only "<review prompt>"

Give the reviewer the threat model above. Triage each finding against it. A finding is in scope when a plausible mistake or an untargeted attack could trigger it. Fix an in-scope defect that breaks a guarantee. File other in-scope findings as issues with the reviewer's text. Reject out-of-scope findings with a one-line reason that names the part of the threat model. Stop when a round has no in-scope defect. After three rounds that still find one, stop and ask Kira, since the design may be wrong. The goal is a sound implementation for relay's use, not a reviewer with nothing left to say.

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
