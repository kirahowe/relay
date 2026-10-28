#!/bin/sh
# Build eventkit next to its source. Info.plist is linked into the binary so
# macOS can show why it wants Calendar and Reminders access.
set -e
cd "$(dirname "$0")"
swiftc -O main.swift Core.swift -o eventkit \
  -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker Info.plist
