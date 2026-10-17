# Run agentloop's live smoke tests from a flag, not an env var

## Context

This is agentloop work tracked here. The live smoke tests are gated by AGENTLOOP_LIVE=1 and have never run. Kira wants no environment variables for wiring, so the gate becomes a make target or a test flag, and the API key comes from the keychain through a command.

## Acceptance criteria

- [ ] A make target (or test flag) in agentloop runs the live smoke tests; AGENTLOOP_LIVE is gone.
- [ ] The key comes from the keychain (service relay-anthropic) via a command, not an env var.
- [ ] Run once successfully against a cheap model (claude-haiku-5-5) with artificial data; the result is recorded in the Log.
- [ ] Which of make target or test flag is better is undecided; record the choice.

## Plan

## Log

- 2026-10-17: landed in agentloop main (xqykmpps, pslwzqwx). `make live` takes no env var. The live test fails without a thinking block.
