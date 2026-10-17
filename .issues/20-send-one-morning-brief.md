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

2026-10-17, built as planned. Decisions, with reasons:

- A missed tick sends the brief late the same day, at the first tick after its time. A day with no tick past its time gets no brief, and a later day never sends an earlier day's: yesterday's calendar is no use this morning.
- The record is one `brief/composed` event, appended with the notification under the log's lock, so a restart neither repeats nor skips a day. A tick that fails before the append is followed by one that tries again. An empty brief is recorded too (`:sent false`), so cal and rem run once a day, not once a minute after 07:30.
- The brief runs the tools named `cal` and `rem-read` unless `:brief` names others, as `cal events --from <today> --to <today>` and `rem-read ls --today` (due today or overdue, as the Today list shows). It runs only `:read` tools on the plain protocol, since no one is there to approve a call; config validation refuses a named tool that is not `:read`.
- relay.tools' plain runner has no time limit, and a cal stuck on a permission prompt would hang every tick. So relay.dispatch runs the tool the same way (configured command, arguments after it, harness/base-env, nothing on stdin) with a one-minute limit, rather than calling the private tools/external-tool. What a failed tool prints goes to the tick's stderr only.
- A failed or unreadable tool makes its section say unavailable and counts as something to send. A tool that is not in relay.edn says not configured and does not by itself make a brief worth sending, since it would say the same thing every day.
- The notification carries the labels of what it shows: the calendar tool's labels only when an event is listed. The push edge (relay.notify) then marks a contaminated brief and breaks its URLs, as for any other notification, so render does not defang a second time (defang is not idempotent).
- Each section shows at most eight lines and each title, list or calendar name at most 80 characters.
- Cal prints times in the system zone and the brief reads today's date in relay's `:timezone`. They are the same on Kira's Mac; a mismatch would only shift which day is asked for.
- No scenario: scenarios can only merge `:grants` into the staging relay.edn and staging defines no cal or rem stand-ins, so one would need changes to scenario.clj or staging.clj, which another session owns. relay.brief-test drives the tick end to end with stand-in scripts instead.
