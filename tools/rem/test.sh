#!/bin/sh
# Compile and run rem's pure-code self-test. No EventKit, no Reminders access.
set -e
cd "$(dirname "$0")"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
swiftc -O Core.swift tests/main.swift -o "$out/rem-tests"
"$out/rem-tests"
