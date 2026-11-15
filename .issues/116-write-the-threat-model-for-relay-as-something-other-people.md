# Write the threat model for relay as something other people install

## Context

Sent by the relay-macos session on 2026-11-14, through the coordinating
session. The text is that session's own.

Kira, 2026-11-14, in the relay-macos session: "i do want to turn this
into a product others can use." The threat model in AGENTS.md is for one
operator, and two of the things it leaves out stop holding when many
people run the same public code.

- "An attacker who studies relay's code to target Kira." An injection
  written against relay's own prompts then works on every install, so it
  is a generic attack.
- "A tool Kira configures with the wrong class or labels." Someone who
  signs up cannot be asked whether a remote tool is `edit` or `egress`.
  `relay connect` leaves that choice to the operator today, so a
  connector would have to arrive with its classes set.

## Acceptance criteria

- [ ] A threat model for relay as a product is written down, and says
  which of today's exclusions still hold.
- [ ] What it asks of relay has issues.

## Plan

## Log
