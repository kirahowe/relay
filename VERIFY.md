# Verifying relay

`bb verify` runs every check relay has, in order, and ends with one table.
It exits non-zero if any step failed or could not run. Run it from the relay
checkout, with agentloop, relay-tui and webtool checked out beside it:

```sh
bb verify                 # every step except the live evals, about 10 minutes
bb verify --list          # the steps
bb verify --only relay    # one step, or several: --only relay,pentest
bb verify --live          # add the live evals, which cost up to --max-cost dollars (default 2)
```

`--agentloop-dir`, `--relay-tui-dir` and `--webtool-dir` name other
checkouts, and `--cleanroom` names a cleanroom build for the mail pentests,
which skip without it. Every relay step uses the agentloop build that the
first step made, so the tests check the binaries that sit side by side,
not whatever is on PATH.

## What each step shows

| Step | Command | What it shows |
|------|---------|---------------|
| agentloop | `make test && make build` | The harness enforces the label rule, the reader, the workbench sandbox, MCP and the display channel by itself, with the race detector on. |
| relay-tui | `make check && make build` | The chat client and the workspace pane render only what relay sent, strip control characters, and take approvals only from the operator. |
| webtool | `make test && make build && make fixture` | Search, ask and fetch refuse anything not plainly on the public internet. The fixture build is the one the pentests use. |
| relay | `bb test --agentloop …` | relay's own rules, the model-based property over random command sequences, and the agentloop integration against a mock model. |
| scenarios | `bb scenarios --agentloop …` | Every end-to-end scenario plays through on staging data against the mock model. |
| pentest | `bb pentest --jobs 4 …` | The built system holds up when attacked from outside, on artificial data, assuming the model obeys the attacker perfectly. |
| eval | `bb eval --live …` | Roles do their jobs well on the real model: they cite, keep skills, speak plainly, and keep quiet when nothing needs the operator. |

Nothing here reads `~/Sync/relay` or `~/.config/relay`, sends a
notification, or touches Reminders or the keychain, except that the live
evals read the API key from the keychain.

## Before handing work back

1. Run `bb verify`. Every step must pass.
2. For a change to labels, holds, tool classes, the sandbox, anything that
   touches the network or the operator's approvals, run the Codex review
   loop that `AGENTS.md` describes, until a round finds no in-scope defect.
3. For a change to what roles are told or how they behave, run the eval
   cases it affects live (`bb eval <case> --live`) and record the results in
   `test/scenarios/BASELINES.md`.
4. Every bug fixed gets a test or a pentest scenario that would have caught
   it.
