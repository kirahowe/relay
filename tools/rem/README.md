# rem

A small command-line front end to Apple Reminders, built on EventKit. It
reads, adds, edits and completes reminders. It cannot delete anything, by
design: no command removes a reminder or a list, so a mistake stays
recoverable.

## Build

```sh
tools/rem/build.sh
```

This needs the Swift compiler (Xcode or the Command Line Tools) and
produces `tools/rem/rem`, which is git-ignored. `Info.plist` is linked into
the binary so macOS can show the Reminders usage description.

## Access

macOS asks once per binary for permission to use Reminders. The grant is
tied to the binary, so rebuilding or moving it may prompt again. If the
first run comes from an unattended process, the prompt may never appear
and access fails. Run any command that touches reminders once in an
interactive terminal and accept the prompt first. `rem help` does not need
access.

## Modes

The operator picks what `rem` may do with `--mode`, in the tool's
configured command. It must be the first argument. Relay appends the
model's arguments after the configured command, so a second `--mode`, or
`--allow-shared`, anywhere later is refused rather than honoured. Without
a mode `rem` refuses to run (only a bare `rem help` works).

| Mode | Commands | Shared lists |
| --- | --- | --- |
| `read` | help, lists, ls, show | readable |
| `write` | help, add, set, done | add and set refuse them |
| `write-shared` | as `write` | add and set allowed |
| `all` | everything | allowed |

`write-shared` stands in for an `--allow-shared` flag. A separate flag in
the configured command would sit in front of the model's arguments, where
the model could supply its own, so the permission is part of the mode
value instead, which can appear only once.

Options are strict: an unknown option is an error, so nothing the model
adds can be read as configuration. The parser, the mode check and the
output escaping live in `Core.swift`, which does not import EventKit.

## Sharing

EventKit exposes these list properties: `isSubscribed`, `type`
(`subscription`, `birthday`, `calDAV`, ...), `allowsContentModifications`,
and the source's `isDelegate`. `rem` turns them into a marker column on
`lists`, `ls`, `show`, `add` and `set`: `-` for none, otherwise a comma
list of `subscribed`, `delegate` and `read-only`. In `write` mode, `add`
and `set` refuse a destination list, or the list a reminder is already in,
that has any marker.

EventKit has no public property that says an ordinary iCloud list is
shared with other people. Such a list looks the same as a private one, so
a `-` marker does not mean nobody else can see it, and `write` mode cannot
refuse it. Do not rely on the markers for that. Class the read tool as
contaminated (below) and treat every write as possibly reaching other
people.

## Output

Titles, list names and notes can come from other people. Control
characters, bidi and zero-width characters, line and paragraph
separators, non-breaking and other unusual spaces, private-use and
unassigned characters are shown as `\u{XXXX}`; tab, newline and carriage
return as `\t`, `\n`, `\r`; a backslash as `\\`. A title therefore cannot
add a field or a line. Notes in `show` are printed one escaped line each,
prefixed with `> `.

## Relay configuration

Two tools over the one binary, so reads and writes get different classes
and labels (see `setup/relay.example.edn`):

```clojure
:tools
{"rem-read" {:class :read
             :labels ["private" "contaminated"]
             :description "Read Apple Reminders. Run with [\"help\"]."
             :command ["/path/to/relay/tools/rem/rem" "--mode" "read"]}
 "rem"      {:class :act
             :labels ["private"]
             :description "Change Apple Reminders. Run with [\"help\"]."
             :command ["/path/to/relay/tools/rem/rem" "--mode" "write"]}}
```

## Tests

`tools/rem/test.sh` compiles `Core.swift` with `tests/main.swift` and runs
it. It covers mode gating, override attempts, sharing classification and
escaping, including randomised checks that hostile titles keep a line's
field count. It never touches EventKit or asks for Reminders access.

## Commands

Output is plain text, one item per line, with tab-separated fields. The
last field of `lists` and `ls` lines is the sharing marker. The write
commands print only a verb and the reminder's id (`Added 4F2A9C1B`), never
its title or list name: those may hold text other people wrote, and a
write should hand the session nothing it did not already know.
Commands below are shown without the mode prefix.

- `rem help` prints every command.
- `rem lists` prints each list with its unfinished count and sharing marker.
  Example: `rem lists`
- `rem ls [--list <name>] [--today]` prints unfinished reminders as id,
  list, due date, title and sharing marker. `--today` shows what the Today smart list
  shows: due today or overdue.
  Example: `rem ls --list Groceries --today`
- `rem show <id>` prints one reminder in full, with notes.
  Example: `rem show 4F2A9C1B`
- `rem add <title> --list <name> [--due <when>] [--notes <text>]` creates a
  reminder and prints `Added <id>`.
  Example: `rem add "Buy milk" --list Groceries --due "2026-01-15 09:00"`
- `rem set <id> [--title <t>] [--due <when>|none] [--list <name>] [--notes <text>]`
  edits a reminder and prints `Updated <id>`. `--due none` clears the due date.
  Example: `rem set 4F2A9C1B --due 2026-01-16`
- `rem done <id>` marks a reminder completed and prints `Completed <id>`.
  Example: `rem done 4F2A9C1B`

`<when>` is `YYYY-MM-DD`, or `"YYYY-MM-DD HH:MM"` for a timed reminder. A
timed reminder also gets an alert at that time. An id can be shortened to
any prefix that matches exactly one unfinished reminder.
