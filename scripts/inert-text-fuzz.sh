#!/bin/sh
# The standing check that text relay shows from outside is inert.
#
# inert-text-fuzz.clj writes random hostile strings as mail, a client and
# `relay ready` show them, and inert-text-check.swift asks Apple's link
# finder whether any of it can be followed. This runs the two together
# and fails when the finder follows anything. A hit prints what was
# written and what relay showed, which is the test case to add before
# fixing it.
#
#   scripts/inert-text-fuzz.sh [seed] [strings]
#
# The seed is the clock's unless one is given, and it is printed, so a
# run can be repeated. Each string is shown three ways, so 100,000
# strings are 300,000 cases. `bb verify` runs this as its fuzz step. It
# needs swiftc, as the eventkit step does.
set -eu
cd "$(dirname "$0")/.."
seed="${1:-$(date +%s)}"
strings="${2:-100000}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
swiftc -O scripts/inert-text-check.swift -o "$tmp/check"
echo "inert-text fuzz: seed $seed, $strings strings, each as three channels show it"
# Written to a file first, so a fuzz that stops early fails the run
# rather than handing the finder fewer cases.
bb scripts/inert-text-fuzz.clj "$seed" "$strings" > "$tmp/cases"
"$tmp/check" < "$tmp/cases"
