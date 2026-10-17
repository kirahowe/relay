# Show spend per role per week in relay status

## Context

Visibility only, with no limits. agentloop reports cost for each run.

## Acceptance criteria

- [x] `relay status` shows spend per role per week.
- [x] No limits or enforcement.
- [x] Pure aggregation function with property tests.

## Plan

## Log

- 2026-10-17: Each wake keeps agentloop's usage and cost_usd from its
  outcome line, and a conversation the sum of the last cost each of its
  sessions gave in a usage_update. An unknown cost stays nil.
- 2026-10-17: relay.spend/weekly sums cost per role per week, weeks
  starting Monday 00:00 in relay's zone, as BigDecimal so a total is
  exactly the sum of its parts. `relay status` shows this week per role,
  the total, and the four weeks before. A wake that ran without a cost
  is counted beside the sum, never as 0. A wake that never ran (rate
  limited, say) and has no cost is not counted at all.
