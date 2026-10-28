#!/bin/sh
# Compile and run eventkit's pure-code self-test. No EventKit, no Calendar or
# Reminders access.
set -e
cd "$(dirname "$0")"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
swiftc -O Core.swift tests/main.swift -o "$out/eventkit-tests"
"$out/eventkit-tests"
