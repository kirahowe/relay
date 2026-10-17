// Self-test for Escape.swift, compiled into both rem's and cal's test
// binaries so the same cases run against each. Touches nothing outside
// the standard library.

/// Unicode's Default_Ignorable_Code_Point, written out again by value so the
/// test does not share a table with Escape.swift. Each entry is a range and
/// a name.
let hiddenRanges: [(ClosedRange<UInt32>, String)] = [
    (0x00AD...0x00AD, "soft hyphen"), (0x034F...0x034F, "combining grapheme joiner"),
    (0x061C...0x061C, "arabic letter mark"), (0x115F...0x1160, "hangul choseong and jungseong fillers"),
    (0x17B4...0x17B5, "khmer inherent vowels"), (0x180B...0x180F, "mongolian selectors"),
    (0x200B...0x200F, "zero width and direction marks"), (0x202A...0x202E, "bidi embeddings and overrides"),
    (0x2060...0x206F, "word joiner, invisible operators, deprecated format"), (0x3164...0x3164, "hangul filler"),
    (0xFE00...0xFE0F, "variation selectors"), (0xFEFF...0xFEFF, "byte order mark"),
    (0xFFA0...0xFFA0, "halfwidth hangul filler"), (0xFFF0...0xFFF8, "reserved specials"),
    (0x1BCA0...0x1BCA3, "shorthand format controls"), (0x1D173...0x1D17A, "musical symbol format controls"),
    (0xE0000...0xE0FFF, "tags and variation selectors supplement"),
]
/// Visible-as-nothing scalars that are not default-ignorable.
let blankLookalikes: [UInt32] = [0x2800, 0x3000, 0x00A0, 0x1680, 0x2028, 0x2029, 0x0085]

/// True for any scalar that must never reach the output raw. U+0020 is the
/// one space allowed, so it is not listed.
func hiddenInOutput(_ s: Unicode.Scalar) -> Bool {
    hiddenRanges.contains { $0.0.contains(s.value) } || blankLookalikes.contains(s.value)
}

/// Undo escape(), so a test can check nothing is lost.
func unescapeForTest(_ text: String) -> String? {
    var out = String.UnicodeScalarView()
    var it = text.unicodeScalars.makeIterator()
    while let c = it.next() {
        guard c == "\\" else { out.append(c); continue }
        guard let n = it.next() else { return nil }
        switch n {
        case "\\": out.append("\\")
        case "t": out.append("\t")
        case "n": out.append("\n")
        case "r": out.append("\r")
        case "u":
            guard it.next() == "{" else { return nil }
            var hex = ""
            while let h = it.next(), h != "}" { hex.unicodeScalars.append(h) }
            guard let v = UInt32(hex, radix: 16), let u = Unicode.Scalar(v) else { return nil }
            out.append(u)
        default: return nil
        }
    }
    return String(out)
}

/// A random string heavy in the ranges that matter and in backslashes.
func hostileText(_ rng: inout SystemRandomNumberGenerator) -> String {
    var s = String.UnicodeScalarView()
    for _ in 0..<Int.random(in: 0...24, using: &rng) {
        let range: ClosedRange<UInt32>
        switch Int.random(in: 0..<4, using: &rng) {
        case 0: range = hiddenRanges.randomElement(using: &rng)!.0
        case 1: range = [0x5C...0x5C, 0x75...0x75, 0x7B...0x7B, 0x7D...0x7D, 0x20...0x7E].randomElement(using: &rng)!
        case 2: range = [0...0x2F, 0x7F...0x9F, 0xA0...0x2FF, 0x2000...0x206F, 0x2800...0x2800, 0x3000...0x3000, 0xD800...0xDFFF,
                         0xE000...0xE0FF, 0x1F600...0x1F64F, 0xFFF0...0xFFFF, 0xF0000...0x10FFFF].randomElement(using: &rng)!
        default: range = 0x300...0x36F
        }
        if let u = Unicode.Scalar(UInt32.random(in: range, using: &rng)) { s.append(u) }
    }
    return String(s)
}

/// Runs the shared cases and returns how many failed.
func runEscapeTests() -> Int {
    var failures = 0
    func check(_ ok: Bool, _ what: String, line: Int = #line) {
        if !ok { failures += 1; print("FAIL (escape, line \(line)): \(what)") }
    }
    func hex(_ v: UInt32) -> String { "\\u{" + String(v, radix: 16, uppercase: true) + "}" }

    // every scalar of every range, alone and between ordinary letters
    for (range, name) in hiddenRanges {
        for v in range {
            guard let u = Unicode.Scalar(v) else { continue }
            check(escape("a\(u)b") == "a" + hex(v) + "b", "\(name): U+\(String(v, radix: 16, uppercase: true)) is shown")
        }
    }
    for v in blankLookalikes {
        check(escape("a\(Unicode.Scalar(v)!)b") == "a" + hex(v) + "b", "blank lookalike U+\(String(v, radix: 16, uppercase: true)) is shown")
    }
    // the named cases from the review
    for v: UInt32 in [0x34F, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180B, 0x180C, 0x180D, 0x180E, 0x180F, 0x3164, 0xFFA0,
                      0xFE00, 0xFE0F, 0xE0100, 0xE01EF, 0xE0000, 0xE0FFF, 0x2060, 0x206F, 0x200B, 0x200F, 0xFEFF, 0x2800, 0x3000] {
        check(escape(String(Unicode.Scalar(v)!)) == hex(v), "named scalar U+\(String(v, radix: 16, uppercase: true))")
    }
    // the OS's own property agrees with the table, in both directions
    for v in UInt32(0)...0x10FFFF {
        guard let u = Unicode.Scalar(v) else { continue }
        if u.properties.isDefaultIgnorableCodePoint {
            check(hiddenInOutput(u) && needsEscape(u), "property says U+\(String(v, radix: 16, uppercase: true)) is ignorable, so it must be escaped")
        }
    }
    // emoji keep no invisible selector
    check(escape("\u{2764}\u{FE0F}") == "\u{2764}\\u{FE0F}", "emoji variation selector shown")
    check(escape("\u{1F468}\u{200D}\u{1F469}") == "\u{1F468}\\u{200D}\u{1F469}", "zero width joiner shown")
    check(escape("a\\u{FE0F}") == "a\\\\u{FE0F}", "typed text that looks like an escape is doubled")
    // text that stays as it is
    check(escape("héllo 日本 😀 e\u{301}") == "héllo 日本 😀 e\u{301}", "ordinary text and combining accents untouched")
    check(escape("a b") == "a b", "plain space untouched")
    // a payload smuggled in selectors cannot ride along
    let payload = "hi" + String(String.UnicodeScalarView("secret".utf8.map { Unicode.Scalar(0xE0100 + UInt32($0)) ?? " " }))
    check(!escape(payload).unicodeScalars.contains(where: hiddenInOutput), "selector payload is visible")

    // property: no ignorable or blank scalar survives, and the escape reverses
    var rng = SystemRandomNumberGenerator()
    for _ in 0..<5000 {
        let t = hostileText(&rng)
        let e = escape(t)
        check(!e.unicodeScalars.contains(where: hiddenInOutput), "nothing hidden survives in \(e.debugDescription)")
        check(!e.unicodeScalars.contains(where: { $0.properties.isDefaultIgnorableCodePoint }), "no ignorable scalar by the OS property")
        check(!e.contains("\n") && !e.contains("\r") && !e.contains("\t"), "single line")
        check(unescapeForTest(e).map { Array($0.unicodeScalars) == Array(t.unicodeScalars) } ?? false, "round trip of \(t.debugDescription)")
        for line in escapeBlock(t) {
            check(!line.unicodeScalars.contains(where: hiddenInOutput), "block line has nothing hidden")
        }
    }
    return failures
}
