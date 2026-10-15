// Self-test for Core.swift. Touches no EventKit. Run with tools/rem/test.sh.
import Foundation

var failures = 0
func check(_ ok: Bool, _ what: String, line: Int = #line) {
    if !ok { failures += 1; print("FAIL (line \(line)): \(what)") }
}
func refused(_ args: [String]) -> Bool { if case .refused = parse(args) { return true }; return false }
func runs(_ args: [String]) -> Invocation? { if case .run(let i) = parse(args) { return i }; return nil }

// mode gating
check(refused([]), "no args")
check(writeResultLine(verb: "Added", id: "AB12CD34") == "Added AB12CD34", "a write echoes its id")
check(writeResultLine(verb: "Updated", id: "a\u{1b}b") == "Updated a\\u{1b}b" || !writeResultLine(verb: "Updated", id: "a\u{1b}b").contains("\u{1b}"), "an id is escaped")
check(refused(["lists"]), "no mode fails closed")
check(refused(["--mode", "bogus", "lists"]), "bad mode")
check(refused(["--mode"]), "mode without value")
check(parse(["help"]) == .usage, "bare help")
for c in [["lists"], ["ls", "--today"], ["show", "AB"], ["help"]] {
    check(runs(["--mode", "read"] + c) != nil, "read permits \(c)")
}
for c in [["add", "t", "--list", "L"], ["set", "AB", "--due", "none"], ["done", "AB"], ["help"]] {
    check(runs(["--mode", "write"] + c) != nil, "write permits \(c)")
    check(runs(["--mode", "all"] + c) != nil, "all permits \(c)")
}
for c in [["lists"], ["ls"], ["show", "AB"]] {
    check(refused(["--mode", "write"] + c), "write refuses \(c)")
    check(runs(["--mode", "all"] + c) != nil, "all permits \(c)")
}
for c in [["add", "t", "--list", "L"], ["set", "AB"], ["done", "AB"]] {
    check(refused(["--mode", "read"] + c), "read refuses \(c)")
}
// the model cannot override: its args come after the configured prefix
check(refused(["--mode", "read", "--mode", "all", "lists"]), "second --mode up front")
check(refused(["--mode", "read", "lists", "--mode", "all"]), "--mode after command")
check(refused(["--mode", "write", "--allow-shared", "add", "t", "--list", "L"]), "injected --allow-shared")
check(refused(["--mode", "write", "add", "t", "--list", "L", "--allow-shared", "x"]), "--allow-shared late")
check(refused(["--mode", "read", "ls", "--bogus", "x"]), "unknown flag")
check(refused(["--mode", "write", "add", "t"]), "add needs --list")
check(refused(["--mode", "write", "add", "a", "b", "--list", "L"]), "extra positional")
check(runs(["--mode", "write", "add", "t", "--list", "L", "--notes", "--mode"])?.flags["notes"] == "--mode", "flag value is data")
check(runs(["--mode", "write", "add", "t", "--list", "L"])?.mode == .write, "mode kept")

// sharing
let priv = ListTraits(isSubscribed: false, typeName: "calDAV", allowsContentModifications: true, isDelegateSource: false)
var sub = priv; sub.isSubscribed = true
var del = priv; del.isDelegateSource = true
check(sharingMarkers(priv).isEmpty, "private list has no marker")
check(sharingMarkers(sub) == ["subscribed"], "subscribed")
check(sharingMarkers(del) == ["delegate"], "delegate")
check(sharedRefusal(mode: .write, listName: "L", traits: priv) == nil, "write to plain list")
check(sharedRefusal(mode: .write, listName: "L", traits: sub) != nil, "write refuses subscribed")
check(sharedRefusal(mode: .write, listName: "L", traits: del) != nil, "write refuses delegate")
check(sharedRefusal(mode: .writeShared, listName: "L", traits: sub) == nil, "write-shared allows")
check(sharedRefusal(mode: .all, listName: "L", traits: sub) == nil, "all allows")

// escaping examples
check(escape("a\tb\nc") == "a\\tb\\nc", "tab/newline")
check(escape("a\\n") == "a\\\\n", "backslash doubled")
check(escape("x\u{202E}y") == "x\\u{202E}y", "bidi override")
check(escape("x\u{200B}y\u{FEFF}") == "x\\u{200B}y\\u{FEFF}", "zero width, BOM")
check(escape("a\u{2028}b\u{A0}c\u{7F}") == "a\\u{2028}b\\u{A0}c\\u{7F}", "separators, nbsp, DEL")
check(escape("héllo 日本 😀") == "héllo 日本 😀", "ordinary text untouched")
check(escapeBlock("a\r\nb\u{2028}c") == ["> a", "> b", "> c"], "block split")
check(escapeBlock("x\nid:     forged") == ["> x", "> id:     forged"], "block lines prefixed")

// property: escaped text is a single line of printable scalars, and an
// output line built from hostile fields keeps its exact field count.
var rng = SystemRandomNumberGenerator()
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
    let t = randomText(), l = randomText()
    let e = escape(t)
    check(!e.unicodeScalars.contains(where: { $0 != " " && needsEscape($0) }), "no raw specials in \(e.debugDescription)")
    check(!e.contains("\n") && !e.contains("\r") && !e.contains("\t"), "single line")
    let row = reminderLine(id: "ABCD1234", list: l, due: "-", title: t, markers: [])
    check(row.split(separator: "\t", omittingEmptySubsequences: false).count == 5, "five fields: \(row.debugDescription)")
    check(!row.contains("\n"), "row is one line")
    for line in showLines(id: "X", title: t, list: l, markers: [], due: "-", done: false, notes: randomText() + "\n" + t) {
        check(!line.contains("\n") && !line.contains("\r"), "show line is one line")
    }
}

if failures == 0 { print("ok") } else { print("\(failures) failure(s)"); exit(1) }
