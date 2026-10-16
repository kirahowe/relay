# Support reminder sections in rem

## Context

EventKit exposes no sections, so rem cannot see or set which section of a Reminders list an item is in. Kira wants both reading and writing. Start with a spike that reports which route is feasible. The Reminders store is never written to directly.

## Acceptance criteria

- [ ] Spike covers: macOS 26 EventKit headers, Shortcuts, a read-only look at the Reminders SQLite store, AppleScript.
- [ ] The spike reports, per route, whether reading and writing sections works and how reliable it is.
- [ ] Never write the Reminders SQLite store directly; a read-only open is the most that route is used for.
- [ ] Implementation scope is decided after the spike and recorded here; it is undecided now.

## Plan

## Log
