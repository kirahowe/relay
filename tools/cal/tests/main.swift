// Self-test for Core.swift. Touches no EventKit. Run with tools/cal/test.sh.
import Foundation

var failures = 0
func check(_ ok: Bool, _ what: String, line: Int = #line) {
    if !ok { failures += 1; print("FAIL (line \(line)): \(what)") }
}
func refused(_ args: [String]) -> Bool { if case .refused = parse(args) { return true }; return false }
func runs(_ args: [String]) -> Invocation? { if case .run(let i) = parse(args) { return i }; return nil }

var utc = Calendar(identifier: .gregorian)
utc.timeZone = TimeZone(identifier: "UTC")!
var berlin = Calendar(identifier: .gregorian)
berlin.timeZone = TimeZone(identifier: "Europe/Berlin")!

func date(_ text: String, _ cal: Calendar = utc) -> Date {
    let w = parseWhen(text, calendar: cal)!
    var c = DateComponents()
    c.year = w.year; c.month = w.month; c.day = w.day
    c.hour = w.time?.hour ?? 0; c.minute = w.time?.minute ?? 0
    return cal.date(from: c)!
}
func range(_ from: String?, _ to: String?, now: String = "2026-10-16 14:30", _ cal: Calendar = utc) -> (start: Date, end: Date)? {
    if case .success(let r) = resolveRange(from: from, to: to, now: date(now, cal), calendar: cal) { return r }
    return nil
}

// parsing: commands and strict options
check(refused([]), "no args")
check(refused(["nope"]), "unknown command")
check(refused(["delete"]), "no delete command")
check(refused(["create", "x"]), "no create command")
check(runs(["help"])?.command == .help, "help")
check(runs(["calendars"])?.command == .calendars, "calendars")
check(refused(["calendars", "x"]), "calendars takes no argument")
check(refused(["calendars", "--all", "x"]), "calendars takes no option")
check(refused(["help", "extra"]), "help takes no argument")
check(runs(["events"])?.flags.isEmpty == true, "events with defaults")
check(refused(["events", "--bogus", "x"]), "unknown option")
check(refused(["events", "--mode", "all"]), "--mode is not an option")
check(refused(["events", "--from"]), "option without value")
check(refused(["events", "--from", "2026-01-01", "--from", "2026-01-02"]), "from given twice")
check(refused(["events", "--to", "2026-01-01", "--to", "2026-01-02"]), "to given twice")
check(refused(["events", "extra"]), "events takes no positional")
check(refused(["events", "--from=2026-01-01"]), "= form is an unknown option")
check(runs(["events", "--calendar", "A", "--calendar", "B"])?.flags["calendar"] == ["A", "B"], "calendar repeats")
check(runs(["events", "--calendar", "--from"])?.flags["calendar"] == ["--from"], "option value is data")
check(refused(["show"]), "show needs an id")
check(refused(["show", "a", "b"]), "show takes one id")
check(refused(["show", "a", "--url", "x"]), "show has no options")
check(runs(["show", "ABC@123"])?.target == "ABC@123", "show target")

// dates
check(parseWhen("2026-10-16", calendar: utc) == When(year: 2026, month: 10, day: 16, time: nil), "date")
check(parseWhen("2026-10-16 09:05", calendar: utc) == When(year: 2026, month: 10, day: 16, time: (9, 5)), "datetime")
check(parseWhen("2026-10-16T09:05", calendar: utc) == When(year: 2026, month: 10, day: 16, time: (9, 5)), "T datetime")
for bad in ["", "2026-1-1", "2026-13-01", "2026-02-30", "2026-10-16 24:00", "2026-10-16 09:60", "2026-10-16 9:05",
            "2026-10-16 09:05:00", "2026-10-16T09:05Z", "tomorrow", "26-10-16", "2026/10/16", "2026-10-16  09:05",
            "2026-10-16 09", "２０２６-10-16", "-2026-10-16", "2026-10-16 09:05 +0100"] {
    check(parseWhen(bad, calendar: utc) == nil, "rejects \(bad.debugDescription)")
}
check(parseWhen("2028-02-29", calendar: utc) != nil, "leap day")
check(parseWhen("2027-02-29", calendar: utc) == nil, "not a leap day")

// ranges
let defaults = range(nil, nil)!
check(defaults.start == date("2026-10-16 00:00"), "default starts at today's midnight")
check(defaults.end == date("2026-10-24 00:00"), "default ends after the day 7 days ahead")
check(range("2026-11-01", nil)!.end == date("2026-11-09"), "from alone: 7 days after its day")
check(range("2026-11-01 15:00", nil)!.start == date("2026-11-01 15:00"), "from datetime exact")
check(range("2026-11-01 15:00", nil)!.end == date("2026-11-09"), "from datetime default end")
check(range("2026-11-01", "2026-11-01")!.end == date("2026-11-02"), "to date includes its day")
check(range("2026-11-01", "2026-11-01 10:00")!.end == date("2026-11-01 10:00"), "to datetime exact")
check(range(nil, "2026-10-16")!.end == date("2026-10-17"), "to only: today")
check(range("2026-11-01", "2026-10-31") == nil, "to before from")
check(range("2026-11-01 10:00", "2026-11-01 10:00") == nil, "empty range")
check(range("2026-11-01 10:00", "2026-11-01 09:00") == nil, "backwards range")
check(range(nil, "2026-10-01") == nil, "to before today with default from")
check(range("2026-01-01", "2026-04-02") != nil, "92 days (1 Jan to 2 Apr inclusive is 92 days)")
check(range("2026-01-01", "2026-04-03") == nil, "93 days is too long")
check(range("2026-01-01 00:00", "2026-04-03 00:00") != nil, "92 days exactly by datetime")
check(range("2026-01-01 00:00", "2026-04-03 00:01") == nil, "one minute over")
check(range("bad", nil) == nil, "bad from")
check(range(nil, "bad") == nil, "bad to")
// time zones: the same default is local midnight, not UTC midnight
let bd = range(nil, nil, now: "2026-10-16 01:30", berlin)!
check(bd.start == date("2026-10-16 00:00", berlin), "berlin default start")
check(bd.end == date("2026-10-24 00:00", berlin), "berlin default end")
// DST: Berlin leaves summer time on 2026-10-25; a day is still a calendar day
let dst = range("2026-10-24", "2026-10-26", berlin)!
check(dst.end == date("2026-10-27 00:00", berlin), "DST end is a local midnight")
check(dst.end.timeIntervalSince(dst.start) == 73 * 3600, "three calendar days across a DST change")
check(range("2026-10-01", "2026-12-31", berlin) != nil, "92 days across DST fits")

// a clock change at midnight moves the day's start to 01:00; the next day still starts at 00:00
var santiago = Calendar(identifier: .gregorian)
santiago.timeZone = TimeZone(identifier: "America/Santiago")!
let sc = range("2026-09-06", "2026-09-06", santiago)!
check(formatInstant(sc.start, calendar: santiago) == "2026-09-06 01:00", "the day starts at 01:00 where midnight is skipped")
check(formatInstant(sc.end, calendar: santiago) == "2026-09-07 00:00", "a date --to ends at the next midnight, not 01:00")
check(formatInstant(range("2026-09-06", nil, santiago)!.end, calendar: santiago) == "2026-09-14 00:00", "default end is a midnight")
check(formatInstant(range(nil, nil, now: "2026-09-06 12:00", santiago)!.end, calendar: santiago) == "2026-09-14 00:00", "default end from now is a midnight")
check(formatInstant(range("2026-09-05", "2026-09-05", santiago)!.end, calendar: santiago) == "2026-09-06 01:00", "the day before ends where the next begins")

// a wall time skipped by a clock change is refused, not moved (Berlin springs forward 2026-03-29 02:00 to 03:00)
check(range("2026-03-29 02:30", "2026-03-29 04:00", berlin) == nil, "from in the skipped hour")
check(range("2026-03-29 01:00", "2026-03-29 02:30", berlin) == nil, "to in the skipped hour")
check(range("2026-03-29 01:59", "2026-03-29 03:00", berlin) != nil, "the minutes around the gap exist")
check(range("2026-03-29 03:00", "2026-03-29 04:00", berlin)!.start == date("2026-03-29 03:00", berlin), "03:00 is real")
check(parseWhen("2026-03-29 02:30", calendar: berlin) != nil, "parsing alone accepts it; the range rule refuses")
// the fall-back hour happens twice; it reads as the first one and keeps its wall time
let fall = range("2026-10-25 02:30", "2026-10-25 04:00", berlin)!
check(formatInstant(fall.start, calendar: berlin) == "2026-10-25 02:30", "ambiguous time keeps its wall time")
// the date syntax means the Gregorian calendar whatever the regional settings say
var buddhist = Calendar(identifier: .buddhist); buddhist.timeZone = utc.timeZone
var japanese = Calendar(identifier: .japanese); japanese.timeZone = utc.timeZone
check(range("2026-10-16", nil, buddhist) == nil && range("2026-10-16", nil, japanese) == nil, "non-Gregorian calendars are refused")
let greg = gregorianCalendar(timeZone: TimeZone(identifier: "Asia/Tokyo")!)
check(greg.identifier == .gregorian && greg.timeZone.identifier == "Asia/Tokyo", "gregorianCalendar keeps the zone")
check(formatDay(date("2026-10-16", greg), calendar: greg) == "2026-10-16", "Gregorian round trip")
check(formatDay(Date(timeIntervalSince1970: 1_790_000_000), calendar: greg).hasPrefix("2026-"), "Gregorian year printed")

// property: random valid ranges respect the rules
var rng = SystemRandomNumberGenerator()
for _ in 0..<2000 {
    let start = date("2026-01-01") .addingTimeInterval(Double(Int.random(in: 0...(400 * 24 * 60), using: &rng)) * 60)
    let from = formatInstant(start, calendar: berlin)
    let span = Int.random(in: -3...100, using: &rng)
    let toDate = berlin.date(byAdding: .day, value: span, to: start)!
    let to = formatDay(toDate, calendar: berlin)
    if let r = range(from, to, now: "2026-06-01 12:00", berlin) {
        check(r.end > r.start, "end after start")
        check(r.end <= berlin.date(byAdding: .day, value: maxRangeDays, to: r.start)!, "within the cap")
    }
    if let r = range(from, nil, now: "2026-06-01 12:00", berlin) {
        check(r.end > r.start && r.end <= berlin.date(byAdding: .day, value: 8, to: r.start)!, "default span at most 8 days")
    }
    // when it is refused, one of the rules is why
    if range(from, to, now: "2026-06-01 12:00", berlin) == nil {
        let end = berlin.date(byAdding: .day, value: 1, to: berlin.startOfDay(for: toDate))!
        let s = parseWhen(from, calendar: berlin).flatMap { w -> Date? in date(from, berlin) }!
        check(end <= s || end > berlin.date(byAdding: .day, value: maxRangeDays, to: s)!, "refusal has a reason: \(from) \(to)")
    }
}

// date formatting
check(formatInstant(date("2026-10-16 09:05"), calendar: utc) == "2026-10-16 09:05", "format instant")
check(formatInstant(date("2026-10-16 09:05"), calendar: berlin) == "2026-10-16 11:05", "format in another zone")
for _ in 0..<1000 {
    let d = Date(timeIntervalSince1970: Double(Int.random(in: 0...2_000_000_000, using: &rng)))
    let text = formatInstant(d, calendar: berlin)
    let back = parseWhen(text, calendar: berlin)
    check(back != nil, "formatted instant parses: \(text)")
}

// occurrence ids
func randomId() -> String {
    let pool = Array("ABCDEF0123456789-:@/")
    return String((0..<Int.random(in: 1...30, using: &rng)).map { _ in pool.randomElement(using: &rng)! })
}
let ev0 = Date(timeIntervalSince1970: 1_800_000_000)
check(occurrenceId(identifier: "ABC", start: ev0) == "ABC@1800000000", "occurrence id")
check(splitOccurrenceId("ABC@1800000000").identifier == "ABC", "split id")
check(splitOccurrenceId("ABC@1800000000").start == ev0, "split start")
check(splitOccurrenceId("ABC").start == nil, "no suffix")
check(splitOccurrenceId("a@b@12").identifier == "a@b", "id may hold an @")
check(splitOccurrenceId("a@b").start == nil && splitOccurrenceId("a@b").identifier == "a@b", "non-numeric suffix stays in the id")
check(splitOccurrenceId("a@").start == nil, "empty suffix")
check(splitOccurrenceId("ABC@-315619200").identifier == "ABC", "pre-1970 id splits")
check(splitOccurrenceId("ABC@-315619200").start == Date(timeIntervalSince1970: -315619200), "pre-1970 start")
check(splitOccurrenceId("a@-").start == nil && splitOccurrenceId("a@-").identifier == "a@-", "bare minus is no suffix")
check(splitOccurrenceId("a@--5").start == nil, "double minus is no suffix")
check(splitOccurrenceId("a@+5").start == nil, "plus is no suffix")
check(splitOccurrenceId("a@1234567890123").start == nil, "absurdly long suffix")
for _ in 0..<500 {
    let id = randomId()
    let t = Date(timeIntervalSince1970: Double(Int.random(in: -4_000_000_000...4_000_000_000, using: &rng)))
    let (i, s) = splitOccurrenceId(occurrenceId(identifier: id, start: t))
    check(i == id && s == t, "id round trip \(id.debugDescription)")
}

// the id printed by `events` is what `show` receives: hostile identifiers survive the whole path
let hostileIds = ["a\\b", "a\tb", "--evil", "-x", "-", "", "a@b", "a@b@7", "100%", "%41", "%zz", "x\u{2029}y", "日本語", "e\u{301}", "\u{0}z",
                  "note: 0 more events", "\u{202E}rtl", "a b", "UID:abc-123@example.com", "~._:-"]
for id in hostileIds {
    for start in [Date(timeIntervalSince1970: 1_800_000_000), Date(timeIntervalSince1970: -315_619_200), Date(timeIntervalSince1970: 0)] {
        let printed = occurrenceId(identifier: id, start: start)
        let row = eventLine(EventFacts(id: printed, start: start, end: start, allDay: false, title: "t", calendar: "c", location: nil), calendar: utc)
        let field = String(row.split(separator: "\t", omittingEmptySubsequences: false)[0])
        check(field == printed, "the id column is the id, unescaped: \(id.debugDescription)")
        check(escape(printed) == printed, "a printed id needs no escaping: \(printed.debugDescription)")
        check(!printed.hasPrefix("-"), "a printed id never starts like an option: \(printed.debugDescription)")
        guard let parsed = runs(["show", field]), let target = parsed.target else { check(false, "show accepts the printed id \(printed.debugDescription)"); continue }
        let (identifier, back) = splitOccurrenceId(target)
        check(identifier == id && back == start, "printed id round trips: \(id.debugDescription) via \(printed.debugDescription)")
    }
}
check(encodeIdentifier("a@b%") == "a%40b%25", "@ and % are encoded")
check(encodeIdentifier("--e") == "%2D-e", "only a leading dash is encoded")
check(decodeIdentifier("a%zzb") == "a%zzb" && decodeIdentifier("%4") == "%4" && decodeIdentifier("%FF") == "%FF", "malformed encodings are taken as typed")
check(decodeIdentifier("%e6%97%a5") == "日", "lowercase hex decodes")
for _ in 0..<2000 {
    let id = randomText() + randomId()
    check(decodeIdentifier(encodeIdentifier(id)) == id, "encode/decode round trip \(id.debugDescription)")
    check(escape(encodeIdentifier(id)) == encodeIdentifier(id), "an encoded identifier needs no escaping")
}

// calendars
let priv = CalendarTraits(isSubscribed: false, typeName: "calDAV", allowsContentModifications: true, isDelegateSource: false)
var sub = priv; sub.isSubscribed = true
var del = priv; del.isDelegateSource = true
var ro = priv; ro.allowsContentModifications = false
var web = priv; web.typeName = "subscription"
check(sharingMarkers(priv).isEmpty, "private calendar has no marker")
check(sharingMarkers(sub) == ["subscribed"], "subscribed")
check(sharingMarkers(web) == ["subscribed"], "subscription type")
check(sharingMarkers(del) == ["delegate"], "delegate")
check(sharingMarkers(ro) == ["read-only"], "read-only")
check(sharingMarkers(CalendarTraits(isSubscribed: true, typeName: "subscription", allowsContentModifications: false, isDelegateSource: true))
      == ["subscribed", "delegate", "read-only"], "all markers, fixed order")
check(calendarLine(id: "I1", title: "Work", source: "iCloud", type: "calDAV", markers: []) == "I1\tWork\tiCloud\tcalDAV\t-", "calendar line")
check(calendarLine(id: "I1", title: "Work", source: "iCloud", type: "calDAV", markers: ["subscribed", "read-only"]).hasSuffix("\tsubscribed,read-only"), "markers column")
check(matchCalendar("I2", ids: ["I1", "I2"], titles: ["a", "b"]) == .found(1), "match by id")
check(matchCalendar("WORK", ids: ["I1", "I2"], titles: ["Home", "Work"]) == .found(1), "match by title, any case")
check(matchCalendar("x", ids: ["I1"], titles: ["Home"]) == .none, "no match")
check(matchCalendar("home", ids: ["I1", "I2"], titles: ["Home", "HOME"]) == .ambiguous, "same title twice")
check(matchCalendar("I2", ids: ["I1", "I2"], titles: ["I2", "I2"]) == .found(1), "id beats title")

// events
func event(_ id: String = "E@1", start: String = "2026-10-16 09:00", end: String = "2026-10-16 10:30", allDay: Bool = false,
           title: String = "Standup", calendar: String = "Work", location: String? = nil, cal: Calendar = utc) -> EventFacts {
    EventFacts(id: id, start: date(start, cal), end: date(end, cal), allDay: allDay, title: title, calendar: calendar, location: location)
}
check(eventLine(event(), calendar: utc) == "E@1\t2026-10-16 09:00\t2026-10-16 10:30\tStandup\tWork\t-", "timed event line")
check(eventLine(event(location: "Room 4"), calendar: utc).hasSuffix("\tRoom 4"), "location")
check(eventLine(event(location: ""), calendar: utc).hasSuffix("\t-"), "empty location")
check(eventLine(event(start: "2026-10-16 00:00", end: "2026-10-17 00:00", allDay: true), calendar: utc)
      == "E@1\t2026-10-16\tall-day\tStandup\tWork\t-", "all-day, end at next midnight")
check(eventLine(event(start: "2026-10-16 00:00", end: "2026-10-16 23:59", allDay: true), calendar: utc)
      == "E@1\t2026-10-16\tall-day\tStandup\tWork\t-", "all-day, end at last minute")
check(eventLine(event(start: "2026-10-16 00:00", end: "2026-10-19 00:00", allDay: true), calendar: utc)
      .contains("\tall-day to 2026-10-18\t"), "multi-day all-day names its last day")
check(eventLine(event(start: "2026-10-16 23:00", end: "2026-10-17 01:00"), calendar: utc).contains("\t2026-10-17 01:00\t"), "overnight event")
check(sortedEvents([event("b", start: "2026-10-17 09:00"), event("a", start: "2026-10-16 09:00", title: "Z"), event("c", start: "2026-10-16 09:00", title: "A")]).map { $0.id } == ["c", "a", "b"], "sorted by start then title")

// the cap and its note
let many = (0..<5).map { event("E\($0)@1", start: "2026-10-16 0\($0):00", end: "2026-10-16 0\($0):30") }
check(eventsOutput(many, calendar: utc, limit: 5).count == 5, "at the cap there is no note")
let capped = eventsOutput(many, calendar: utc, limit: 3)
check(capped.count == 4, "three events and a note")
check(capped.last!.hasPrefix("note: 2 more events not shown"), "the note counts what was cut: \(capped.last!)")
check(capped.dropLast().allSatisfy { !$0.hasPrefix("note:") }, "events are not notes")
check(eventsOutput([], calendar: utc).isEmpty, "no events, no output")
check(eventsOutput((0..<(maxEvents + 1)).map { event("E\($0)@1") }, calendar: utc).count == maxEvents + 1, "default cap is maxEvents")

// recurrence
func rule(_ f: String, _ interval: Int = 1, weekdays: [WeekdayFact] = [], monthDays: [Int] = [], months: [Int] = [],
          until: Date? = nil, count: Int? = nil) -> RecurrenceFacts {
    RecurrenceFacts(frequency: f, interval: interval, weekdays: weekdays, monthDays: monthDays, months: months, until: until, count: count)
}
check(describeRecurrence(rule("daily"), calendar: utc) == "every day", "daily")
check(describeRecurrence(rule("weekly", 2, weekdays: [WeekdayFact(number: 0, weekday: 2), WeekdayFact(number: 0, weekday: 4)]), calendar: utc)
      == "every 2 weeks on Mon, Wed", "biweekly")
check(describeRecurrence(rule("monthly", weekdays: [WeekdayFact(number: 2, weekday: 3)]), calendar: utc) == "every month on 2nd Tue", "second Tuesday")
check(describeRecurrence(rule("monthly", weekdays: [WeekdayFact(number: -1, weekday: 6)]), calendar: utc) == "every month on last Fri", "last Friday")
check(describeRecurrence(rule("monthly", monthDays: [1, 15]), calendar: utc) == "every month on day 1, 15", "month days")
check(describeRecurrence(rule("yearly", months: [3]), calendar: utc) == "every year in Mar", "yearly")
check(describeRecurrence(rule("weekly", until: date("2026-12-01")), calendar: utc) == "every week, until 2026-12-01", "until")
check(describeRecurrence(rule("daily", count: 10), calendar: utc) == "every day, 10 times", "count")
for (n, text) in [(1, "1st"), (2, "2nd"), (3, "3rd"), (4, "4th"), (11, "11th"), (12, "12th"), (13, "13th"), (21, "21st"), (22, "22nd"),
                  (23, "23rd"), (101, "101st"), (111, "111th"), (-1, "last"), (-2, "2nd from last"), (-3, "3rd from last"), (-11, "11th from last")] {
    check(describeRecurrence(RecurrenceFacts(frequency: "monthly", interval: 1, weekdays: [], monthDays: [], months: [], setPositions: [n]), calendar: utc)
          == "every month, keeping only the \(text) of those", "ordinal \(n)")
}
check(describeRecurrence(rule("daily", 0, count: 0), calendar: utc) == "every day", "zero interval, zero count")

check(describeRecurrence(RecurrenceFacts(frequency: "monthly", interval: 1,
        weekdays: (2...6).map { WeekdayFact(number: 0, weekday: $0) }, monthDays: [], months: [], setPositions: [-1]), calendar: utc)
      == "every month on Mon, Tue, Wed, Thu, Fri, keeping only the last of those", "last weekday of the month")
check(describeRecurrence(RecurrenceFacts(frequency: "monthly", interval: 1, weekdays: [], monthDays: [], months: [], setPositions: [1, 3]), calendar: utc)
      .hasSuffix("keeping only the 1st, 3rd of those"), "several positions")
check(describeRecurrence(RecurrenceFacts(frequency: "yearly", interval: 1, weekdays: [], monthDays: [], months: [], weeksOfYear: [20], daysOfYear: [100, -1]), calendar: utc)
      == "every year in week 20 of the year on day 100, -1 of the year", "week and day of year")

// show
let boss = AttendeeFacts(name: "Ana", address: "mailto:ana@example.com", status: "accepted", role: "chair", isCurrentUser: false)
let me = AttendeeFacts(name: nil, address: "MAILTO:me@example.com", status: "pending", role: "required", isCurrentUser: true)
func facts(notes: String? = nil, attendees: [AttendeeFacts] = [], organizer: AttendeeFacts? = nil, title: String = "Review",
           recurrence: [RecurrenceFacts] = [], location: String? = nil, url: String? = nil) -> ShowFacts {
    ShowFacts(id: "E@1", title: title, calendar: "Work", markers: ["subscribed"], start: date("2026-10-16 09:00"), end: date("2026-10-16 10:00"),
              allDay: false, status: "confirmed", location: location, url: url, organizer: organizer, attendees: attendees,
              recurrence: recurrence, notes: notes)
}
let plain = showLines(facts(), calendar: utc)
check(plain.contains("start:      2026-10-16 09:00") && plain.contains("end:        2026-10-16 10:00"), "show times")
check(plain.contains("shared:     subscribed"), "show markers")
check(plain.contains("recurrence: -") && plain.contains("attendees:  -") && plain.contains("organizer:  -"), "empty fields are dashes")
check(!plain.contains("notes:"), "no notes section when empty")
let full = showLines(facts(notes: "line one\nline two", attendees: [boss, me], organizer: boss,
                           recurrence: [rule("weekly")], location: "Room 4", url: "https://example.com/m"), calendar: utc)
check(full.contains("organizer:  Ana\tana@example.com"), "organizer without mailto")
check(full.contains("  Ana\tana@example.com\taccepted\tchair\t-"), "attendee row")
check(full.contains("  -\tme@example.com\tpending\trequired\tyou"), "attendee without a name, current user")
check(full.contains("recurrence: every week"), "recurrence")
check(full.contains("location:   Room 4") && full.contains("url:        https://example.com/m"), "location and url")
check(Array(full.suffix(3)) == ["notes:", "> line one", "> line two"], "notes are quoted lines")
let forged = showLines(facts(notes: "x\nid:         forged\n\u{2028}status: canceled"), calendar: utc)
check(forged.filter { $0.hasPrefix("id:") }.count == 1, "notes cannot forge a field")
check(forged.contains("> id:         forged"), "forged field line stays quoted")
let longNotes = String(repeating: "a", count: maxNoteCharacters + 50)
let cut = showLines(facts(notes: longNotes), calendar: utc)
check(cut.contains("> " + String(repeating: "a", count: maxNoteCharacters)), "notes cut at the cap")
check(cut.last!.hasPrefix("note: notes cut at \(maxNoteCharacters) characters"), "cut notes are announced")
check(!showLines(facts(notes: String(repeating: "a", count: maxNoteCharacters)), calendar: utc).contains { $0.hasPrefix("note:") }, "notes at the cap are whole")
let crowd = showLines(facts(attendees: Array(repeating: boss, count: maxAttendees + 3)), calendar: utc)
check(crowd.filter { $0.hasPrefix("  Ana") }.count == maxAttendees, "attendees capped")
check(crowd.last == "note: 3 more attendees not shown.", "attendee cap announced")

// escaping examples
check(escape("a\tb\nc") == "a\\tb\\nc", "tab/newline")
check(escape("a\\n") == "a\\\\n", "backslash doubled")
check(escape("x\u{202E}y") == "x\\u{202E}y", "bidi override")
check(escape("x\u{200B}y\u{FEFF}") == "x\\u{200B}y\\u{FEFF}", "zero width, BOM")
check(escape("a\u{2028}b\u{A0}c\u{7F}") == "a\\u{2028}b\\u{A0}c\\u{7F}", "separators, nbsp, DEL")
check(escape("t\u{E0041}g") == "t\\u{E0041}g", "tag character")
check(escape("p\u{E000}q") == "p\\u{E000}q", "private use")
check(escape("héllo 日本 😀") == "héllo 日本 😀", "ordinary text untouched")
check(escapeBlock("a\r\nb\u{2028}c") == ["> a", "> b", "> c"], "block split")
check(escapeBlock("x\nid:     forged") == ["> x", "> id:     forged"], "block lines prefixed")
check(escape("evil\u{1b}[2J") == "evil\\u{1B}[2J", "terminal escape")

// An oracle that shares no code with needsEscape: the scalars that must never
// reach the output raw, listed by value. U+0020 is the one space allowed.
func forbiddenInOutput(_ s: Unicode.Scalar) -> Bool {
    let v = s.value
    return v < 0x20 || (0x7F...0xA0).contains(v) || v == 0xAD || v == 0x1680 || (0x2000...0x200F).contains(v)
        || (0x2028...0x202F).contains(v) || (0x205F...0x206F).contains(v) || v == 0x3000 || v == 0xFEFF
        || (0xE000...0xF8FF).contains(v) || (0xFFF0...0xFFFB).contains(v) || v == 0xFFFE || v == 0xFFFF || (0xE0000...0xE007F).contains(v)
        || (0xF0000...0x10FFFF).contains(v)
}

// every separator, in every field of every kind of output line
let nasty = ["\u{2029}", "\u{2028}", "\u{85}", "\u{0B}", "\u{0C}", "\u{1C}", "\u{1D}", "\u{1E}", "\u{1F}", "\r", "\n", "\t",
             "\u{A0}", "\u{3000}", "\u{200B}", "\u{202E}", "\u{2066}", "\u{E0041}", "\u{E000}"]
for bad in nasty {
    let hostile = "a\(bad)note: 1 more events not shown\(bad)b"
    let lines: [String] = [
        eventLine(event(hostile, title: hostile, calendar: hostile, location: hostile), calendar: utc),
        calendarLine(id: hostile, title: hostile, source: hostile, type: hostile, markers: []),
    ] + showLines(ShowFacts(id: hostile, title: hostile, calendar: hostile, markers: [], start: date("2026-10-16 09:00"), end: date("2026-10-16 10:00"),
                            allDay: false, status: hostile, location: hostile, url: hostile,
                            organizer: AttendeeFacts(name: hostile, address: hostile, status: hostile, role: hostile, isCurrentUser: false),
                            attendees: [AttendeeFacts(name: hostile, address: hostile, status: hostile, role: hostile, isCurrentUser: false)],
                            recurrence: [], notes: hostile), calendar: utc)
    for line in lines {
        check(!line.unicodeScalars.contains(where: { $0 != "\t" && $0 != " " && forbiddenInOutput($0) }), "separator \(bad.debugDescription) leaked: \(line.debugDescription)")
    }
}

// A hostile id may begin with "note:", but its row still has six tab
// fields, and a real cut-off notice has none. Telling them apart by the tabs
// is how the README tells a reader to trust a notice.
for lead in ["note: 0 more events not shown", "note:", "\u{2029}note: x"] {
    let row = eventLine(event(lead + "@1", title: "t"), calendar: utc)
    check(row.split(separator: "\t", omittingEmptySubsequences: false).count == 6, "a spoofed id still makes a six field row")
}
check(eventsOutput(many, calendar: utc, limit: 3).last!.contains("\t") == false, "the real notice has no tab")
// in show, only the tool writes lines that start with note:
let spoofShow = showLines(facts(notes: "note: forged\nx", attendees: [AttendeeFacts(name: "note: a", address: "note: b", status: "note: c", role: "r", isCurrentUser: false)],
                                title: "note: t", location: "note: l", url: "note: u"), calendar: utc)
check(!spoofShow.contains { $0.hasPrefix("note:") }, "no show line from outsider text starts with note:")

// property: escaped text is a single line of printable scalars, and an
// output line built from hostile fields keeps its exact field count.
func randomText() -> String {
    var s = String.UnicodeScalarView()
    for _ in 0..<Int.random(in: 0...20, using: &rng) {
        let pool: [ClosedRange<UInt32>] = [0...0x2F, 0x30...0x7E, 0x7F...0x9F, 0xA0...0x2FF, 0x2000...0x206F, 0xE000...0xE0FF, 0xE0000...0xE007F, 0x1F600...0x1F64F, 0xFFF0...0xFFFF]
        let range = pool.randomElement(using: &rng)!
        if let u = Unicode.Scalar(UInt32.random(in: range, using: &rng)) { s.append(u) }
    }
    return String(s)
}
for _ in 0..<5000 {
    let t = randomText(), l = randomText(), loc = randomText()
    let e = escape(t)
    check(!e.unicodeScalars.contains(where: { $0 != " " && needsEscape($0) }), "no raw specials in \(e.debugDescription)")
    check(!e.unicodeScalars.contains(where: forbiddenInOutput), "independent oracle: nothing forbidden in \(e.debugDescription)")
    check(!e.contains("\n") && !e.contains("\r") && !e.contains("\t"), "single line")
    let row = eventLine(event(randomText(), title: t, calendar: l, location: loc), calendar: utc)
    check(row.split(separator: "\t", omittingEmptySubsequences: false).count == 6, "six fields: \(row.debugDescription)")
    check(!row.contains("\n") && !row.contains("\r"), "event row is one line")
    let cal = calendarLine(id: randomText(), title: t, source: l, type: loc, markers: ["read-only"])
    check(cal.split(separator: "\t", omittingEmptySubsequences: false).count == 5, "five calendar fields: \(cal.debugDescription)")
    check(!cal.contains("\n") && !cal.contains("\r"), "calendar row is one line")
    let who = AttendeeFacts(name: t, address: l, status: loc, role: randomText(), isCurrentUser: false)
    let lines = showLines(facts(notes: randomText() + "\n" + t, attendees: [who], organizer: who, title: t, location: loc, url: l), calendar: utc)
    let keys = ["id:", "title:", "calendar:", "shared:", "start:", "end:", "status:", "location:", "url:", "organizer:", "recurrence:", "attendees:"]
    for line in lines {
        check(!line.contains("\n") && !line.contains("\r"), "show line is one line")
    }
    for key in keys {
        check(lines.filter { $0.hasPrefix(key) }.count == 1, "\(key) appears once in \(lines.debugDescription)")
    }
    check(lines.filter { $0.hasPrefix("  ") }.count == 1 && lines.first(where: { $0.hasPrefix("  ") })!.split(separator: "\t", omittingEmptySubsequences: false).count == 5,
          "one attendee row of five fields")
    check(lines.first(where: { $0.hasPrefix("organizer:") })!.split(separator: "\t", omittingEmptySubsequences: false).count == 2, "organizer has two fields")
}

if failures == 0 { print("ok") } else { print("\(failures) failure(s)"); exit(1) }
