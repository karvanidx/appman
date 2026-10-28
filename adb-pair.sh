#!/data/data/com.termux/files/usr/bin/bash
# Pair with Android Wireless debugging, connect, install and launch AppMan.
# Usage: ./adb-pair.sh <pair_ip:pair_port> <pairing_code> <connect_ip:connect_port>
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
APK="${APK:-$ROOT/dist/appman-1.3.1.apk}"

if [ $# -lt 3 ]; then
  echo "Usage: $0 <pair_ip:pair_port> <pairing_code> <connect_ip:connect_port>" >&2
  echo "Example: $0 192.168.1.5:41234 123456 192.168.1.5:38999" >&2
  exit 1
fi
PAIR_ADDR="$1"
CODE="$2"
CONNECT_ADDR="$3"

echo ">> adb pair $PAIR_ADDR"
adb pair "$PAIR_ADDR" "$CODE"
echo ">> adb connect $CONNECT_ADDR"
adb connect "$CONNECT_ADDR"
adb devices

[ -f "$APK" ] || { echo "APK not found: $APK (run ./build.sh)" >&2; exit 1; }
echo ">> adb install -r $APK"
adb install -r "$APK"
echo ">> launching AppMan"
adb shell monkey -p io.github.karvanidx.appman -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
echo "Done."
