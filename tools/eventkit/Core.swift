// The pure part of eventkit: argument parsing, the --allow rule, date and
// time syntax, and the JSON shapes. Nothing here imports EventKit or touches
// the outside world, so tests/main.swift can compile it alone.
//
// eventkit has no delete command, and calendar events are read-only.

import Foundation

// MARK: errors

enum ErrorCode: String {
    case usage
    case noAccess = "no-access"
    case notFound = "not-found"
    case refused
    case failed
}

struct Failure: Error, Equatable {
    var code: ErrorCode
    var message: String
}

func errorObject(_ failure: Failure) -> [String: Any] {
    ["error": failure.message, "code": failure.code.rawValue]
}

/// One JSON object on one line. JSON escaping is the only escaping.
func render(_ object: [String: Any]) -> String {
    let data = (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes]))
        ?? Data("{\"code\":\"failed\",\"error\":\"Could not encode the result.\"}".utf8)
    return String(decoding: data, as: UTF8.self)
}

// MARK: commands and arguments

enum Command: String {
    case help, access, calendars, events, lists, reminders, add, set, done, reopen

    var isWrite: Bool { [.add, .set, .done, .reopen].contains(self) }
}

struct CommandSpec {
    var positional: Bool           // takes exactly one positional (an id)
    var once: [String]             // each may be given once
    var many: [String]             // each may be given any number of times
}

let specs: [Command: CommandSpec] = [
    .help: CommandSpec(positional: false, once: [], many: []),
    .access: CommandSpec(positional: false, once: [], many: []),
    .calendars: CommandSpec(positional: false, once: [], many: []),
    .events: CommandSpec(positional: false, once: ["from", "to"], many: ["calendar"]),
    .lists: CommandSpec(positional: false, once: [], many: []),
    .reminders: CommandSpec(positional: false, once: [], many: ["list"]),
    .add: CommandSpec(positional: false, once: ["list", "title", "due", "notes"], many: []),
    .set: CommandSpec(positional: true, once: ["title", "due", "notes", "list"], many: []),
    .done: CommandSpec(positional: true, once: [], many: []),
    .reopen: CommandSpec(positional: true, once: [], many: []),
]

struct Invocation: Equatable {
    var allow: [String]
    var command: Command
    var target: String?
    var flags: [String: [String]]

    func one(_ name: String) -> String? { flags[name]?.first }
}

enum ParseResult: Equatable {
    case run(Invocation)
    case failure(Failure)
}

private func usageFailure(_ message: String) -> ParseResult {
    .failure(Failure(code: .usage, message: message))
}

/// Parse the argument list (without argv[0]). Options are strict: an
/// unknown option is a usage error, and the value after an option is always
/// taken as data. `--allow <title>` is accepted only before the command
/// word, so nothing a caller appends after the command can widen the grant.
func parse(_ args: [String]) -> ParseResult {
    var rest = ArraySlice(args)
    var allow: [String] = []
    while rest.first == "--allow" {
        rest.removeFirst()
        guard let title = rest.popFirst() else { return usageFailure("--allow needs a list title.") }
        allow.append(title)
    }
    guard let word = rest.popFirst() else { return usageFailure("No command given. Run `eventkit help`.") }
    if word.hasPrefix("--") {
        return usageFailure("Unknown option \(word) before the command. Only --allow may come first.")
    }
    guard let command = Command(rawValue: word) else {
        return usageFailure("Unknown command \(word). Run `eventkit help`.")
    }
    if !allow.isEmpty, !command.isWrite {
        return usageFailure("--allow applies only to add, set, done and reopen.")
    }
    let spec = specs[command]!
    var flags: [String: [String]] = [:]
    var target: String? = nil
    while let argument = rest.popFirst() {
        if argument.hasPrefix("--") {
            let name = String(argument.dropFirst(2))
            let isOnce = spec.once.contains(name)
            guard isOnce || spec.many.contains(name) else {
                return usageFailure("Unknown option \(argument) for \(command.rawValue).")
            }
            guard let value = rest.popFirst() else { return usageFailure("\(argument) needs a value.") }
            if isOnce, flags[name] != nil { return usageFailure("\(argument) given twice.") }
            flags[name, default: []].append(value)
        } else if spec.positional, target == nil {
            target = argument
        } else {
            return usageFailure("Unexpected argument \(argument). Run `eventkit help`.")
        }
    }
    if spec.positional, target == nil { return usageFailure("\(command.rawValue) needs an id.") }
    let invocation = Invocation(allow: allow, command: command, target: target, flags: flags)
    if let failure = validate(invocation) { return .failure(failure) }
    return .run(invocation)
}

/// The checks that need the whole command line: required options and the
/// syntax of times.
func validate(_ i: Invocation) -> Failure? {
    func bad(_ message: String) -> Failure { Failure(code: .usage, message: message) }
    switch i.command {
    case .events:
        guard let from = i.one("from"), let to = i.one("to") else { return bad("events needs --from and --to.") }
        guard let start = parseInstant(from) else { return bad("Cannot read \(from) as --from. Use a UTC instant like 2026-10-28T14:00:00Z.") }
        guard let end = parseInstant(to) else { return bad("Cannot read \(to) as --to. Use a UTC instant like 2026-10-28T14:00:00Z.") }
        if end <= start { return bad("--to must be after --from.") }
    case .reminders:
        if i.flags["list"] == nil { return bad("reminders needs at least one --list.") }
    case .add:
        guard i.one("list") != nil, let title = i.one("title") else { return bad("add needs --list and --title.") }
        if title.isEmpty { return bad("--title must not be empty.") }
        if let due = i.one("due"), parseDue(due) == nil { return bad(dueHint(due)) }
    case .set:
        if !["title", "due", "notes", "list"].contains(where: { i.flags[$0] != nil }) {
            return bad("set needs at least one of --title, --due, --notes, --list.")
        }
        if i.one("title")?.isEmpty == true { return bad("--title must not be empty.") }
        if let due = i.one("due"), due != "none", parseDue(due) == nil { return bad(dueHint(due)) }
    default:
        break
    }
    return nil
}

private func dueHint(_ text: String) -> String {
    "Cannot read \(text) as --due. Use YYYY-MM-DD or YYYY-MM-DDTHH:MM (local time)."
}

/// The marker the first run puts at the start of the child's arguments so
/// the child does not disclaim again. An argument rather than an environment
/// variable, so no configuration travels in the environment.
let disclaimMarker = "--disclaimed"

func stripDisclaimMarker(_ args: [String]) -> (args: [String], disclaimed: Bool) {
    args.first == disclaimMarker ? (Array(args.dropFirst()), true) : (args, false)
}

// MARK: the --allow rule

/// A write is allowed only in a list whose title is exactly one of the
/// `--allow` titles. Returns the refusal, or nil when allowed.
func allowFailure(allow: [String], listTitle: String) -> Failure? {
    if allow.contains(listTitle) { return nil }
    return Failure(code: .refused, message: "The list \(listTitle) is not one of the allowed lists.")
}

/// Titles that more than one list has. Naming such a list is ambiguous.
func duplicateTitles(_ titles: [String]) -> Set<String> {
    var seen = Set<String>()
    var duplicates = Set<String>()
    for title in titles where !seen.insert(title).inserted { duplicates.insert(title) }
    return duplicates
}

// MARK: dates and times

/// Gregorian and UTC, whatever the user's regional settings are.
let utcCalendar: Calendar = {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "UTC")!
    return calendar
}()

private func digits(_ text: Substring, count: Int) -> Int? {
    guard text.count == count, text.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
    return Int(text)
}

struct Day: Equatable {
    var year: Int, month: Int, day: Int
}

func parseDay(_ text: String) -> Day? {
    let parts = text.split(separator: "-", omittingEmptySubsequences: false)
    guard parts.count == 3, let year = digits(parts[0], count: 4), year >= 1,
          let month = digits(parts[1], count: 2), let day = digits(parts[2], count: 2) else { return nil }
    var components = DateComponents()
    components.calendar = utcCalendar
    components.year = year; components.month = month; components.day = day
    guard components.isValidDate else { return nil }
    return Day(year: year, month: month, day: day)
}

func formatDay(_ day: Day) -> String {
    String(format: "%04d-%02d-%02d", day.year, day.month, day.day)
}

/// A reminder's due value: a date, or a local wall time with no zone.
struct Due: Equatable {
    var day: Day
    var time: (hour: Int, minute: Int)?

    static func == (a: Due, b: Due) -> Bool {
        a.day == b.day && a.time?.hour == b.time?.hour && a.time?.minute == b.time?.minute
    }
}

/// `YYYY-MM-DD` or `YYYY-MM-DDTHH:MM`, strictly.
func parseDue(_ text: String) -> Due? {
    let halves = text.split(separator: "T", omittingEmptySubsequences: false)
    guard halves.count == 1 || halves.count == 2, let day = parseDay(String(halves[0])) else { return nil }
    if halves.count == 1 { return Due(day: day, time: nil) }
    let clock = halves[1].split(separator: ":", omittingEmptySubsequences: false)
    guard clock.count == 2, let hour = digits(clock[0], count: 2), let minute = digits(clock[1], count: 2),
          hour < 24, minute < 60 else { return nil }
    return Due(day: day, time: (hour, minute))
}

func formatDue(_ due: Due) -> String {
    guard let time = due.time else { return formatDay(due.day) }
    return formatDay(due.day) + String(format: "T%02d:%02d", time.hour, time.minute)
}

/// `YYYY-MM-DDTHH:MM:SSZ`, a UTC instant, strictly.
func parseInstant(_ text: String) -> Date? {
    guard text.hasSuffix("Z") else { return nil }
    let halves = text.dropLast().split(separator: "T", omittingEmptySubsequences: false)
    guard halves.count == 2, let day = parseDay(String(halves[0])) else { return nil }
    let clock = halves[1].split(separator: ":", omittingEmptySubsequences: false)
    guard clock.count == 3, let hour = digits(clock[0], count: 2), let minute = digits(clock[1], count: 2),
          let second = digits(clock[2], count: 2), hour < 24, minute < 60, second < 60 else { return nil }
    var components = DateComponents()
    components.calendar = utcCalendar
    components.year = day.year; components.month = day.month; components.day = day.day
    components.hour = hour; components.minute = minute; components.second = second
    return utcCalendar.date(from: components)
}

/// A UTC instant, to the second (a fraction is cut off).
func formatInstant(_ date: Date) -> String {
    let c = utcCalendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date.rounded(.down))
    return String(format: "%04d-%02d-%02dT%02d:%02d:%02dZ", c.year ?? 0, c.month ?? 0, c.day ?? 0,
                  c.hour ?? 0, c.minute ?? 0, c.second ?? 0)
}

extension Date {
    fileprivate func rounded(_ rule: FloatingPointRoundingRule) -> Date {
        Date(timeIntervalSince1970: timeIntervalSince1970.rounded(rule))
    }
}

/// The date of an all-day event's start, in the given calendar's zone.
func localDay(_ date: Date, calendar: Calendar) -> String {
    let c = calendar.dateComponents([.year, .month, .day], from: date)
    return formatDay(Day(year: c.year ?? 0, month: c.month ?? 0, day: c.day ?? 0))
}

/// The exclusive end date of an all-day event, as in iCalendar. EventKit
/// gives the end either as the next midnight or as the last second of the
/// final day. A second earlier lies inside the final day in both cases.
func allDayEnd(start: Date, end: Date, calendar: Calendar) -> String {
    let lastMoment = end > start ? end.addingTimeInterval(-1) : start
    let lastDay = calendar.startOfDay(for: lastMoment)
    let next = calendar.date(byAdding: .day, value: 1, to: lastDay).map { calendar.startOfDay(for: $0) } ?? lastDay
    return localDay(next, calendar: calendar)
}

/// How far back completed reminders are reported.
let completedWindowDays = 14

func completedWindowStart(now: Date) -> Date {
    now.addingTimeInterval(-Double(completedWindowDays) * 86400)
}

// MARK: calendars and lists

/// The EventKit facts about a calendar or list, as plain values.
struct CalendarTraits: Equatable {
    var isSubscribed: Bool
    var typeName: String            // local, calDAV, exchange, subscription, birthday
    var allowsContentModifications: Bool
    var isDelegateSource: Bool      // EKSource.isDelegate: someone else's account opened as a delegate
}

/// Markers for a list's sharing signals, in a fixed order. Empty means "no
/// signal", which is not proof that nobody else can see or write to it: a
/// shared iCloud list looks the same as a private one.
func sharingMarkers(_ traits: CalendarTraits) -> [String] {
    var markers: [String] = []
    if traits.isSubscribed || traits.typeName == "subscription" { markers.append("subscribed") }
    if traits.isDelegateSource { markers.append("delegate") }
    if !traits.allowsContentModifications { markers.append("read-only") }
    return markers
}

func calendarObject(id: String, title: String, source: String, type: String) -> [String: Any] {
    ["id": id, "title": title, "source": source, "type": type]
}

func listObject(id: String, title: String, source: String, markers: [String]) -> [String: Any] {
    ["id": id, "title": title, "source": source, "markers": markers]
}

// MARK: events

struct EventFacts: Equatable {
    var key: String
    var id: String
    var calendar: String
    var title: String
    var start: String               // UTC instant, or a date when all-day
    var end: String                 // exclusive when all-day
    var allDay: Bool
    var location: String?
    var notes: String?
    var url: String?
    var status: String
    var modified: String?
}

/// Stable per occurrence: the external identifier and the occurrence start.
func eventKey(identifier: String, start: String) -> String { "\(identifier)@\(start)" }

func eventObject(_ e: EventFacts) -> [String: Any] {
    ["key": e.key, "id": e.id, "calendar": e.calendar, "title": e.title, "start": e.start, "end": e.end,
     "all_day": e.allDay, "location": e.location ?? NSNull(), "notes": e.notes ?? NSNull(),
     "url": e.url ?? NSNull(), "status": e.status, "modified": e.modified ?? NSNull()]
}

func sortedEvents(_ events: [EventFacts]) -> [EventFacts] {
    events.sorted { ($0.start, $0.calendar, $0.title, $0.key) < ($1.start, $1.calendar, $1.title, $1.key) }
}

// MARK: reminders

struct ReminderFacts: Equatable {
    var id: String
    var list: String
    var title: String
    var notes: String?
    var due: String?                // a date or a local wall time
    var completed: Bool
    var completedAt: String?        // UTC instant
    var modified: String?           // UTC instant
}

func reminderObject(_ r: ReminderFacts) -> [String: Any] {
    ["id": r.id, "list": r.list, "title": r.title, "notes": r.notes ?? NSNull(), "due": r.due ?? NSNull(),
     "completed": r.completed, "completed_at": r.completedAt ?? NSNull(), "modified": r.modified ?? NSNull()]
}

func sortedReminders(_ reminders: [ReminderFacts]) -> [ReminderFacts] {
    reminders.sorted { ($0.list, $0.due ?? "~", $0.title, $0.id) < ($1.list, $1.due ?? "~", $1.title, $1.id) }
}

// MARK: alarms

/// Which alarms a due-date edit removes: those at positions whose absolute
/// date equals the old timed due date (nil for a relative or location
/// alarm, or when the old due had no time). Every other alarm is the
/// user's own and stays.
func alarmsToRemove(oldDue: Date?, alarmDates: [Date?]) -> [Int] {
    guard let oldDue else { return [] }
    return alarmDates.indices.filter { alarmDates[$0] == oldDue }
}

/// Whether to add an absolute alarm at the new timed due date: not when
/// there is no new timed due, and not when one is already there among the
/// alarms that remain.
func needsNewAlarm(newDue: Date?, remaining: [Date?]) -> Bool {
    guard let newDue else { return false }
    return !remaining.contains(newDue)
}
