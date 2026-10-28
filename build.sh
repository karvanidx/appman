#!/data/data/com.termux/files/usr/bin/bash
# AppMan build script — Termux, no root, no Gradle.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
AJ="${ANDROID_JAR:-$HOME/android/sdk/platforms/android-36/android.jar}"
MIN_SDK=24
TARGET_SDK=34
VERSION_CODE=4
VERSION_NAME="1.3"

if [ ! -f "$AJ" ]; then
  echo "ERROR: android.jar not found at $AJ" >&2
  echo "Set ANDROID_JAR=/path/to/android.jar" >&2
  exit 1
fi
for t in aapt2 javac d8 apksigner keytool python3; do
  command -v "$t" >/dev/null || { echo "ERROR: missing $t" >&2; exit 1; }
done

BUILD="$ROOT/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/gen" "$ROOT/dist"

echo "[1/6] aapt2 compile"
aapt2 compile --dir "$ROOT/app/res" -o "$BUILD/res.zip"

echo "[2/6] aapt2 link"
aapt2 link -o "$BUILD/base.apk" \
  --manifest "$ROOT/app/AndroidManifest.xml" \
  -I "$AJ" \
  --java "$BUILD/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  "$BUILD/res.zip"

echo "[3/6] javac"
LIB="$ROOT/app/libs"
SHIZUKU_CP="$LIB/api.jar:$LIB/provider.jar:$LIB/aidl.jar:$LIB/shared.jar"
find "$ROOT/app/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac -source 8 -target 8 -bootclasspath "$AJ" -classpath "$SHIZUKU_CP" -Xlint:-options \
  -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "[4/6] d8"
d8 --lib "$AJ" --min-api "$MIN_SDK" --output "$BUILD/dex" \
  "$LIB/api.jar" "$LIB/provider.jar" "$LIB/aidl.jar" "$LIB/shared.jar" \
  $(find "$BUILD/classes" -name '*.class')

echo "[5/6] package + align"
python3 "$ROOT/tools/mkapk.py" \
  --base "$BUILD/base.apk" --dex "$BUILD/dex/classes.dex" --out "$BUILD/unsigned.apk"

echo "[6/6] sign"
KS="$ROOT/appman.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass appman -keypass appman \
    -alias appman -dname "CN=AppMan,O=AppMan,C=ID" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
OUT="$ROOT/dist/appman-${VERSION_NAME}.apk"
apksigner sign --ks "$KS" --ks-pass pass:appman --key-pass pass:appman \
  --ks-key-alias appman --out "$OUT" "$BUILD/unsigned.apk"
apksigner verify --verbose "$OUT" | head -6
aapt2 dump badging "$OUT" | head -4

echo
echo "APK: $OUT"
ls -lh "$OUT" | awk '{print "Size:", $5}'
sha256sum "$OUT"
