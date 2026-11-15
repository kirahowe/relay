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
checkouts. Every relay step uses the agentloop build that the
first step made, so the tests check the binaries that sit side by side,
not whatever is on PATH.

## What each step shows

| Step | Command | What it shows |
|------|---------|---------------|
| agentloop | `make test && make build` | The harness enforces the label rule and forbid_labels, MCP and the display channel by itself, with the race detector on. |
| relay-tui | `make check && make build` | The dashboard renders only what relay sent and strips control characters. |
| catalog | `cmp` on `resources/a2ui/relay_catalog.json`, then `diff -r` on `testdata/surfaces`, each against relay-tui's copy | relay-tui's copy of the A2UI catalog is the one relay checks a role's surface against, byte for byte, and the surface cards its renderer tests draw are the ones relay sends. |
| webtool | `make test && make build && make fixture` | Search, ask and fetch refuse anything not plainly on the public internet. The fixture build is the one the pentests use. |
| eventkit | `tools/eventkit/test.sh && tools/eventkit/build.sh && bb … relay.eventkit-contract …` | The EventKit binary parses strictly, refuses a write outside its `--allow` lists and has no delete. relay's tests use a fake binary, so the last part runs the real one on the arguments relay builds for a read (never a write) and fails if it cannot parse them. |
| relay | `bb test --agentloop …` | relay's own rules, the model-based property over random command sequences, the agentloop integration against a mock model, and every end-to-end scenario played through on staging data against the mock model. |
| fuzz | `scripts/inert-text-fuzz.sh` | Text relay shows from outside holds no link that Apple's link finder, the one Mail and the phone use, can follow: 100,000 random hostile strings, each as mail, a client and `relay ready` show it. The seed is the clock's and is printed. A hit prints what was written and what was shown; add that as a test in `relay.text-test` before fixing it. |
| pentest | `bb pentest --jobs 4 …` | The built system holds up when attacked from outside, on artificial data, assuming the model obeys the attacker perfectly. |
| eval | `bb eval --live …` | Roles do their jobs well on the real model: they cite, keep skills, speak plainly, and keep quiet when nothing needs the operator. |

Nothing here reads `~/Sync/relay` or `~/.config/relay`, sends a
notification, or touches Reminders or the keychain, except that the live
evals read the API key from the keychain.

The relay and pentest steps each hold one check on a real container: the
shell's live test and `s18-shell-walls`. Both need Apple's container
service, which `container system start` starts. While it is stopped each
of the two fails in one line that says so, and the run fails. `bb verify`
says it before the first step and again under the table.

The fuzz step makes new strings every run, so it can fail on a change
that never went near link breaking. A hit is a link that mail or the
phone would follow, in code that is already on trunk. File it with the
seed and the string the run prints, and do not rerun until it passes. A
change that touches none of `relay.text`, `relay.messages` and
`relay.render` is not held back by a hit that has been filed. Nothing
reaches `main` on a run with one.

## Before handing work back

1. Run `bb verify`. Every step must pass.
2. For a change to labels, tool classes, the sandbox or anything that
   touches the network, run the Codex review
   loop that `AGENTS.md` describes, until a round finds no in-scope defect.
3. For a change to what roles are told or how they behave, run the eval
   cases it affects live (`bb eval <case> --live`) and record the results in
   `test/scenarios/BASELINES.md`.
4. Every bug fixed gets a test or a pentest scenario that would have caught
   it.
