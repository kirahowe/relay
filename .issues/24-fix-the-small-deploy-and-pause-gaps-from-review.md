# Fix the small deploy and pause gaps from review

## Context

Round 9 of the staging and deploy review (2026-10-17) converged with these NITs. None breaks a guarantee. Each leaves a state Kira can recover by hand.

- A Ctrl-C between registering the pause and writing the pause file leaves relay paused.
- The notice text does not check the pause file.
- `*inherited-pause*` is read before the lock is taken.
- A Ctrl-C during the wake wait prints nothing.

## Acceptance criteria

- [ ] Each case has a test, or a one-line reason it is not worth fixing.

## Plan

## Log
