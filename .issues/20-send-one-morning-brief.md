# Send one morning brief

## Context

One hub routine batches the things Kira needs to see in the morning into a single message instead of several.

## Acceptance criteria

- [ ] A hub routine sends one brief covering today's calendar, reminders due, reports waiting and held calls.
- [ ] It sends nothing when all four are empty (undecided; confirm with Kira).
- [ ] The brief depends on the calendar tool for its calendar part; until it exists the section is omitted.

## Plan

Decided 2026-10-17 (Kira said to proceed as I see fit; Kira can overturn this):

- relay composes the brief itself at a configured time, `:brief {:at "07:30"}` in relay.edn. No model wakes. A pure function in relay.render builds the text from state plus the cal and rem output, and the shell sends it through the existing notifier. This costs no tokens. It also keeps calendar invites, which are outsider text, away from any role's prompt.
- Sections: today's calendar, reminders due today, reports waiting for review, held calls. Counts plus short lines, escaped the way operator views already are.
- When all four are empty it sends nothing. The routine form in the original acceptance criteria is replaced by this config key.
- If cal or rem fails, the brief says that section is unavailable rather than dropping it silently.
- Wait for relay-23's cost work to land before starting. Both touch core.clj.

## Log
