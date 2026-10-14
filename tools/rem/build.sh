#!/bin/sh
# Build rem next to its source. Info.plist is linked into the binary so
# macOS can show why it wants Reminders access.
set -e
cd "$(dirname "$0")"
swiftc -O main.swift -o rem \
  -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker Info.plist
