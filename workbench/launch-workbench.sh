#!/usr/bin/env bash
set -euo pipefail

WORKBENCH_DIR="$(cd "$(dirname "$0")" && pwd)"

if command -v adb >/dev/null 2>&1; then
  DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if [ -n "$DEVICES" ]; then
    echo "Forwarding USB telemetry bridge tcp:8765 -> device..."
    adb forward tcp:8765 tcp:8765 || true
    adb shell am start -n com.telematicslab.demo/.MainActivity >/dev/null 2>&1 || true
  else
    echo "No USB Android device detected — opening Workbench in Offline Simulator mode."
  fi
fi

open "$WORKBENCH_DIR/index.html"
