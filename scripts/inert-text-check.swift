// Reads what scripts/inert-text-fuzz.clj wrote and asks Apple's link
// finder (NSDataDetector, which Mail and the phone use) whether any of it
// can be followed as a web address. Text relay shows from outside must be
// inert, so every hit is a defect in relay.text/defang or in what cuts
// its output.
//
//   swiftc -O scripts/inert-text-check.swift -o /tmp/inert-text-check
//   bb scripts/inert-text-fuzz.clj 1 200000 | /tmp/inert-text-check
//
// A hit is an http, https or ftp address. A mail address (mailto:) and a
// broken link (hxxps://, which nothing opens) are not hits. It prints up
// to 40 hits and the count, and exits 1 when there is any.

import Foundation

let detector = try! NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
var cases = 0, hits = 0
while let line = readLine(strippingNewline: true) {
    let parts = line.split(separator: "\u{01}", maxSplits: 1, omittingEmptySubsequences: false)
    guard parts.count == 2 else { continue }
    cases += 1
    // relay ready's lines come joined by U+0002: the finder reads each.
    for shown in parts[1].split(separator: "\u{02}", omittingEmptySubsequences: false).map(String.init) {
        for m in detector.matches(in: shown, range: NSRange(shown.startIndex..., in: shown)) {
            guard let url = m.url, let scheme = url.scheme?.lowercased(),
                  ["http", "https", "ftp"].contains(scheme) else { continue }
            hits += 1
            if hits <= 40 { print("FOLLOWED \(url.absoluteString)\n   written: \(parts[0])\n   shown:   \(shown)") }
        }
    }
}
print("\(cases) cases, \(hits) followed")
exit(hits == 0 ? 0 : 1)
