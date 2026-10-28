// Self-test for Core.swift. Touches no EventKit. Run with tools/eventkit/test.sh.
import Foundation

var failures = 0
func check(_ ok: Bool, _ what: String, line: Int = #line) {
    if !ok { failures += 1; print("FAIL (line \(line)): \(what)") }
}

func run(_ args: [String]) -> Invocation? { if case .run(let i) = parse(args) { return i }; return nil }
func failure(_ args: [String]) -> Failure? { if case .failure(let f) = parse(args) { return f }; return nil }
func isUsage(_ args: [String]) -> Bool { failure(args)?.code == .usage }

// Reproducible randomness: the seed is printed when a check fails.
struct SplitMix64: RandomNumberGenerator {
    var state: UInt64
    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
}
let seed = UInt64(Date().timeIntervalSince1970 * 1000)
var rng = SplitMix64(state: seed)

func jsonObject(_ text: String) -> [String: Any]? {
    (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any]
}

// MARK: parsing

check(isUsage([]), "no args")
check(isUsage(["bogus"]), "unknown command")
check(isUsage(["--bogus", "calendars"]), "unknown option before the command")
check(run(["help"])?.command == .help, "help")
check(isUsage(["help", "--x", "1"]), "help takes no options")
for c in ["access", "calendars", "lists"] {
    check(run([c])?.command == Command(rawValue: c), "\(c) runs")
    check(isUsage([c, "--x", "1"]), "\(c) rejects unknown options")
    check(isUsage([c, "extra"]), "\(c) rejects positionals")
}

let from = "2026-10-28T00:00:00Z", to = "2026-10-29T00:00:00Z"
check(run(["events", "--from", from, "--to", to])?.flags["calendar"] == nil, "events without calendar")
check(run(["events", "--from", from, "--to", to, "--calendar", "A", "--calendar", "B"])?.flags["calendar"] == ["A", "B"], "calendar repeats")
check(isUsage(["events", "--from", from]), "events needs --to")
check(isUsage(["events", "--to", to]), "events needs --from")
check(isUsage(["events", "--from", from, "--from", from, "--to", to]), "--from twice")
check(isUsage(["events", "--from", from, "--to", to, "--to", to]), "--to twice")
check(isUsage(["events", "--from", from, "--to", to, "--bogus", "x"]), "unknown option")
check(isUsage(["events", "--from", from, "--to", to, "--calendar"]), "option without value")
check(isUsage(["events", "--from", to, "--to", from]), "to before from")
check(isUsage(["events", "--from", from, "--to", from]), "empty range")
check(isUsage(["events", "--from", "2026-10-28", "--to", to]), "date is not an instant")
check(isUsage(["events", "--from", "2026-10-28T00:00:00", "--to", to]), "instant needs Z")
check(isUsage(["events", "--from=\(from)", "--to", to]), "equals form is unknown")
check(run(["events", "--from", from, "--to", to, "--calendar", "--to"])?.flags["calendar"] == ["--to"], "an option value is data")

check(run(["reminders", "--list", "A"])?.flags["list"] == ["A"], "reminders one list")
check(run(["reminders", "--list", "A", "--list", "B"])?.flags["list"] == ["A", "B"], "reminders lists repeat")
check(isUsage(["reminders"]), "reminders needs a list")
check(isUsage(["reminders", "--list"]), "list needs a value")
check(isUsage(["--allow", "A", "reminders", "--list", "A"]), "reads take no --allow")
check(isUsage(["--allow", "A", "lists"]), "lists takes no --allow")

check(run(["add", "--list", "L", "--title", "T"])?.one("title") == "T", "add")
check(run(["add", "--list", "L", "--title", "T", "--due", "2026-10-28T09:00", "--notes", "n"])?.one("due") == "2026-10-28T09:00", "add with due")
check(isUsage(["add", "--title", "T"]), "add needs a list")
check(isUsage(["add", "--list", "L"]), "add needs a title")
check(isUsage(["add", "--list", "L", "--title", ""]), "empty title")
check(isUsage(["add", "--list", "L", "--title", "T", "--due", "none"]), "add has no due none")
check(isUsage(["add", "--list", "L", "--title", "T", "--due", "tomorrow"]), "bad due")
check(isUsage(["add", "--list", "L", "--title", "T", "--list", "M"]), "list twice")
check(isUsage(["add", "stray", "--list", "L", "--title", "T"]), "stray positional")
check(run(["add", "--list", "L", "--title", "--notes", "--notes", "x"])?.one("title") == "--notes", "title value is data")

check(isUsage(["set", "ID", "--due", "none"]), "none is not a due date")
check(run(["set", "ID", "--clear-due"])?.has("clear-due") == true, "set --clear-due")
check(run(["set", "ID", "--clear-notes", "--list", "M"])?.has("clear-notes") == true, "set --clear-notes and list")
check(run(["set", "ID", "--notes", "none"])?.one("notes") == "none", "notes that read none are notes")
check(isUsage(["set", "ID", "--due", "2026-10-28", "--clear-due"]), "--due conflicts with --clear-due")
check(isUsage(["set", "ID", "--notes", "x", "--clear-notes"]), "--notes conflicts with --clear-notes")
check(isUsage(["set", "ID", "--clear-due", "--clear-due"]), "a switch is given once")
check(isUsage(["set", "ID", "--clear-due", "x"]), "a switch takes no value")
check(isUsage(["add", "--list", "L", "--title", "T", "--clear-notes"]), "add has no clear flags")
check(isUsage(["set", "ID"]), "set needs a change")
check(isUsage(["set", "--title", "T"]), "set needs an id")
check(isUsage(["set", "ID", "OTHER", "--title", "T"]), "set takes one id")
check(isUsage(["set", "ID", "--due", "2026-02-30"]), "set rejects an impossible date")
check(run(["set", "--title", "T", "ID"])?.target == "ID", "id after options")
for c in ["done", "reopen"] {
    check(run([c, "ID"])?.target == "ID", "\(c) takes an id")
    check(isUsage([c]), "\(c) needs an id")
    check(isUsage([c, "ID", "--list", "L"]), "\(c) takes no options")
}
for c in ["delete", "remove", "rm", "drop", "trash"] {
    check(isUsage([c, "ID"]), "no \(c) command")
    check(Command(rawValue: c) == nil, "\(c) is not a Command")
}

// MARK: the --allow rule

let a = run(["--allow", "Personal", "--allow", "Home", "done", "ID"])
check(a?.allow == ["Personal", "Home"], "allow before the command is collected")
check(run(["done", "ID"])?.allow == [], "no allow means an empty list")
check(isUsage(["done", "ID", "--allow", "Personal"]), "--allow after the command word")
check(isUsage(["done", "--allow", "Personal", "ID"]), "--allow between command and id")
check(isUsage(["add", "--list", "L", "--title", "T", "--allow", "L"]), "--allow after add options")
check(isUsage(["--allow", "L", "add", "--list", "L", "--title", "T", "--allow", "M"]), "a second --allow after the command")
check(isUsage(["--allow"]), "--allow without a value")
check(isUsage(["--allow", "A"]), "--allow without a command")
check(isUsage(["--allow", "A", "--bogus", "x", "done", "ID"]), "other options before the command")
check(isUsage(["--allow", "A", "calendars"]), "--allow with a read")
check(isUsage(["--allow", "A", "events", "--from", from, "--to", to]), "--allow with events")
check(run(["--allow", "--allow", "done", "ID"])?.allow == ["--allow"], "an --allow value is data")
check(allowFailure(allow: ["Personal"], listTitle: "Personal") == nil, "allowed list")
check(allowFailure(allow: ["Personal"], listTitle: "personal")?.code == .refused, "match is exact")
check(allowFailure(allow: ["Personal"], listTitle: "Work")?.code == .refused, "other list refused")
check(allowFailure(allow: [], listTitle: "Personal")?.code == .refused, "empty allow refuses everything")
check(allowFailure(allow: ["Personal"], listTitle: "Personal ")?.code == .refused, "no trimming")
check(duplicateTitles(["A", "B", "A"]) == ["A"], "duplicates found")
check(duplicateTitles(["A", "B"]).isEmpty, "no duplicates")

// Property: no matter what follows the command word, --allow there is never
// accepted, for every command.
let words = ["--allow", "--list", "--title", "--due", "x", "L", "2026-10-28", "none", "ID", "--from", from]
for command in ["access", "calendars", "events", "lists", "reminders", "add", "set", "done", "reopen"] {
    for _ in 0..<200 {
        var tail = (0..<Int.random(in: 0...6, using: &rng)).map { _ in words.randomElement(using: &rng)! }
        tail.insert(contentsOf: ["--allow", "Personal"], at: Int.random(in: 0...tail.count, using: &rng))
        if case .run(let i) = parse(["--allow", "Personal", command] + tail) {
            // Accepted only if the --allow was consumed as the value of an option.
            check(i.allow == ["Personal"], "allow stays the leading grant: \(command) \(tail)")
        }
        check(!(run([command] + tail).map { !$0.allow.isEmpty } ?? false), "no grant from the tail: \(command) \(tail)")
    }
}

// Property: parse never crashes, and an accepted command line never has an
// empty allow with a read or a non-empty allow with a read.
let pool = ["add", "set", "done", "reopen", "events", "reminders", "lists", "help", "--list", "--allow", "--from", "--to",
            "--title", "--due", "--notes", "--clear-due", "--clear-notes", "--calendar", "", "x", "-", "--", "2026-10-28", "none"]
for _ in 0..<5000 {
    let args = (0..<Int.random(in: 0...8, using: &rng)).map { _ in pool.randomElement(using: &rng)! }
    if case .run(let i) = parse(args), !i.command.isWrite {
        check(i.allow.isEmpty, "reads carry no allow: \(args)")
    }
}

// The disclaim marker is only honoured as the first argument.
check(stripDisclaimMarker([disclaimMarker, "lists"]) == (["lists"], true), "marker stripped")
check(stripDisclaimMarker(["lists", disclaimMarker]) == (["lists", disclaimMarker], false), "late marker kept")
check(isUsage(["lists", disclaimMarker]), "a late marker is a usage error")

// MARK: dates and times

check(parseDay("2026-10-28") == Day(year: 2026, month: 10, day: 28), "day")
check(parseDay("2026-02-29") == nil, "no 29 Feb 2026")
check(parseDay("2028-02-29") != nil, "29 Feb 2028")
check(parseDay("2026-13-01") == nil && parseDay("2026-00-10") == nil && parseDay("2026-10-00") == nil, "bad month or day")
check(parseDay("26-10-28") == nil && parseDay("2026-1-28") == nil && parseDay("2026/10/28") == nil, "day shape")
check(parseDay("0000-01-01") == nil, "year zero")
check(parseDue("2026-10-28T09:05") == Due(day: Day(year: 2026, month: 10, day: 28), time: (9, 5)), "due with time")
check(parseDue("2026-10-28")?.time == nil, "due date only")
check(parseDue("2026-10-28T24:00") == nil && parseDue("2026-10-28T09:60") == nil, "bad clock")
check(parseDue("2026-10-28T09:05Z") == nil && parseDue("2026-10-28 09:05") == nil && parseDue("2026-10-28T9:05") == nil, "due shape")
check(parseDue("2026-10-28T09:05:00") == nil, "no seconds in due")
check(parseDue("") == nil && parseDue("T") == nil, "empty due")
check(parseInstant("2026-10-28T14:00:00Z") == Date(timeIntervalSince1970: 1793196000), "known instant")
check(formatInstant(Date(timeIntervalSince1970: 0)) == "1970-01-01T00:00:00Z", "epoch")
check(formatInstant(Date(timeIntervalSince1970: 1.9)) == "1970-01-01T00:00:01Z", "fraction cut off")
check(formatInstant(Date(timeIntervalSince1970: -1)) == "1969-12-31T23:59:59Z", "before the epoch")
check(parseInstant("2026-10-28T14:00:60Z") == nil && parseInstant("2026-10-28T14:00Z") == nil
      && parseInstant("2026-10-28T14:00:00+00:00") == nil && parseInstant("2026-10-28T14:00:00") == nil, "instant shape")

func randomDay() -> Day {
    let year = Int.random(in: 1...9999, using: &rng), month = Int.random(in: 1...12, using: &rng)
    return Day(year: year, month: month, day: Int.random(in: 1...28, using: &rng))
}
for _ in 0..<2000 {
    let day = randomDay()
    check(parseDay(formatDay(day)) == day, "day round trip \(day)")
    let due = Due(day: day, time: Bool.random(using: &rng) ? (Int.random(in: 0...23, using: &rng), Int.random(in: 0...59, using: &rng)) : nil)
    check(parseDue(formatDue(due)) == due, "due round trip \(due)")
    let seconds = Double(Int.random(in: -62_000_000_000...250_000_000_000, using: &rng))
    let text = formatInstant(Date(timeIntervalSince1970: seconds))
    check(parseInstant(text)?.timeIntervalSince1970 == seconds, "instant round trip \(text)")
}
// Whatever parses is canonical: formatting gives the text back.
let alphabet = Array("0123456789-:TZ +")
for _ in 0..<20000 {
    let text = String((0..<Int.random(in: 0...22, using: &rng)).map { _ in alphabet.randomElement(using: &rng)! })
    if let d = parseDue(text) { check(formatDue(d) == text, "due is canonical: \(text)") }
    if let d = parseInstant(text) { check(formatInstant(d) == text, "instant is canonical: \(text)") }
}

// All-day ends are exclusive dates, in the given zone.
var zone = Calendar(identifier: .gregorian)
zone.timeZone = TimeZone(identifier: "Europe/Berlin")!
func midnight(_ text: String) -> Date { zone.startOfDay(for: parseInstant(text + "T12:00:00Z")!) }
let day1 = midnight("2026-10-28")
check(localDay(day1, calendar: zone) == "2026-10-28", "local day")
check(allDayEnd(start: day1, end: midnight("2026-10-29"), calendar: zone) == "2026-10-29", "next midnight end")
check(allDayEnd(start: day1, end: midnight("2026-10-29").addingTimeInterval(-1), calendar: zone) == "2026-10-29", "last second end")
check(allDayEnd(start: day1, end: midnight("2026-10-31"), calendar: zone) == "2026-10-31", "three days")
check(allDayEnd(start: day1, end: day1, calendar: zone) == "2026-10-29", "zero-length end")
check(allDayEnd(start: midnight("2026-10-24"), end: midnight("2026-10-26"), calendar: zone) == "2026-10-26", "across the clock change")
check(completedWindowStart(now: Date(timeIntervalSince1970: 15 * 86400)) == Date(timeIntervalSince1970: 86400), "14 day window")

// MARK: JSON shapes

let err = jsonObject(render(errorObject(Failure(code: .noAccess, message: "No \"access\"\n\\ \u{1}"))))
check(err?["code"] as? String == "no-access", "error code")
check(err?["error"] as? String == "No \"access\"\n\\ \u{1}", "error text survives JSON escaping")
check(Set(err?.keys.map { $0 } ?? []) == ["code", "error"], "error has exactly code and error")
check(!render(errorObject(Failure(code: .usage, message: "x"))).contains("\n"), "one line")
for (code, text) in [(ErrorCode.usage, "usage"), (.noAccess, "no-access"), (.notFound, "not-found"), (.refused, "refused"), (.failed, "failed")] {
    check(code.rawValue == text, "code name \(text)")
}
check(render(["a": "b/c"]) == "{\"a\":\"b/c\"}", "slashes are not escaped")
for _ in 0..<500 {
    let nasty = String((0..<Int.random(in: 0...12, using: &rng)).map { _ in
        Character(Unicode.Scalar(UInt8.random(in: 1...127, using: &rng)))
    })
    check(jsonObject(render(errorObject(Failure(code: .failed, message: nasty))))?["error"] as? String == nasty, "round trip \(nasty.debugDescription)")
}

let reminder = ReminderFacts(id: "ID1", list: "Personal", title: "Buy milk", notes: nil, due: "2026-10-28T09:00",
                             completed: false, completedAt: nil, modified: "2026-10-27T10:00:00Z")
let ro = jsonObject(render(reminderObject(reminder)))
check(Set(ro?.keys.map { $0 } ?? []) == ["id", "list", "title", "notes", "due", "completed", "completed_at", "modified"], "reminder keys")
check(ro?["notes"] is NSNull && ro?["completed_at"] is NSNull, "reminder nulls")
check((ro?["completed"] as? NSNumber)?.boolValue == false, "reminder completed is a boolean")
check(ro?["due"] as? String == "2026-10-28T09:00", "reminder due")
let wrapped = jsonObject(render(["before": reminderObject(reminder), "after": reminderObject(reminder)]))
check(Set(wrapped?.keys.map { $0 } ?? []) == ["before", "after"], "before and after")

let event = EventFacts(key: eventKey(identifier: "UID", start: "2026-10-28T14:00:00Z"), id: "E1", calendar: "Home",
                       title: "Dentist", start: "2026-10-28T14:00:00Z", end: "2026-10-28T15:00:00Z", allDay: false,
                       location: nil, notes: nil, url: nil, status: "confirmed", modified: nil)
let eo = jsonObject(render(eventObject(event)))
check(Set(eo?.keys.map { $0 } ?? []) == ["key", "id", "calendar", "title", "start", "end", "all_day", "location", "notes", "url", "status", "modified"], "event keys")
check(eo?["key"] as? String == "UID@2026-10-28T14:00:00Z", "event key")
check(eo?["all_day"] as? Bool == false, "all_day is a boolean")
check(eventKey(identifier: "UID", start: "2026-10-28") == "UID@2026-10-28", "all-day key uses a date")
check(sortedEvents([event, {var e = event; e.start = "2026-10-27T10:00:00Z"; return e}()]).first?.start == "2026-10-27T10:00:00Z", "events sort by start")
let dated = {var r = reminder; r.due = "2026-10-27"; r.id = "ID0"; return r}()
let undated = {var r = reminder; r.due = nil; return r}()
check(sortedReminders([undated, reminder, dated]).map { $0.id } == ["ID0", "ID1", "ID1"] && sortedReminders([undated, dated]).last?.due == nil, "reminders sort by due, none last")

let lo = jsonObject(render(["lists": [listObject(id: "1", title: "T", source: "iCloud", markers: ["delegate"])]]))
check(((lo?["lists"] as? [[String: Any]])?.first?["markers"] as? [String]) == ["delegate"], "list markers")
let co = calendarObject(id: "1", title: "T", source: "S", type: "local")
check(Set(co.keys) == ["id", "title", "source", "type"], "calendar keys")
let plain = CalendarTraits(isSubscribed: false, typeName: "calDAV", allowsContentModifications: true, isDelegateSource: false)
check(sharingMarkers(plain).isEmpty, "no markers")
check(sharingMarkers(CalendarTraits(isSubscribed: true, typeName: "subscription", allowsContentModifications: false, isDelegateSource: true))
      == ["subscribed", "delegate", "read-only"], "all markers in order")

// MARK: alarms

let timed = parseDue("2026-10-28T09:00")
let dayDue = parseDue("2026-10-28")
let noDue: Due? = nil
check(alarmEffect(command: .add, due: timed) == .relativeAtDue, "a new timed reminder gets one relative alarm")
check(alarmEffect(command: .add, due: dayDue) == .none, "a date-only due gets none")
check(alarmEffect(command: .add, due: noDue) == .none, "no due, no alarm")
let allDues: [Due?] = [timed, dayDue, noDue]
for command in [Command.set, .done, .reopen, .reminders, .events] {
    for due in allDues {
        check(alarmEffect(command: command, due: due) == .none, "\(command) never touches an alarm")
    }
}
// Whatever a set is given, its plan has no alarm effect.
for _ in 0..<500 {
    let groups: [[String]] = [["--title", "T"], ["--due", "2026-10-28T09:00"], ["--notes", "N"], ["--list", "L"], ["--clear-notes"]]
    var args = ["set", "ID"]
    for g in groups where Bool.random(using: &rng) { args += g }
    if case .run(let i) = parse(args) {
        check(alarmEffect(command: i.command, due: i.one("due").flatMap(parseDue)) == .none, "set's plan has no alarm effect")
    }
}

if failures > 0 {
    print("\(failures) checks failed (seed \(seed))")
    exit(1)
}
print("ok")
