// eventkit: a command-line front end to Apple Calendar and Reminders,
// through EventKit.
//
// Calendar events are read-only: no EventKit call here writes an event.
// Reminders can be added, edited, completed and reopened, only in lists
// named with --allow. Nothing here removes an event, a reminder, a calendar
// or a list: the code calls no remove(...) method, and there is no delete
// command.

import EventKit
import Foundation

let usageText = """
eventkit: read Apple Calendar and manage Apple Reminders

Every command except help prints one JSON object. On failure it prints
{"error": <text>, "code": <code>} and exits 1. Codes: usage, no-access,
not-found, refused, failed.

  eventkit access
  eventkit calendars
  eventkit events --from <instant> --to <instant> [--calendar <title>]...
  eventkit lists
  eventkit reminders --list <title> [--list <title>]...
  eventkit --allow <title>... add --list <title> --title <text> [--due <due>] [--notes <text>]
  eventkit --allow <title>... set <id> [--title <text>] [--due <due> | --clear-due] [--notes <text> | --clear-notes] [--list <title>]
  eventkit --allow <title>... done <id>
  eventkit --allow <title>... reopen <id>

  <instant> is a UTC instant: 2026-10-28T14:00:00Z
  <due> is a date, 2026-10-28, or local wall time, 2026-10-28T09:00
  --allow comes before the command word. A write is refused unless the
  reminder's list, and the destination list, are named by --allow.
  There is no delete command.
"""

func die(_ failure: Failure) -> Never {
    print(render(errorObject(failure)))
    exit(1)
}

func fail(_ code: ErrorCode, _ message: String) -> Never { die(Failure(code: code, message: message)) }

func succeed(_ object: [String: Any]) -> Never {
    print(render(object))
    exit(0)
}

final class Box<T>: @unchecked Sendable {
    var value: T
    init(_ value: T) { self.value = value }
}

// MARK: TCC responsibility

// macOS attributes Calendar and Reminders access to the "responsible"
// process, which is normally the parent (Terminal, bb, a launchd job). A
// private libSystem call lets a spawned child disclaim that, so the access
// request and the stored grant are in eventkit's own name and carry over
// between a terminal and the brew service. The symbol is private API.
@_silgen_name("responsibility_spawnattrs_setdisclaim")
func responsibility_spawnattrs_setdisclaim(_ attr: UnsafeMutablePointer<posix_spawnattr_t?>, _ disclaim: Int32) -> Int32

/// Re-run this binary with responsibility disclaimed, passing stdin, stdout
/// and stderr through, wait, and exit with its status. Returns only when
/// the spawn could not be set up, in which case the caller carries on in
/// this process (access is then attributed to the parent, which still works
/// for whoever already granted it there).
///
/// The child is told it is the child by an argument (disclaimMarker) rather
/// than an environment variable.
func runDisclaimed(_ args: [String]) {
    var attr: posix_spawnattr_t? = nil
    guard posix_spawnattr_init(&attr) == 0 else { return }
    defer { posix_spawnattr_destroy(&attr) }
    guard responsibility_spawnattrs_setdisclaim(&attr, 1) == 0 else { return }

    var buffer = [CChar](repeating: 0, count: 4096)
    var size = UInt32(buffer.count)
    guard _NSGetExecutablePath(&buffer, &size) == 0 else { return }
    let path = String(cString: buffer)

    var argv: [UnsafeMutablePointer<CChar>?] = ([path, disclaimMarker] + args).map { strdup($0) }
    argv.append(nil)
    defer { for pointer in argv { free(pointer) } }

    var envp: [UnsafeMutablePointer<CChar>?] = ProcessInfo.processInfo.environment.map { strdup("\($0.key)=\($0.value)") }
    envp.append(nil)
    defer { for pointer in envp { free(pointer) } }

    var pid: pid_t = 0
    guard posix_spawn(&pid, path, nil, &attr, argv, envp) == 0 else { return }

    var status: Int32 = 0
    while waitpid(pid, &status, 0) < 0 {
        if errno != EINTR { exit(1) }
    }
    if status & 0x7f == 0 { exit((status >> 8) & 0xff) }
    exit(128 + (status & 0x7f))
}

// MARK: start

let (arguments, alreadyDisclaimed) = stripDisclaimMarker(Array(CommandLine.arguments.dropFirst()))
let invocation: Invocation
switch parse(arguments) {
case .failure(let failure): die(failure)
case .run(let parsed): invocation = parsed
}
if invocation.command == .help {
    print(usageText)
    exit(0)
}
if !alreadyDisclaimed { runDisclaimed(arguments) }

let store = EKEventStore()
let allow = invocation.allow
let timeZoneCalendar: Calendar = {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone.current
    return calendar
}()

// MARK: access

func statusName(_ status: EKAuthorizationStatus) -> String {
    switch status {
    case .fullAccess: return "granted"
    case .denied: return "denied"
    case .restricted: return "restricted"
    case .notDetermined: return "not-determined"
    case .writeOnly: return "write-only"
    default: return "unknown"
    }
}

func request(_ entity: EKEntityType) {
    let done = DispatchSemaphore(value: 0)
    if #available(macOS 14.0, *) {
        let handler: EKEventStoreRequestAccessCompletionHandler = { _, _ in done.signal() }
        if entity == .event {
            store.requestFullAccessToEvents(completion: handler)
        } else {
            store.requestFullAccessToReminders(completion: handler)
        }
    } else {
        store.requestAccess(to: entity) { _, _ in done.signal() }
    }
    done.wait()
}

func require(_ entity: EKEntityType) {
    guard EKEventStore.authorizationStatus(for: entity) == .fullAccess else {
        let what = entity == .event ? "Calendar" : "Reminders"
        fail(.noAccess, "No access to \(what). Run `relay eventkit-access` in a terminal once to grant it.")
    }
}

// MARK: helpers

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

func markers(_ calendar: EKCalendar) -> [String] {
    sharingMarkers(CalendarTraits(isSubscribed: calendar.isSubscribed, typeName: typeName(calendar),
                                  allowsContentModifications: calendar.allowsContentModifications,
                                  isDelegateSource: calendar.source?.isDelegate ?? false))
}

func instantString(_ date: Date?) -> String? { date.map(formatInstant) }

func nonEmpty(_ text: String?) -> String? { (text ?? "").isEmpty ? nil : text }

// MARK: events

func eventStatus(_ event: EKEvent) -> String {
    switch event.status {
    case .none: return "none"
    case .confirmed: return "confirmed"
    case .tentative: return "tentative"
    case .canceled: return "canceled"
    @unknown default: return "unknown"
    }
}

func eventFacts(_ event: EKEvent) -> EventFacts {
    let start = event.startDate ?? Date(timeIntervalSince1970: 0)
    let end = event.endDate ?? start
    let startText = event.isAllDay ? localDay(start, calendar: timeZoneCalendar) : formatInstant(start)
    let endText = event.isAllDay ? allDayEnd(start: start, end: end, calendar: timeZoneCalendar) : formatInstant(end)
    let identifier = nonEmpty(event.calendarItemExternalIdentifier) ?? event.calendarItemIdentifier
    return EventFacts(
        key: eventKey(identifier: identifier, start: startText),
        id: event.eventIdentifier ?? event.calendarItemIdentifier,
        calendar: event.calendar.title, title: event.title ?? "", start: startText, end: endText,
        allDay: event.isAllDay, location: nonEmpty(event.location ?? event.structuredLocation?.title),
        notes: nonEmpty(event.notes), url: event.url?.absoluteString, status: eventStatus(event),
        modified: instantString(event.lastModifiedDate))
}

// MARK: reminders

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

func dueString(_ reminder: EKReminder) -> String? {
    guard let c = reminder.dueDateComponents, let year = c.year, let month = c.month, let day = c.day else { return nil }
    var time: (hour: Int, minute: Int)? = nil
    if let hour = c.hour, let minute = c.minute { time = (hour, minute) }
    return formatDue(Due(day: Day(year: year, month: month, day: day), time: time))
}

func reminderFacts(_ reminder: EKReminder) -> ReminderFacts {
    ReminderFacts(id: reminder.calendarItemIdentifier, list: reminder.calendar.title,
                  title: reminder.title ?? "", notes: nonEmpty(reminder.notes), due: dueString(reminder),
                  completed: reminder.isCompleted, completedAt: instantString(reminder.completionDate),
                  modified: instantString(reminder.lastModifiedDate))
}

func resolveList(_ title: String) -> EKCalendar {
    let hits = store.calendars(for: .reminder).filter { $0.title == title }
    if hits.isEmpty { fail(.notFound, "No reminder list is titled \(title). Run `eventkit lists`.") }
    if hits.count > 1 { fail(.usage, "More than one reminder list is titled \(title), so the title is ambiguous. Rename one of them.") }
    return hits[0]
}

func findReminder(_ id: String) -> EKReminder {
    guard let found = store.calendarItem(withIdentifier: id) as? EKReminder else {
        fail(.notFound, "No reminder has the id \(id). Copy an id from `eventkit reminders`.")
    }
    return found
}

/// Refuse unless writing in this list is allowed. A title shared by two
/// lists cannot be told apart by --allow, so that is refused too.
func requireAllowed(_ list: EKCalendar) {
    let titles = store.calendars(for: .reminder).map { $0.title }
    if duplicateTitles(titles).contains(list.title) {
        fail(.refused, "More than one reminder list is titled \(list.title), so --allow cannot tell them apart.")
    }
    if let failure = allowFailure(allow: allow, listTitle: list.title) { die(failure) }
}

func dueComponents(_ due: Due) -> DateComponents {
    var c = DateComponents()
    c.calendar = Calendar(identifier: .gregorian)
    c.year = due.day.year; c.month = due.day.month; c.day = due.day.day
    if let time = due.time { c.hour = time.hour; c.minute = time.minute }
    return c
}

/// The date of a timed due value, in the local zone. Nil for no due or a
/// date-only one.
func dueDate(_ components: DateComponents?) -> Date? {
    guard var c = components, c.hour != nil else { return nil }
    c.calendar = timeZoneCalendar
    return timeZoneCalendar.date(from: c)
}

/// Change a reminder's due date, and nothing else about it: alarms are not
/// touched (alarmEffect). Setting the due date to nil clears it.
func setDue(_ reminder: EKReminder, _ text: String?) {
    guard let text, let due = parseDue(text) else {
        reminder.dueDateComponents = nil
        return
    }
    reminder.dueDateComponents = dueComponents(due)
}

func save(_ reminder: EKReminder) {
    do { try store.save(reminder, commit: true) } catch { fail(.failed, "Could not save: \(error.localizedDescription)") }
}

// MARK: run

switch invocation.command {
case .help:
    fail(.failed, "unreachable")

case .access:
    request(.event)
    request(.reminder)
    succeed(["calendar": statusName(EKEventStore.authorizationStatus(for: .event)),
             "reminders": statusName(EKEventStore.authorizationStatus(for: .reminder))])

case .calendars:
    require(.event)
    let items = store.calendars(for: .event).sorted { ($0.title, $0.calendarIdentifier) < ($1.title, $1.calendarIdentifier) }
    succeed(["calendars": items.map {
        calendarObject(id: $0.calendarIdentifier, title: $0.title, source: $0.source?.title ?? "", type: typeName($0))
    }])

case .events:
    require(.event)
    let start = parseInstant(invocation.one("from")!)!
    let end = parseInstant(invocation.one("to")!)!
    var chosen: [EKCalendar]? = nil
    if let titles = invocation.flags["calendar"] {
        let all = store.calendars(for: .event)
        chosen = []
        for title in titles {
            let hits = all.filter { $0.title == title }
            if hits.isEmpty { fail(.notFound, "No calendar is titled \(title). Run `eventkit calendars`.") }
            chosen! += hits
        }
    }
    // events(matching:) expands repeating events into their occurrences.
    let found = store.events(matching: store.predicateForEvents(withStart: start, end: end, calendars: chosen))
    succeed(["events": sortedEvents(found.map(eventFacts)).map(eventObject)])

case .lists:
    require(.reminder)
    let items = store.calendars(for: .reminder).sorted { ($0.title, $0.calendarIdentifier) < ($1.title, $1.calendarIdentifier) }
    succeed(["lists": items.map {
        listObject(id: $0.calendarIdentifier, title: $0.title, source: $0.source?.title ?? "", markers: markers($0))
    }])

case .reminders:
    require(.reminder)
    var lists: [EKCalendar] = []
    for title in invocation.flags["list"]! {
        let list = resolveList(title)
        if !lists.contains(where: { $0.calendarIdentifier == list.calendarIdentifier }) { lists.append(list) }
    }
    let now = Date()
    let open = fetch(store.predicateForIncompleteReminders(withDueDateStarting: nil, ending: nil, calendars: lists))
    let done = fetch(store.predicateForCompletedReminders(withCompletionDateStarting: completedWindowStart(now: now),
                                                          ending: now, calendars: lists))
    var seen = Set<String>()
    let all = (open + done).filter { seen.insert($0.calendarItemIdentifier).inserted }
    succeed(["reminders": sortedReminders(all.map(reminderFacts)).map(reminderObject)])

case .add:
    require(.reminder)
    let destination = resolveList(invocation.one("list")!)
    requireAllowed(destination)
    let new = EKReminder(eventStore: store)
    new.title = invocation.one("title")!
    new.calendar = destination
    new.notes = invocation.one("notes")
    if let due = invocation.one("due") {
        setDue(new, due)
        // A new timed reminder gets one alarm that follows its due date.
        if alarmEffect(command: .add, due: parseDue(due)) == .relativeAtDue { new.addAlarm(EKAlarm(relativeOffset: 0)) }
    }
    save(new)
    succeed(["after": reminderObject(reminderFacts(new))])

case .set:
    require(.reminder)
    let found = findReminder(invocation.target!)
    requireAllowed(found.calendar)
    var destination: EKCalendar? = nil
    if let title = invocation.one("list") {
        destination = resolveList(title)
        requireAllowed(destination!)
    }
    let before = reminderObject(reminderFacts(found))
    if let title = invocation.one("title") { found.title = title }
    if invocation.has("clear-notes") { found.notes = nil } else if let notes = invocation.one("notes") { found.notes = notes }
    if invocation.has("clear-due") { setDue(found, nil) } else if let due = invocation.one("due") { setDue(found, due) }
    if let destination { found.calendar = destination }
    save(found)
    succeed(["before": before, "after": reminderObject(reminderFacts(found))])

case .done, .reopen:
    require(.reminder)
    let found = findReminder(invocation.target!)
    requireAllowed(found.calendar)
    let before = reminderObject(reminderFacts(found))
    found.isCompleted = invocation.command == .done
    save(found)
    succeed(["before": before, "after": reminderObject(reminderFacts(found))])
}
