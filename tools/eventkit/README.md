# eventkit

A small Swift command-line tool that reads Apple Calendar and manages Apple
Reminders through EventKit. relay runs it; you can also run it by hand.
It replaces the old `cal` and `rem` tools.

Build with `./build.sh` (output: `tools/eventkit/eventkit`, git-ignored).
Run the tests with `./test.sh`. They compile only `Core.swift` and touch
neither Calendar nor Reminders.

## Commands

Every command except `help` prints one JSON object on stdout. On failure it
prints `{"error": <text>, "code": <code>}` and exits 1. The codes are
`usage`, `no-access`, `not-found`, `refused` and `failed`. Options are
strict, and an unknown one is a `usage` error.

    eventkit access
    eventkit calendars
    eventkit events --from <instant> --to <instant> [--calendar <title>]...
    eventkit lists
    eventkit reminders --list <title>...
    eventkit --allow <title>... add --list <title> --title <text> [--due <due>] [--notes <text>]
    eventkit --allow <title>... set <id> [--title <text>] [--due <due> | --clear-due] [--notes <text> | --clear-notes] [--list <title>]
    eventkit --allow <title>... done <id>
    eventkit --allow <title>... reopen <id>

Times: an instant is UTC, `2026-10-28T14:00:00Z`. An all-day event has
date-only `start` and `end` (`2026-10-28`), and `end` is exclusive. A
reminder's `due` is null, a date, or a local wall time with no zone,
`2026-10-28T09:00`.

`events` expands repeating events into occurrences. Each has a `key`, the
event's external identifier plus `@` plus the occurrence start. `reminders`
returns every unfinished reminder in the lists plus those completed in the
last 14 days. A title that matches no list is `not-found`. If two lists
share a title, the command is a `usage` error.

Ids are exact. There are no prefixes.

`set` clears a due date with `--clear-due` and notes with `--clear-notes`.
Each conflicts with its value flag (`--due`, `--notes`): giving both is a
`usage` error. No value is magic, so notes that read `none` are just notes.

## Writes and --allow

`--allow <title>` goes before the command word, once per list. A write is
refused (`refused`) unless the reminder's current list (`set`, `done`,
`reopen`) and the destination list (`add`, `set --list`) are all allowed.
`--allow` after the command word is a `usage` error, and the read commands
do not take it.

## Alarms

`add` with a timed due date gives the new reminder one alarm relative to
its due date (offset 0), which follows the due date by itself if it moves.
No command ever adds or removes an alarm on an existing reminder: `set`
changes the due date only, so an alarm you set stays exactly as you set it.
(An undo has nothing to put back for alarms, because the before-image holds
none.)

## No delete

There is no delete command, and the code calls no EventKit method that
removes anything, an alarm included (`removeAlarm` appears nowhere). Calendar events are read-only. A reminder that should go
away is completed instead.

## Access

macOS asks for Calendar and Reminders access once per binary. Run
`eventkit access` in a terminal (relay wraps it as `relay eventkit-access`)
and click Allow. Other commands never prompt. If access is missing they fail
with `no-access`.

A rebuild changes the binary's signature, so macOS may ask again. Run
`eventkit access` after rebuilding.

On start, eventkit re-runs itself with TCC "responsibility" disclaimed
(`responsibility_spawnattrs_setdisclaim`, a private libSystem call). Without
that, macOS would attribute the access to the parent process (Terminal, bb,
the brew service), and a grant made in a terminal would not carry over to the
service. The copy it spawns is marked by a `--disclaimed` first argument, not
an environment variable. `help` and usage errors skip the re-run.
