#!/bin/sh
# Build cal next to its source. Info.plist is linked into the binary so
# macOS can show why it wants Calendar access.
set -e
cd "$(dirname "$0")"
swiftc -O main.swift Core.swift -o cal \
  -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker Info.plist
