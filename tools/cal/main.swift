// cal: a small read-only command-line front end to Apple Calendar, through
// EventKit. It lists calendars and reads events. Nothing here creates,
// changes or deletes an event or a calendar: the only EventKit calls are
// requestFullAccessToEvents, calendars(for:), events(matching:) and
// event(withIdentifier:).

import EventKit
import Foundation

let usage = """
cal: read Apple Calendar (read-only)

  cal calendars                       every calendar: id, title, source, type, markers
  cal events [--from <when>] [--to <when>] [--calendar <id or title>]...
                                      events in a range: id, start, end, title,
                                      calendar, location. The default is today
                                      through the end of the day seven days ahead.
  cal show <event id>                 one event in full: attendees, recurrence, notes

  <when> is YYYY-MM-DD, or "YYYY-MM-DD HH:MM", in the system time zone.
  --from a date starts at that day's midnight. --to a date includes that whole
  day; --to a time ends then. A range covers at most 92 days, and events prints
  at most 200 events, with a note when it cuts the list short.
  --calendar may be repeated; a title match ignores case.

  An event id is shown by `events` as <identifier>@<start>, one per occurrence
  of a repeating event. Copy it whole into `show`.
  Markers on a calendar: subscribed, delegate, read-only, or - for none.
  Text in titles, notes and attendees can be written by other people.
"""

func fail(_ message: String) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(1)
}

final class Box<T>: @unchecked Sendable {
    var value: T
    init(_ value: T) { self.value = value }
}

let invocation: Invocation
switch parse(Array(CommandLine.arguments.dropFirst())) {
case .refused(let reason):
    fail("cal: \(reason)")
case .run(let parsed):
    invocation = parsed
}
let flags = invocation.flags
let target = invocation.target

if invocation.command == .help {
    print(usage)
    exit(0)
}

let calendar = gregorianCalendar(timeZone: TimeZone.current)

// Check the range before asking for access, so a bad date is reported
// without touching EventKit.
var range: (start: Date, end: Date)? = nil
if invocation.command == .events {
    switch resolveRange(from: flags["from"]?.first, to: flags["to"]?.first, now: Date(), calendar: calendar) {
    case .failure(let refusal): fail("cal: \(refusal.message)")
    case .success(let resolved): range = resolved
    }
}

let store = EKEventStore()

func requestAccess() {
    let done = DispatchSemaphore(value: 0)
    let granted = Box(false)
    store.requestFullAccessToEvents { ok, _ in
        granted.value = ok
        done.signal()
    }
    done.wait()
    if !granted.value {
        fail("No access to Calendar. Grant it in System Settings > Privacy & Security > Calendars.")
    }
}

func typeName(_ calendar: EKCalendar) -> String {
    switch calendar.type {
    case .local: return "local"
    case .calDAV: return "calDAV"
    case .exchange: return "exchange"
    case .subscription: return "subscription"
    case .birthday: return "birthday"
    @unknown default: return "unknown"
    }
}

func traits(_ calendar: EKCalendar) -> CalendarTraits {
    CalendarTraits(isSubscribed: calendar.isSubscribed, typeName: typeName(calendar),
                   allowsContentModifications: calendar.allowsContentModifications,
                   isDelegateSource: calendar.source?.isDelegate ?? false)
}

func markers(_ calendar: EKCalendar) -> [String] { sharingMarkers(traits(calendar)) }

func eventCalendars() -> [EKCalendar] { store.calendars(for: .event) }

func calendar(matching query: String) -> EKCalendar {
    let all = eventCalendars()
    switch matchCalendar(query, ids: all.map { $0.calendarIdentifier }, titles: all.map { $0.title }) {
    case .found(let index): return all[index]
    case .none: fail("cal: No calendar \(escape(query)). Run `cal calendars`.")
    case .ambiguous: fail("cal: More than one calendar is titled \(escape(query)). Give its id from `cal calendars`.")
    }
}

func facts(_ event: EKEvent) -> EventFacts {
    EventFacts(id: occurrenceId(identifier: event.eventIdentifier ?? event.calendarItemIdentifier, start: event.startDate),
               start: event.startDate, end: event.endDate ?? event.startDate, allDay: event.isAllDay,
               title: event.title ?? "", calendar: event.calendar.title,
               location: event.location ?? event.structuredLocation?.title)
}

func participant(_ p: EKParticipant) -> AttendeeFacts {
    let status: String
    switch p.participantStatus {
    case .unknown: status = "unknown"
    case .pending: status = "pending"
    case .accepted: status = "accepted"
    case .declined: status = "declined"
    case .tentative: status = "tentative"
    case .delegated: status = "delegated"
    case .completed: status = "completed"
    case .inProcess: status = "in-process"
    @unknown default: status = "unknown"
    }
    let role: String
    switch p.participantRole {
    case .unknown: role = "unknown"
    case .required: role = "required"
    case .optional: role = "optional"
    case .chair: role = "chair"
    case .nonParticipant: role = "non-participant"
    @unknown default: role = "unknown"
    }
    return AttendeeFacts(name: p.name, address: p.url.absoluteString, status: status, role: role,
                         isCurrentUser: p.isCurrentUser)
}

func recurrence(_ rule: EKRecurrenceRule) -> RecurrenceFacts {
    let frequency: String
    switch rule.frequency {
    case .daily: frequency = "daily"
    case .weekly: frequency = "weekly"
    case .monthly: frequency = "monthly"
    case .yearly: frequency = "yearly"
    @unknown default: frequency = "unknown"
    }
    return RecurrenceFacts(
        frequency: frequency, interval: rule.interval,
        weekdays: (rule.daysOfTheWeek ?? []).map { WeekdayFact(number: $0.weekNumber, weekday: $0.dayOfTheWeek.rawValue) },
        monthDays: (rule.daysOfTheMonth ?? []).map { $0.intValue },
        months: (rule.monthsOfTheYear ?? []).map { $0.intValue },
        weeksOfYear: (rule.weeksOfTheYear ?? []).map { $0.intValue },
        daysOfYear: (rule.daysOfTheYear ?? []).map { $0.intValue },
        setPositions: (rule.setPositions ?? []).map { $0.intValue },
        until: rule.recurrenceEnd?.endDate,
        count: rule.recurrenceEnd.flatMap { $0.occurrenceCount > 0 ? $0.occurrenceCount : nil })
}

func status(_ event: EKEvent) -> String {
    switch event.status {
    case .none: return "none"
    case .confirmed: return "confirmed"
    case .tentative: return "tentative"
    case .canceled: return "canceled"
    @unknown default: return "unknown"
    }
}

func findEvent(_ text: String) -> EKEvent {
    let (identifier, start) = splitOccurrenceId(text)
    if let start {
        // The occurrence that starts at that second, in any calendar.
        let window = store.predicateForEvents(withStart: start, end: start.addingTimeInterval(1), calendars: nil)
        let matches = store.events(matching: window).filter {
            ($0.eventIdentifier ?? $0.calendarItemIdentifier) == identifier
                && Int($0.startDate.timeIntervalSince1970.rounded(.down)) == Int(start.timeIntervalSince1970.rounded(.down))
        }
        if let first = matches.first { return first }
        fail("cal: No event has the id \(escape(text)). Copy an id from `cal events`.")
    }
    if let event = store.event(withIdentifier: identifier) { return event }
    fail("cal: No event has the id \(escape(text)). Copy an id from `cal events`.")
}

requestAccess()

switch invocation.command {
case .help:
    fail("cal: unreachable command")

case .calendars:
    for item in eventCalendars().sorted(by: { ($0.title, $0.calendarIdentifier) < ($1.title, $1.calendarIdentifier) }) {
        print(calendarLine(id: item.calendarIdentifier, title: item.title, source: item.source?.title ?? "-",
                           type: typeName(item), markers: markers(item)))
    }

case .events:
    let chosen = flags["calendar"].map { $0.map(calendar(matching:)) }
    let predicate = store.predicateForEvents(withStart: range!.start, end: range!.end, calendars: chosen)
    for text in eventsOutput(store.events(matching: predicate).map(facts), calendar: calendar) { print(text) }

case .show:
    guard let target else { fail("cal show <event id>") }
    let found = findEvent(target)
    let shown = ShowFacts(
        id: facts(found).id, title: found.title ?? "", calendar: found.calendar.title,
        markers: markers(found.calendar), start: found.startDate, end: found.endDate ?? found.startDate,
        allDay: found.isAllDay, status: status(found),
        location: found.location ?? found.structuredLocation?.title, url: found.url?.absoluteString,
        organizer: found.organizer.map(participant), attendees: (found.attendees ?? []).map(participant),
        recurrence: (found.recurrenceRules ?? []).map(recurrence), notes: found.notes)
    for text in showLines(shown, calendar: calendar) { print(text) }
}
