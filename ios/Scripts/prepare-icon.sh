#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
/usr/bin/sips -z 1024 1024 "Assets/AppIcon-source.png" \
  --out "Assets.xcassets/AppIcon.appiconset/AppIcon.png" >/dev/null
