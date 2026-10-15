// rem: a small command-line front end to Apple Reminders, through EventKit.
// It reads, adds, edits and completes reminders. It never deletes one.

import EventKit
import Foundation

let usage = """
rem: read and manage Apple Reminders

  rem --mode read|write|write-shared|all <command> ...
  The operator fixes --mode in the tool's configured command; it must come
  first and cannot be repeated. read: lists, ls, show. write: add, set,
  done, and never to a shared or subscribed list. write-shared: as write,
  shared lists allowed. all: everything.

  rem lists                           every list, with its unfinished count
  rem ls [--list <name>] [--today]    unfinished reminders: id, list, due, title
                                      --today is what the Today smart list shows:
                                      due today or overdue
  rem show <id>                       one reminder in full, with notes
  rem add <title> --list <name> [--due <when>] [--notes <text>]
  rem set <id> [--title <t>] [--due <when>|none] [--list <name>] [--notes <text>]
  rem done <id>                       mark it completed

  <when> is YYYY-MM-DD, or "YYYY-MM-DD HH:MM" for a timed reminder.
  An id may be shortened to any prefix that matches one unfinished reminder.
"""

func fail(_ message: String) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(1)
}

final class Box<T>: @unchecked Sendable {
    var value: T
    init(_ value: T) { self.value = value }
}

let store = EKEventStore()

func requestAccess() {
    let done = DispatchSemaphore(value: 0)
    let granted = Box(false)
    store.requestFullAccessToReminders { ok, _ in
        granted.value = ok
        done.signal()
    }
    done.wait()
    if !granted.value {
        fail("No access to Reminders. Grant it in System Settings > Privacy & Security > Reminders.")
    }
}

func fetch(_ predicate: NSPredicate) -> [EKReminder] {
    let done = DispatchSemaphore(value: 0)
    let found = Box([EKReminder]())
    store.fetchReminders(matching: predicate) { reminders in
        found.value = reminders ?? []
        done.signal()
    }
    done.wait()
    return found.value
}

func unfinished(in calendars: [EKCalendar]? = nil, today: Bool = false) -> [EKReminder] {
    var end: Date? = nil
    if today {
        let start = Calendar.current.startOfDay(for: Date())
        end = Calendar.current.date(byAdding: .day, value: 1, to: start)
    }
    return fetch(store.predicateForIncompleteReminders(
        withDueDateStarting: nil, ending: end, calendars: calendars))
}

func list(named name: String) -> EKCalendar {
    let matches = store.calendars(for: .reminder).filter {
        $0.title.caseInsensitiveCompare(name) == .orderedSame
    }
    if matches.isEmpty { fail("No list named \"\(escape(name))\". Run `rem lists`.") }
    if matches.count > 1 { fail("More than one list is named \"\(name)\".") }
    return matches[0]
}

func reminder(withId id: String) -> EKReminder {
    if let exact = store.calendarItem(withIdentifier: id) as? EKReminder { return exact }
    let matches = unfinished().filter { $0.calendarItemIdentifier.hasPrefix(id.uppercased()) }
    if matches.isEmpty { fail("No unfinished reminder has an id starting \"\(escape(id))\".") }
    if matches.count > 1 { fail("\(matches.count) reminders have an id starting \"\(escape(id))\". Give more of it.") }
    return matches[0]
}

func dueString(_ reminder: EKReminder) -> String {
    guard let due = reminder.dueDateComponents, let year = due.year,
          let month = due.month, let day = due.day else { return "-" }
    let date = String(format: "%04d-%02d-%02d", year, month, day)
    guard let hour = due.hour, let minute = due.minute else { return date }
    return date + String(format: " %02d:%02d", hour, minute)
}

func dueComponents(_ text: String) -> DateComponents? {
    if text == "none" { return nil }
    let numbers = text.split(whereSeparator: { "- :T".contains($0) }).map { Int($0) }
    guard numbers.count == 3 || numbers.count == 5, !numbers.contains(nil) else {
        fail("Cannot read \"\(text)\" as a date. Use YYYY-MM-DD or \"YYYY-MM-DD HH:MM\".")
    }
    var due = DateComponents()
    due.calendar = Calendar.current
    due.year = numbers[0]; due.month = numbers[1]; due.day = numbers[2]
    if numbers.count == 5 { due.hour = numbers[3]; due.minute = numbers[4] }
    guard due.isValidDate else { fail("\"\(text)\" is not a real date.") }
    return due
}

func setDue(_ reminder: EKReminder, _ text: String) {
    let due = dueComponents(text)
    reminder.dueDateComponents = due
    // A timed reminder alerts at its time. A dated one just appears in Today.
    for alarm in reminder.alarms ?? [] { reminder.removeAlarm(alarm) }
    if let due, due.hour != nil, let date = Calendar.current.date(from: due) {
        reminder.addAlarm(EKAlarm(absoluteDate: date))
    }
}

func save(_ reminder: EKReminder) {
    do { try store.save(reminder, commit: true) } catch { fail("Could not save: \(error.localizedDescription)") }
}

func traits(_ calendar: EKCalendar) -> ListTraits {
    let name: String
    switch calendar.type {
    case .local: name = "local"
    case .calDAV: name = "calDAV"
    case .exchange: name = "exchange"
    case .subscription: name = "subscription"
    case .birthday: name = "birthday"
    @unknown default: name = "unknown"
    }
    return ListTraits(isSubscribed: calendar.isSubscribed, typeName: name,
                      allowsContentModifications: calendar.allowsContentModifications,
                      isDelegateSource: calendar.source?.isDelegate ?? false)
}

func markers(_ calendar: EKCalendar) -> [String] { sharingMarkers(traits(calendar)) }

func line(_ reminder: EKReminder) -> String {
    reminderLine(id: String(reminder.calendarItemIdentifier.prefix(8)), list: reminder.calendar.title,
                 due: dueString(reminder), title: reminder.title ?? "", markers: markers(reminder.calendar))
}

func requireWritable(_ calendar: EKCalendar) {
    if let reason = sharedRefusal(mode: mode, listName: calendar.title, traits: traits(calendar)) { fail("rem: \(reason)") }
}

let invocation: Invocation
switch parse(Array(CommandLine.arguments.dropFirst())) {
case .usage:
    print(usage)
    exit(0)
case .refused(let reason):
    fail("rem: \(reason)")
case .run(let parsed):
    invocation = parsed
}
let mode = invocation.mode
let flags = invocation.flags
let target = invocation.target

if invocation.command == .help {
    print(usage)
    exit(0)
}

requestAccess()

switch invocation.command.rawValue {
case "lists":
    let counts = Dictionary(grouping: unfinished(), by: { $0.calendar.calendarIdentifier })
    for calendar in store.calendars(for: .reminder).sorted(by: { $0.title < $1.title }) {
        print(listLine(count: counts[calendar.calendarIdentifier]?.count ?? 0, title: calendar.title, markers: markers(calendar)))
    }

case "ls":
    let calendars = flags["list"].map { [list(named: $0)] }
    let reminders = unfinished(in: calendars, today: flags["today"] != nil)
    for reminder in reminders.sorted(by: {
        ($0.calendar.title, dueString($0), $0.title ?? "") < ($1.calendar.title, dueString($1), $1.title ?? "")
    }) {
        print(line(reminder))
    }

case "show":
    guard let target else { fail("rem show <id>") }
    let found = reminder(withId: target)
    for text in showLines(id: found.calendarItemIdentifier, title: found.title ?? "", list: found.calendar.title,
                          markers: markers(found.calendar), due: dueString(found), done: found.isCompleted,
                          notes: found.notes) { print(text) }

case "add":
    guard let target, let listName = flags["list"] else { fail("rem add <title> --list <name> [--due <when>] [--notes <text>]") }
    let destination = list(named: listName)
    requireWritable(destination)
    let new = EKReminder(eventStore: store)
    new.title = target
    new.calendar = destination
    new.notes = flags["notes"]
    if let due = flags["due"] { setDue(new, due) }
    save(new)
    print(writeResultLine(verb: "Added", id: String(new.calendarItemIdentifier.prefix(8))))

case "set":
    guard let target else { fail("rem set <id> [--title <t>] [--due <when>|none] [--list <name>] [--notes <text>]") }
    let found = reminder(withId: target)
    requireWritable(found.calendar)
    if let title = flags["title"] { found.title = title }
    if let listName = flags["list"] {
        let destination = list(named: listName)
        requireWritable(destination)
        found.calendar = destination
    }
    if let notes = flags["notes"] { found.notes = notes }
    if let due = flags["due"] { setDue(found, due) }
    save(found)
    print(writeResultLine(verb: "Updated", id: String(found.calendarItemIdentifier.prefix(8))))

case "done":
    guard let target else { fail("rem done <id>") }
    let found = reminder(withId: target)
    found.isCompleted = true
    save(found)
    print(writeResultLine(verb: "Completed", id: String(found.calendarItemIdentifier.prefix(8))))

default:
    fail("rem: unreachable command")
}
