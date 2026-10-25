# Read the log's decimals exactly

## Context

2026-10-24, found by Codex round 4 on relay#79. relay.log reads each event
with cheshire's parse-string, which reads every decimal as a double. A
value written as 12345678901234567.0 comes back as 1.2345678901234568E16,
so state rebuilt from the log differs from the state relay decided, for
any decimal a role or a tool supplied.

relay#79 fixed the case that mattered for consent: a hold now keeps its
input as JSON text (:input-json, :effective-input-json on hold/opened),
and the review reads that text with the strict reader. Other decimals in
events (tool inputs in other events, costs, figures from reports) are
still read as doubles.

Reading the log with bigdecimals is not a drop-in change: Clojure's
(= 1.0M 1.0) is false, so every reader that compares a number from the
log with one computed in memory (spend, costs, tests that compare decided
and replayed state) has to be audited. relay#59 is the same problem for
MCP structured results.

## Acceptance criteria

- The log reads decimals and large integers exactly, or every place that
  needs exactness keeps the text as holds now do.
- Decided state and state replayed from the log are equal for events
  with decimals (a property test).
- The numbers relay sums and shows (spend) still compare and add up.

## Log
