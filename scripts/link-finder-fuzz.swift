// Checks relay's rule for a live link against Apple's link finder.
//
// relay shows a live link between angle brackets, <https://x.example/a>,
// so that whatever reads the text follows exactly that address. Link
// finders have rules of their own, and they disagree. Apple's
// NSDataDetector, which Mail and the phone use, balances brackets across
// what follows an address, and drops the end of one from a full stop,
// comma, semicolon or exclamation mark that no letter or digit follows.
// Ghostty's stops at an apostrophe. relay.text/link? therefore admits
// only letters, digits and - . _ ~ / ? # & = % : + @, and an address must
// end in a letter or a digit, or a slash right after one.
//
// This builds random addresses, puts each between angle brackets with
// text before and after, and asks NSDataDetector what it would follow.
// Every address of relay's shape must be followed exactly. It prints how
// many of the others were not, to show the rule is doing something.
//
// Run it on a Mac when relay.text/link? or link-closers changes:
//
//     swiftc -O scripts/link-finder-fuzz.swift -o /tmp/link-finder-fuzz
//     /tmp/link-finder-fuzz 400000
//
// It exits 1 when an address of relay's shape was followed differently.

import Foundation

struct RNG {
    var s: UInt64
    mutating func next() -> UInt64 { s ^= s << 13; s ^= s >> 7; s ^= s << 17; return s }
    mutating func pick<T>(_ xs: [T]) -> T { xs[Int(next() % UInt64(xs.count))] }
    mutating func below(_ n: Int) -> Int { Int(next() % UInt64(n)) }
}

let detector = try! NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)

/// The http and https addresses the detector would follow in `s`, as written.
func followed(_ s: String) -> [String] {
    detector.matches(in: s, range: NSRange(s.startIndex..., in: s)).compactMap { m in
        guard let r = Range(m.range, in: s) else { return nil }
        let t = String(s[r])
        return t.hasPrefix("http") ? t : nil
    }
}

func isAlnum(_ c: Character) -> Bool { c.isASCII && (c.isLetter || c.isNumber) }

/// relay.text/link?, for the addresses this builds: after the scheme only
/// letters, digits and - . _ ~ / ? # & = % : + @, with no second ://,
/// ending in a letter or a digit, or a slash right after one.
func relayShape(_ link: String) -> Bool {
    guard let r = link.range(of: "://") else { return false }
    let rest = Array(link[r.upperBound...])
    guard rest.allSatisfy({ isAlnum($0) || "-._~/?#&=%:+@".contains($0) }) else { return false }
    guard !String(rest).contains("://") else { return false }
    let n = rest.count
    return isAlnum(rest[n - 1]) || (rest[n - 1] == "/" && n >= 2 && isAlnum(rest[n - 2]))
}

let cases = CommandLine.arguments.count > 1 ? Int(CommandLine.arguments[1]) ?? 100_000 : 100_000
// What RFC 3986 allows in a path, a query or a fragment, with the marks
// relay admits listed twice so that many of the addresses are of its shape.
let marks = Array("-._~:/?#[]@!$&'()*+,;=%-._~/?#&=%:+@").map(String.init)
let alnum = Array("abcxyzABC0189").map(String.init)
// What a role might write straight before and after a link.
let before = ["", "See ", "**", "(", "[t](", "x=", "\"", "'", "_", "Row 1 ", "evil[.]example/SECRET/", "a:", "~", "((", "[", "<"]
let after = ["", " now", "**", ").", ")", "]", ":", "?", "!", ",", ";", "'", "\"", "_", "~", "SECRET", "/SECRET",
             ".SECRET", "?d=1", "#x", "@evil.example", ")**.", ": none", "…", ">", "))", "]]", ">>", ".", "..."]

var rng = RNG(s: 0x9E37_79B9_7F4A_7C15)
var shaped = 0, shapedWrong = 0, others = 0, othersWrong = 0
for _ in 0..<cases {
    var link = rng.pick(["https://", "http://"]) + rng.pick(["safe.example", "a.b-c.example.org", "x.test"])
        + rng.pick(["", "", ":8443"])
    let n = rng.below(24)
    if n > 0 { link += rng.pick(["/", "/", "?", "#"]) }
    for _ in 0..<n { link += rng.below(3) == 0 ? rng.pick(marks) : rng.pick(alnum) }
    let text = rng.pick(before) + "<" + link + ">" + rng.pick(after)
    let got = followed(text)
    let exact = got.count == 1 && got[0] == link
    if relayShape(link) {
        shaped += 1
        if !exact {
            shapedWrong += 1
            if shapedWrong <= 20 { print("WRONG  \(text)\n       followed \(got)") }
        }
    } else {
        others += 1
        if !exact { othersWrong += 1 }
    }
}
print("\(cases) addresses between angle brackets")
print("of relay's shape: \(shaped), followed differently: \(shapedWrong)")
print("not of relay's shape: \(others), followed differently: \(othersWrong)")
exit(shapedWrong == 0 ? 0 : 1)
