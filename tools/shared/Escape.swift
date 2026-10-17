// Output escaping shared by rem and cal. Both build.sh and test.sh scripts
// compile this one file, so the two tools cannot drift apart. It imports
// nothing, so the self-tests can compile it alone.

// MARK: escaping

/// Make untrusted text safe for one field of one line. Everything that can
/// hide, reorder or fake text becomes a visible `\u{XXXX}`: control, format
/// (bidi marks, zero-width, tags), separator, private-use, unassigned and
/// surrogate scalars, every space other than U+0020, every Unicode
/// default-ignorable code point (variation selectors, combining grapheme
/// joiner, Hangul fillers, Khmer inherent vowels, Mongolian selectors), and
/// the braille blank. Backslash doubles so an escape can never be forged.
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
        return isDefaultIgnorable(scalar) || scalar.value == 0x2800
    }
}

/// Unicode's Default_Ignorable_Code_Point (DerivedCoreProperties). Renderers
/// draw these as nothing, so text can hide in them. The property is asked
/// first; the explicit ranges are a backstop for a system whose Unicode
/// tables are older than the list, so the answer never shrinks.
func isDefaultIgnorable(_ scalar: Unicode.Scalar) -> Bool {
    scalar.properties.isDefaultIgnorableCodePoint
        || defaultIgnorableRanges.contains { $0.contains(scalar.value) }
}

/// Default_Ignorable_Code_Point as of Unicode 16.
let defaultIgnorableRanges: [ClosedRange<UInt32>] = [
    0x00AD...0x00AD, 0x034F...0x034F, 0x061C...0x061C, 0x115F...0x1160,
    0x17B4...0x17B5, 0x180B...0x180F, 0x200B...0x200F, 0x202A...0x202E,
    0x2060...0x206F, 0x3164...0x3164, 0xFE00...0xFE0F, 0xFEFF...0xFEFF,
    0xFFA0...0xFFA0, 0xFFF0...0xFFF8, 0x1BCA0...0x1BCA3, 0x1D173...0x1D17A,
    0xE0000...0xE0FFF,
]

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
