#!/bin/sh
# Compile and run cal's pure-code self-test. No EventKit, no Calendar access.
set -e
cd "$(dirname "$0")"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
swiftc -O Core.swift tests/main.swift -o "$out/cal-tests"
"$out/cal-tests"
