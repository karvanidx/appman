#!/data/data/com.termux/files/usr/bin/bash
# Fetch android.jar (platform 36) on a fresh Termux. No sdkmanager needed.
set -euo pipefail
PLATFORM="${PLATFORM:-platform-36_r02.zip}"
DEST="${DEST:-$HOME/android/sdk/platforms/android-36}"
URL="https://dl.google.com/android/repository/$PLATFORM"
TMP="$HOME/dl"
mkdir -p "$TMP" "$DEST"
if [ ! -f "$TMP/$PLATFORM" ]; then
  echo ">> downloading $URL"
  curl -L --retry 5 --retry-delay 2 -C - -o "$TMP/$PLATFORM" "$URL"
fi
echo ">> extracting android.jar"
unzip -o -q "$TMP/$PLATFORM" '*/android.jar' -d "$TMP/extract"
cp "$TMP/extract"/*/android.jar "$DEST/android.jar"
rm -rf "$TMP/extract"
echo "android.jar -> $DEST/android.jar"
