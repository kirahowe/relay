// The pure part of cal: argument parsing, date and range rules, output
// formatting and escaping. Nothing here imports EventKit or touches the
// outside world, so tests/main.swift can compile it alone.
//
// cal is read-only by design. No command here has a counterpart that
// creates, changes or deletes an event or a calendar.

import Foundation

// MARK: limits

/// The longest span `events` will cover, in days.
let maxRangeDays = 92
/// The most events `events` prints. More are counted, not shown.
let maxEvents = 200
/// The most attendees `show` prints.
let maxAttendees = 100
/// The most characters of notes `show` prints.
let maxNoteCharacters = 4000

// MARK: commands

enum Command: String {
    case help, calendars, events, show
}

struct CommandSpec {
    var positional: Bool           // takes exactly one positional
    var valueFlags: [String]       // each may be given once
    var repeatFlags: [String]      // each may be given any number of times
}

let specs: [Command: CommandSpec] = [
    .help: CommandSpec(positional: false, valueFlags: [], repeatFlags: []),
    .calendars: CommandSpec(positional: false, valueFlags: [], repeatFlags: []),
    .events: CommandSpec(positional: false, valueFlags: ["from", "to"], repeatFlags: ["calendar"]),
    .show: CommandSpec(positional: true, valueFlags: [], repeatFlags: []),
]

struct Invocation: Equatable {
    var command: Command
    var target: String?
    var flags: [String: [String]]
}

enum ParseResult: Equatable {
    case run(Invocation)
    case refused(String)       // fail closed, with the reason
}

/// Parse the full argument list (without argv[0]). Options are strict: an
/// unknown option is an error, so nothing a caller adds can be read as
/// something else. The value after a value option is always taken as data.
func parse(_ args: [String]) -> ParseResult {
    var rest = args
    guard !rest.isEmpty else { return .refused("No command given. Run `cal help`.") }
    let word = rest.removeFirst()
    guard let command = Command(rawValue: word) else {
        return .refused("Unknown command \(escape(word)). Run `cal help`.")
    }
    let spec = specs[command]!
    var flags: [String: [String]] = [:]
    var target: String? = nil
    while !rest.isEmpty {
        let argument = rest.removeFirst()
        if argument.hasPrefix("--") {
            let name = String(argument.dropFirst(2))
            let isOnce = spec.valueFlags.contains(name)
            guard isOnce || spec.repeatFlags.contains(name) else {
                return .refused("Unknown option \(escape(argument)) for \(command.rawValue).")
            }
            guard !rest.isEmpty else { return .refused("\(argument) needs a value.") }
            if isOnce, flags[name] != nil { return .refused("\(argument) given twice.") }
            flags[name, default: []].append(rest.removeFirst())
        } else if spec.positional, target == nil {
            target = argument
        } else {
            return .refused("Unexpected argument \(escape(argument)). Run `cal help`.")
        }
    }
    if spec.positional, target == nil { return .refused("\(command.rawValue) needs an argument. Run `cal help`.") }
    return .run(Invocation(command: command, target: target, flags: flags))
}

// MARK: dates and ranges

/// A date the caller typed: `YYYY-MM-DD`, or `YYYY-MM-DD HH:MM` (a `T`
/// may stand for the space), in the system time zone.
struct When: Equatable {
    var year: Int, month: Int, day: Int
    var time: (hour: Int, minute: Int)?

    static func == (a: When, b: When) -> Bool {
        a.year == b.year && a.month == b.month && a.day == b.day
            && a.time?.hour == b.time?.hour && a.time?.minute == b.time?.minute
    }
}

struct Refusal: Error, Equatable { var message: String }

/// The calendar all date syntax is read and written in: Gregorian, in the
/// given time zone. `Calendar.current` follows the user's regional settings,
/// so under a Buddhist or Japanese calendar `2026-10-16` would mean another
/// year.
func gregorianCalendar(timeZone: TimeZone) -> Calendar {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = timeZone
    return calendar
}

private func digits(_ text: Substring, count: Int) -> Int? {
    guard text.count == count, text.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
    return Int(text)
}

/// Read a date as rem reads one, but strictly: fixed digit counts and a
/// real calendar date.
func parseWhen(_ text: String, calendar: Calendar) -> When? {
    let halves = text.split(omittingEmptySubsequences: false, whereSeparator: { $0 == " " || $0 == "T" })
    guard halves.count == 1 || halves.count == 2 else { return nil }
    let parts = halves[0].split(separator: "-", omittingEmptySubsequences: false)
    guard parts.count == 3, let year = digits(parts[0], count: 4),
          let month = digits(parts[1], count: 2), let day = digits(parts[2], count: 2) else { return nil }
    var components = DateComponents()
    components.calendar = calendar
    components.year = year; components.month = month; components.day = day
    var time: (hour: Int, minute: Int)? = nil
    if halves.count == 2 {
        let clock = halves[1].split(separator: ":", omittingEmptySubsequences: false)
        guard clock.count == 2, let hour = digits(clock[0], count: 2), let minute = digits(clock[1], count: 2),
              hour < 24, minute < 60 else { return nil }
        time = (hour, minute)
    }
    guard components.isValidDate(in: calendar) else { return nil }
    return When(year: year, month: month, day: day, time: time)
}

private func startOfDay(_ when: When, _ calendar: Calendar) -> Date? {
    var components = DateComponents()
    components.year = when.year; components.month = when.month; components.day = when.day
    return calendar.date(from: components).map { calendar.startOfDay(for: $0) }
}

/// The start of the day `days` days after the day holding `date`. Adding days
/// to a start of day that a clock change moved (01:00 where midnight does not
/// exist) keeps that 01:00, so the result is reduced to its own day's start.
private func nextDayStart(after date: Date, days: Int, _ calendar: Calendar) -> Date? {
    calendar.date(byAdding: .day, value: days, to: calendar.startOfDay(for: date)).map { calendar.startOfDay(for: $0) }
}

private func instant(_ when: When, _ calendar: Calendar) -> Date? {
    guard let time = when.time else { return startOfDay(when, calendar) }
    var components = DateComponents()
    components.year = when.year; components.month = when.month; components.day = when.day
    components.hour = time.hour; components.minute = time.minute
    // A wall time skipped by a clock change (02:30 when 02:00 jumps to 03:00)
    // would be moved silently; refuse it instead. A time that happens twice
    // reads as its first occurrence.
    guard let date = calendar.date(from: components) else { return nil }
    let back = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: date)
    guard back.year == when.year, back.month == when.month, back.day == when.day,
          back.hour == time.hour, back.minute == time.minute else { return nil }
    return date
}

/// The half-open range `[start, end)` that `events` covers.
///
/// - `--from` a date starts at that day's midnight; a datetime starts then.
///   Without it the range starts at today's midnight.
/// - `--to` a date includes that whole day; a datetime ends then. Without
///   it the range ends with the day seven days after the start's day.
/// - The end must be after the start, and the range at most `maxRangeDays`.
func resolveRange(from: String?, to: String?, now: Date, calendar: Calendar) -> Result<(start: Date, end: Date), Refusal> {
    let hint = "Use YYYY-MM-DD or \"YYYY-MM-DD HH:MM\", a time that exists in the system time zone."
    guard calendar.identifier == .gregorian else {
        return .failure(Refusal(message: "Dates are read in the Gregorian calendar only."))
    }
    var start = calendar.startOfDay(for: now)
    if let from {
        guard let when = parseWhen(from, calendar: calendar), let date = instant(when, calendar) else {
            return .failure(Refusal(message: "Cannot read \(escape(from)) as a --from date. \(hint)"))
        }
        start = date
    }
    var end: Date
    if let to {
        guard let when = parseWhen(to, calendar: calendar) else {
            return .failure(Refusal(message: "Cannot read \(escape(to)) as a --to date. \(hint)"))
        }
        if when.time != nil {
            guard let date = instant(when, calendar) else {
                return .failure(Refusal(message: "Cannot read \(escape(to)) as a --to date. \(hint)"))
            }
            end = date
        } else {
            guard let day = startOfDay(when, calendar), let next = nextDayStart(after: day, days: 1, calendar) else {
                return .failure(Refusal(message: "Cannot read \(escape(to)) as a --to date. \(hint)"))
            }
            end = next
        }
    } else {
        guard let day = nextDayStart(after: start, days: 8, calendar) else {
            return .failure(Refusal(message: "Cannot work out the end of the range."))
        }
        end = day
    }
    guard end > start else { return .failure(Refusal(message: "--to must be after --from.")) }
    guard let limit = calendar.date(byAdding: .day, value: maxRangeDays, to: start), end <= limit else {
        return .failure(Refusal(message: "The range is longer than \(maxRangeDays) days. Ask for a shorter one."))
    }
    return .success((start, end))
}

private func two(_ n: Int) -> String { String(format: "%02d", n) }

func formatDay(_ date: Date, calendar: Calendar) -> String {
    let c = calendar.dateComponents([.year, .month, .day], from: date)
    return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
}

func formatInstant(_ date: Date, calendar: Calendar) -> String {
    let c = calendar.dateComponents([.hour, .minute], from: date)
    return formatDay(date, calendar: calendar) + " " + two(c.hour ?? 0) + ":" + two(c.minute ?? 0)
}

// MARK: events and the ids that name them

/// An occurrence's id is the event's identifier and its start as epoch
/// seconds, `<identifier>@<seconds>`. A repeating event has one identifier
/// for all its occurrences, so the start says which one.
///
/// The identifier can come from an invite's UID, so it is percent-encoded to
/// a safe alphabet: letters, digits and `.` `_` `:` `~` `-` stay, a leading
/// `-` and everything else (including `@`, `%`, backslash, tabs and any
/// non-ASCII) become `%XX` over UTF-8. The printed id then survives escape()
/// unchanged, so what is copied from `events` is what `show` decodes, and it
/// can never read as an option.
func occurrenceId(identifier: String, start: Date) -> String {
    "\(encodeIdentifier(identifier))@\(Int(start.timeIntervalSince1970.rounded(.down)))"
}

private let safeIdentifierBytes = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._:~-".utf8)

func encodeIdentifier(_ identifier: String) -> String {
    var out = ""
    for (index, byte) in identifier.utf8.enumerated() {
        if safeIdentifierBytes.contains(byte), !(index == 0 && byte == UInt8(ascii: "-")) {
            out.unicodeScalars.append(Unicode.Scalar(byte))
        } else {
            out += String(format: "%%%02X", byte)
        }
    }
    return out
}

/// The inverse of encodeIdentifier. Text that is not a well-formed encoding
/// (a stray `%`, bytes that are not UTF-8) is taken as typed.
func decodeIdentifier(_ text: String) -> String {
    var bytes: [UInt8] = []
    var iterator = Array(text.utf8)[...]
    while let byte = iterator.popFirst() {
        guard byte == UInt8(ascii: "%") else { bytes.append(byte); continue }
        guard iterator.count >= 2, let high = hexValue(iterator[iterator.startIndex]),
              let low = hexValue(iterator[iterator.startIndex + 1]) else { return text }
        bytes.append(UInt8(high * 16 + low))
        iterator.removeFirst(2)
    }
    return String(bytes: bytes, encoding: .utf8) ?? text
}

private func hexValue(_ byte: UInt8) -> Int? {
    switch byte {
    case UInt8(ascii: "0")...UInt8(ascii: "9"): return Int(byte - UInt8(ascii: "0"))
    case UInt8(ascii: "A")...UInt8(ascii: "F"): return Int(byte - UInt8(ascii: "A")) + 10
    case UInt8(ascii: "a")...UInt8(ascii: "f"): return Int(byte - UInt8(ascii: "a")) + 10
    default: return nil
    }
}

/// Split what a caller typed back into the identifier and, if given, the
/// start. A suffix after the last `@` counts only when it is digits, with a
/// leading `-` for a start before 1970.
func splitOccurrenceId(_ text: String) -> (identifier: String, start: Date?) {
    guard let at = text.lastIndex(of: "@") else { return (decodeIdentifier(text), nil) }
    var suffix = text[text.index(after: at)...]
    let negative = suffix.hasPrefix("-")
    if negative { suffix = suffix.dropFirst() }
    guard !suffix.isEmpty, suffix.count <= 12, suffix.allSatisfy({ $0.isASCII && $0.isNumber }),
          let seconds = Double(suffix) else { return (decodeIdentifier(text), nil) }
    return (decodeIdentifier(String(text[..<at])), Date(timeIntervalSince1970: negative ? -seconds : seconds))
}

struct EventFacts {
    var id: String
    var start: Date
    var end: Date
    var allDay: Bool
    var title: String
    var calendar: String
    var location: String?
}

func startField(_ event: EventFacts, calendar: Calendar) -> String {
    event.allDay ? formatDay(event.start, calendar: calendar) : formatInstant(event.start, calendar: calendar)
}

/// The end column: the end time, or `all-day` (with the last day when the
/// event covers more than one).
func endField(_ event: EventFacts, calendar: Calendar) -> String {
    guard event.allDay else { return formatInstant(event.end, calendar: calendar) }
    let first = formatDay(event.start, calendar: calendar)
    // An all-day end is either the next midnight or the last second of the
    // final day. A second earlier is inside the final day in both cases.
    let last = formatDay(event.end.addingTimeInterval(-1), calendar: calendar)
    return last > first ? "all-day to \(last)" : "all-day"
}

func eventLine(_ event: EventFacts, calendar: Calendar) -> String {
    let location = (event.location ?? "").isEmpty ? "-" : escape(event.location!)
    return [escape(event.id), startField(event, calendar: calendar), endField(event, calendar: calendar),
            escape(event.title), escape(event.calendar), location].joined(separator: "\t")
}

/// Order for printing: by start, then calendar, title and id.
func sortedEvents(_ events: [EventFacts]) -> [EventFacts] {
    events.sorted {
        ($0.start, $0.calendar, $0.title, $0.id) < ($1.start, $1.calendar, $1.title, $1.id)
    }
}

/// The lines for a range: one per event up to `maxEvents`, then a note when
/// some were left out, so a cut-short answer is never mistaken for a whole one.
func eventsOutput(_ events: [EventFacts], calendar: Calendar, limit: Int = maxEvents) -> [String] {
    let ordered = sortedEvents(events)
    var lines = ordered.prefix(limit).map { eventLine($0, calendar: calendar) }
    if ordered.count > limit {
        lines.append("note: \(ordered.count - limit) more events not shown (\(ordered.count) in range, showing the first \(limit)). Ask for a shorter range or one calendar.")
    }
    return lines
}

// MARK: calendars

/// The EventKit facts about a calendar, as plain values.
struct CalendarTraits: Equatable {
    var isSubscribed: Bool
    var typeName: String            // local, calDAV, exchange, subscription, birthday
    var allowsContentModifications: Bool
    var isDelegateSource: Bool      // EKSource.isDelegate: someone else's account opened as a delegate
}

/// Markers for a calendar's sharing signals, in a fixed order. Empty means
/// "no signal", which is not proof that nobody else can see or write to the
/// calendar: a shared iCloud calendar looks the same as a private one.
func sharingMarkers(_ traits: CalendarTraits) -> [String] {
    var markers: [String] = []
    if traits.isSubscribed || traits.typeName == "subscription" { markers.append("subscribed") }
    if traits.isDelegateSource { markers.append("delegate") }
    if !traits.allowsContentModifications { markers.append("read-only") }
    return markers
}

func markerColumn(_ markers: [String]) -> String {
    markers.isEmpty ? "-" : markers.joined(separator: ",")
}

func calendarLine(id: String, title: String, source: String, type: String, markers: [String]) -> String {
    [escape(id), escape(title), escape(source), escape(type), markerColumn(markers)].joined(separator: "\t")
}

enum CalendarMatch: Equatable {
    case found(Int)
    case none
    case ambiguous
}

/// Find a calendar by identifier (exact), else by title (case-insensitive).
/// Two calendars with one title are ambiguous: the caller must use the id.
func matchCalendar(_ query: String, ids: [String], titles: [String]) -> CalendarMatch {
    if let index = ids.firstIndex(of: query) { return .found(index) }
    let hits = titles.indices.filter { titles[$0].caseInsensitiveCompare(query) == .orderedSame }
    if hits.count == 1 { return .found(hits[0]) }
    return hits.isEmpty ? .none : .ambiguous
}

// MARK: show

struct AttendeeFacts {
    var name: String?
    var address: String?           // as EventKit gives it, usually mailto:
    var status: String             // accepted, declined, tentative, pending, ...
    var role: String               // required, optional, chair, ...
    var isCurrentUser: Bool
}

struct WeekdayFact: Equatable {
    var number: Int                // 0 every week; 2 is the second; -1 is the last
    var weekday: Int               // 1 Sunday ... 7 Saturday
}

struct RecurrenceFacts: Equatable {
    var frequency: String          // daily, weekly, monthly, yearly
    var interval: Int
    var weekdays: [WeekdayFact]
    var monthDays: [Int]
    var months: [Int]
    var weeksOfYear: [Int] = []
    var daysOfYear: [Int] = []
    var setPositions: [Int] = []   // keep only these positions among the matches of each period
    var until: Date?
    var count: Int?
}

private let weekdayNames = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
private let monthNames = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

private func ordinal(_ n: Int) -> String {
    if n == -1 { return "last" }
    if n < 0 { return "\(ordinal(-n)) from last" }
    let suffix: String
    switch (n % 100, n % 10) {
    case (11...13, _): suffix = "th"
    case (_, 1): suffix = "st"
    case (_, 2): suffix = "nd"
    case (_, 3): suffix = "rd"
    default: suffix = "th"
    }
    return "\(n)\(suffix)"
}

/// One rule in a few words, such as `every 2 weeks on Mon, Wed, until 2026-12-01`.
func describeRecurrence(_ rule: RecurrenceFacts, calendar: Calendar) -> String {
    let unit = ["daily": "day", "weekly": "week", "monthly": "month", "yearly": "year"][rule.frequency] ?? rule.frequency
    var text = rule.interval <= 1 ? "every \(unit)" : "every \(rule.interval) \(unit)s"
    if !rule.weekdays.isEmpty {
        let days = rule.weekdays.map { day -> String in
            let name = (1...7).contains(day.weekday) ? weekdayNames[day.weekday - 1] : "?"
            return day.number == 0 ? name : "\(ordinal(day.number)) \(name)"
        }
        text += " on " + days.joined(separator: ", ")
    }
    if !rule.monthDays.isEmpty {
        text += " on day " + rule.monthDays.map { $0 == -1 ? "last" : String($0) }.joined(separator: ", ")
    }
    if !rule.months.isEmpty {
        text += " in " + rule.months.map { (1...12).contains($0) ? monthNames[$0 - 1] : "?" }.joined(separator: ", ")
    }
    if !rule.weeksOfYear.isEmpty {
        text += " in week " + rule.weeksOfYear.map(String.init).joined(separator: ", ") + " of the year"
    }
    if !rule.daysOfYear.isEmpty {
        text += " on day " + rule.daysOfYear.map(String.init).joined(separator: ", ") + " of the year"
    }
    if !rule.setPositions.isEmpty {
        // "Mon to Fri, position -1" is the last weekday, not every weekday.
        text += ", keeping only the " + rule.setPositions.map(ordinal).joined(separator: ", ") + " of those"
    }
    if let until = rule.until {
        text += ", until " + formatDay(until, calendar: calendar)
    } else if let count = rule.count, count > 0 {
        text += ", \(count) times"
    }
    return text
}

struct ShowFacts {
    var id: String
    var title: String
    var calendar: String
    var markers: [String]
    var start: Date
    var end: Date
    var allDay: Bool
    var status: String
    var location: String?
    var url: String?
    var organizer: AttendeeFacts?
    var attendees: [AttendeeFacts]
    var recurrence: [RecurrenceFacts]
    var notes: String?
}

private func person(_ p: AttendeeFacts) -> String {
    let address = p.address.map { $0.lowercased().hasPrefix("mailto:") ? String($0.dropFirst(7)) : $0 } ?? ""
    return [escape(p.name ?? ""), escape(address)].map { $0.isEmpty ? "-" : $0 }.joined(separator: "\t")
}

func showLines(_ facts: ShowFacts, calendar: Calendar) -> [String] {
    let event = EventFacts(id: facts.id, start: facts.start, end: facts.end, allDay: facts.allDay,
                           title: facts.title, calendar: facts.calendar, location: facts.location)
    var lines = [
        "id:         \(escape(facts.id))",
        "title:      \(escape(facts.title))",
        "calendar:   \(escape(facts.calendar))",
        "shared:     \(markerColumn(facts.markers))",
        "start:      \(startField(event, calendar: calendar))",
        "end:        \(endField(event, calendar: calendar))",
        "status:     \(escape(facts.status))",
        "location:   \((facts.location ?? "").isEmpty ? "-" : escape(facts.location!))",
        "url:        \((facts.url ?? "").isEmpty ? "-" : escape(facts.url!))",
        "organizer:  \(facts.organizer.map(person) ?? "-")",
    ]
    if facts.recurrence.isEmpty {
        lines.append("recurrence: -")
    } else {
        lines.append("recurrence: " + facts.recurrence.map { escape(describeRecurrence($0, calendar: calendar)) }.joined(separator: "; "))
    }
    if facts.attendees.isEmpty {
        lines.append("attendees:  -")
    } else {
        lines.append("attendees:  \(facts.attendees.count) (name, address, status, role)")
        for attendee in facts.attendees.prefix(maxAttendees) {
            lines.append("  " + [person(attendee), escape(attendee.status), escape(attendee.role),
                                 attendee.isCurrentUser ? "you" : "-"].joined(separator: "\t"))
        }
        if facts.attendees.count > maxAttendees {
            lines.append("note: \(facts.attendees.count - maxAttendees) more attendees not shown.")
        }
    }
    if let notes = facts.notes, !notes.isEmpty {
        let scalars = notes.unicodeScalars
        var shown = notes
        if scalars.count > maxNoteCharacters {
            shown = String(String.UnicodeScalarView(scalars.prefix(maxNoteCharacters)))
        }
        lines.append("notes:")
        lines += escapeBlock(shown)
        if scalars.count > maxNoteCharacters {
            lines.append("note: notes cut at \(maxNoteCharacters) characters (\(scalars.count) in all).")
        }
    }
    return lines
}

// MARK: escaping

/// Make untrusted text safe for one field of one line. Control, format
/// (bidi marks, zero-width, tags), separator, private-use and unassigned
/// scalars, and every space other than U+0020, become visible escapes.
/// Backslash doubles so an escape can never be forged.
func escape(_ text: String) -> String {
    var out = ""
    for scalar in text.unicodeScalars {
        switch scalar {
        case "\\": out += "\\\\"
        case "\t": out += "\\t"
        case "\n": out += "\\n"
        case "\r": out += "\\r"
        case " ": out += " "
        default:
            if needsEscape(scalar) {
                out += "\\u{" + String(scalar.value, radix: 16, uppercase: true) + "}"
            } else {
                out.unicodeScalars.append(scalar)
            }
        }
    }
    return out
}

func needsEscape(_ scalar: Unicode.Scalar) -> Bool {
    switch scalar.properties.generalCategory {
    case .control, .format, .lineSeparator, .paragraphSeparator, .spaceSeparator,
         .privateUse, .unassigned, .surrogate:
        return true
    default:
        return false
    }
}

/// Multi-line text (notes): each line escaped and prefixed so no line can
/// pose as a field of its own. Splits on every line-break scalar.
func escapeBlock(_ text: String) -> [String] {
    var lines: [String] = []
    var current = ""
    var previousCR = false
    for scalar in text.unicodeScalars {
        let isBreak = scalar == "\n" || scalar == "\r" || scalar == "\u{85}"
            || scalar == "\u{2028}" || scalar == "\u{2029}" || scalar == "\u{0B}" || scalar == "\u{0C}"
        if isBreak {
            if scalar == "\n" && previousCR { previousCR = false; continue }
            lines.append(current); current = ""
            previousCR = scalar == "\r"
        } else {
            current.unicodeScalars.append(scalar); previousCR = false
        }
    }
    lines.append(current)
    return lines.map { "> " + escape($0) }
}
