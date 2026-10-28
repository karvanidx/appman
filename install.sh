#!/data/data/com.termux/files/usr/bin/bash
# Copy the APK to shared Downloads and try to open the system installer.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
APK="${1:-$ROOT/dist/appman-1.3.apk}"
[ -f "$APK" ] || { echo "APK not found: $APK (run ./build.sh first)" >&2; exit 1; }

DEST="/sdcard/Download/AppMan"
mkdir -p "$DEST"
NAME="$(basename "$APK")"
cp -f "$APK" "$DEST/$NAME"
echo "Copied to $DEST/$NAME"

if command -v termux-open >/dev/null 2>&1; then
  termux-open "$DEST/$NAME" || true
else
  am start -a android.intent.action.VIEW \
    -d "file://$DEST/$NAME" \
    -t application/vnd.android.package-archive >/dev/null 2>&1 || true
fi

cat <<EOF

If the installer did not appear, open it manually:
  Files -> Download -> AppMan -> $NAME -> Install

For automatic installs over ADB (no root):
  1. Enable Developer options -> Wireless debugging
  2. ./adb-pair.sh <pair_ip:pair_port> <pairing_code> <connect_ip:connect_port>
EOF
