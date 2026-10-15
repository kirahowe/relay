// The pure part of rem: argument parsing, mode restriction, sharing
// classification and output formatting. Nothing here imports EventKit or
// touches the outside world, so tests/main.swift can compile it alone.

import Foundation

// MARK: modes

/// What the operator's configured command allows. The model never chooses it.
enum Mode: String {
    case read          // help, lists, ls, show
    case write         // help, add, set, done; refuses shared destinations
    case writeShared = "write-shared"  // as write, shared lists allowed
    case all           // everything, shared lists allowed

    var allowsShared: Bool { self == .writeShared || self == .all }

    func permits(_ command: Command) -> Bool {
        switch command {
        case .help: return true
        case .lists, .ls, .show: return self == .read || self == .all
        case .add, .set, .done: return self != .read
        }
    }
}

enum Command: String {
    case help, lists, ls, show, add, set, done
}

struct CommandSpec {
    var positional: Bool           // takes exactly one positional
    var valueFlags: [String]
    var boolFlags: [String]
    var required: [String]
}

let specs: [Command: CommandSpec] = [
    .help: CommandSpec(positional: false, valueFlags: [], boolFlags: [], required: []),
    .lists: CommandSpec(positional: false, valueFlags: [], boolFlags: [], required: []),
    .ls: CommandSpec(positional: false, valueFlags: ["list"], boolFlags: ["today"], required: []),
    .show: CommandSpec(positional: true, valueFlags: [], boolFlags: [], required: []),
    .add: CommandSpec(positional: true, valueFlags: ["list", "due", "notes"], boolFlags: [], required: ["list"]),
    .set: CommandSpec(positional: true, valueFlags: ["title", "due", "list", "notes"], boolFlags: [], required: []),
    .done: CommandSpec(positional: true, valueFlags: [], boolFlags: [], required: []),
]

struct Invocation: Equatable {
    var mode: Mode
    var command: Command
    var target: String?
    var flags: [String: String]
}

enum ParseResult: Equatable {
    case run(Invocation)
    case usage                 // bare `rem help` with no mode
    case refused(String)       // fail closed, with the reason
}

/// Parse the full argument list (without argv[0]).
///
/// The configured command supplies `--mode <m>` as the first two arguments and
/// relay appends the model's arguments after it. So the mode is read only from
/// the front, exactly once; any later `--mode` is rejected, never honoured.
/// Flags are strict per command, so an unknown flag cannot slip through.
func parse(_ args: [String]) -> ParseResult {
    if args == ["help"] { return .usage }
    guard args.first == "--mode" else {
        return .refused("Needs --mode read|write|write-shared|all as its first argument. The operator sets it in the tool's command.")
    }
    guard args.count >= 2, let mode = Mode(rawValue: args[1]) else {
        return .refused("Unknown mode. Use --mode read, write, write-shared or all.")
    }
    var rest = Array(args.dropFirst(2))
    guard !rest.isEmpty else { return .refused("No command given. Run `rem help`.") }
    let word = rest.removeFirst()
    guard let command = Command(rawValue: word) else {
        return .refused("Unknown command \(escape(word)). Run `rem help`.")
    }
    guard mode.permits(command) else {
        return .refused("\(command.rawValue) is not available in --mode \(mode.rawValue).")
    }
    let spec = specs[command]!
    var flags: [String: String] = [:]
    var target: String? = nil
    while !rest.isEmpty {
        let argument = rest.removeFirst()
        if argument == "--mode" || argument == "--allow-shared" {
            return .refused("\(argument) is set by the operator and cannot be given here.")
        }
        if argument.hasPrefix("--") {
            let name = String(argument.dropFirst(2))
            if spec.boolFlags.contains(name) {
                flags[name] = "yes"
            } else if spec.valueFlags.contains(name) {
                guard !rest.isEmpty else { return .refused("\(argument) needs a value.") }
                if flags[name] != nil { return .refused("\(argument) given twice.") }
                flags[name] = rest.removeFirst()
            } else {
                return .refused("Unknown option \(escape(argument)) for \(command.rawValue).")
            }
        } else if spec.positional, target == nil {
            target = argument
        } else {
            return .refused("Unexpected argument \(escape(argument)). Run `rem help`.")
        }
    }
    if spec.positional, target == nil { return .refused("\(command.rawValue) needs an argument. Run `rem help`.") }
    for name in spec.required where flags[name] == nil {
        return .refused("\(command.rawValue) needs --\(name).")
    }
    return .run(Invocation(mode: mode, command: command, target: target, flags: flags))
}

// MARK: sharing

/// The EventKit facts about a list, as plain values.
struct ListTraits: Equatable {
    var isSubscribed: Bool          // EKCalendar.isSubscribed
    var typeName: String            // EKCalendar.type: local, calDAV, exchange, subscription, birthday
    var allowsContentModifications: Bool
    var isDelegateSource: Bool      // EKSource.isDelegate: someone else's account opened as a delegate
}

/// Markers for a list's sharing signals, in a fixed order. Empty means
/// "no signal", which is not proof that nobody else can see the list: an
/// iCloud list shared with other people looks the same as a private one.
func sharingMarkers(_ traits: ListTraits) -> [String] {
    var markers: [String] = []
    if traits.isSubscribed || traits.typeName == "subscription" { markers.append("subscribed") }
    if traits.isDelegateSource { markers.append("delegate") }
    if !traits.allowsContentModifications { markers.append("read-only") }
    return markers
}

func markerColumn(_ markers: [String]) -> String {
    markers.isEmpty ? "-" : markers.joined(separator: ",")
}

/// A reason to refuse writing to this list under `mode`, or nil.
func sharedRefusal(mode: Mode, listName: String, traits: ListTraits) -> String? {
    guard !mode.allowsShared else { return nil }
    let markers = sharingMarkers(traits)
    guard !markers.isEmpty else { return nil }
    return "List \(escape(listName)) is \(markers.joined(separator: ", ")). This tool's mode does not write to such lists."
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

// MARK: output lines

func listLine(count: Int, title: String, markers: [String]) -> String {
    "\(count)\t\(escape(title))\t\(markerColumn(markers))"
}

/// What a write prints: the verb and the reminder's id, nothing else. Its
/// title and list may hold text other people wrote, which a write must not
/// hand back to the session that made it.
func writeResultLine(verb: String, id: String) -> String {
    "\(verb) \(escape(id))"
}

func reminderLine(id: String, list: String, due: String, title: String, markers: [String]) -> String {
    "\(id)\t\(escape(list))\t\(due)\t\(escape(title))\t\(markerColumn(markers))"
}

func showLines(id: String, title: String, list: String, markers: [String], due: String, done: Bool, notes: String?) -> [String] {
    var lines = [
        "id:     \(escape(id))",
        "title:  \(escape(title))",
        "list:   \(escape(list))",
        "shared: \(markerColumn(markers))",
        "due:    \(due)",
        "done:   \(done ? "yes" : "no")",
    ]
    if let notes, !notes.isEmpty { lines.append("notes:"); lines += escapeBlock(notes) }
    return lines
}
