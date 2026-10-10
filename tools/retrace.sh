#!/usr/bin/env bash
# De-obfuscates a logged stack trace with the mapping of the build that produced it.
#   adb logcat -d -v brief | grep TinyTube | tools/retrace.sh
#   tools/retrace.sh trace.txt
# For an older build, check out its commit's mapping.txt first (versionName is
# `git describe`, so the installed build names its commit).
set -euo pipefail
cd "$(dirname "$0")/.."
exec .tools/jdk/bin/java -cp .tools/sdk/build-tools/35.0.0/lib/d8.jar \
  com.android.tools.r8.retrace.Retrace mapping.txt "$@"
