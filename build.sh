#!/usr/bin/env bash
# Self-contained build: downloads a JDK + Android build-tools into ./.tools on
# first run (nothing is installed system-wide), then compiles the app without
# gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
TOOLS=$ROOT/.tools
OUT=$TOOLS/build
SDK=${TINYTUBE_ANDROID_SDK_ROOT:-$TOOLS/sdk}
BT_VER=35.0.0
PLATFORM=android-35
MIN_SDK=34
TARGET_SDK=35

mkdir -p "$TOOLS"

# ---- JDK -------------------------------------------------------------------
if [ -z "${TINYTUBE_JAVA_HOME:-}" ] && [ ! -x "$TOOLS/jdk/bin/javac" ]; then
  echo ">> downloading JDK 17"
  curl -fL --progress-bar -o "$TOOLS/jdk.tar.gz" \
    'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse'
  rm -rf "$TOOLS/jdk" "$TOOLS/jdk-tmp"; mkdir -p "$TOOLS/jdk-tmp"
  tar -xzf "$TOOLS/jdk.tar.gz" -C "$TOOLS/jdk-tmp" --strip-components=1
  mv "$TOOLS/jdk-tmp" "$TOOLS/jdk"; rm -f "$TOOLS/jdk.tar.gz"
fi
export JAVA_HOME=${TINYTUBE_JAVA_HOME:-$TOOLS/jdk}
export PATH=$JAVA_HOME/bin:$PATH

# ---- Android SDK pieces ----------------------------------------------------
BT=$SDK/build-tools/$BT_VER
ANDROID_JAR=$SDK/platforms/$PLATFORM/android.jar
if [ ! -x "$BT/aapt2" ] || [ ! -f "$ANDROID_JAR" ]; then
  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo ">> downloading Android command line tools"
    curl -fL --progress-bar -o "$TOOLS/clt.zip" \
      'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip'
    rm -rf "$SDK/cmdline-tools"; mkdir -p "$SDK/cmdline-tools"
    (cd "$SDK/cmdline-tools" && unzip -q "$TOOLS/clt.zip" && mv cmdline-tools latest)
    rm -f "$TOOLS/clt.zip"
  fi
  echo ">> downloading build-tools $BT_VER and platform $PLATFORM"
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
    "build-tools;$BT_VER" "platforms;$PLATFORM" | grep -v '^\[' || true
fi

# ---- signing key (development key; releases restore a stable key from CI secrets) --
KS=$ROOT/keystore.jks
if [ "${TINYTUBE_UNSIGNED:-}" != 1 ] && [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias tinytube -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Tiny Tube' >/dev/null
fi

# ---- build -----------------------------------------------------------------
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"
VERSION_CODE=${TINYTUBE_VERSION_CODE:-$(git rev-list --count HEAD 2>/dev/null || echo 1)}
if ! [[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || [ "$VERSION_CODE" -gt 2100000000 ]; then
  echo 'Version code must be a positive Android integer' >&2; exit 1
fi
# Obtainium compares version names numerically. Git hashes are unordered and
# their digit runs can make a newer build look like a downgrade. Keep Android's
# two versions identical; git history still identifies the source by code.
VERSION_NAME=$VERSION_CODE

echo ">> aapt2"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unaligned.apk" --manifest AndroidManifest.xml -I "$ANDROID_JAR" \
  --java "$OUT/gen" --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --no-version-vectors "$OUT/res.zip"

# Resource names are collapsed and paths shortened (res/drawable/ic_cache.xml
# becomes res/aB.xml): nothing looks resources up by name, and it is ~2 KB.
"$BT/aapt2" optimize -o "$OUT/optimized.apk" --collapse-resource-names \
  --shorten-resource-paths "$OUT/unaligned.apk"
mv "$OUT/optimized.apk" "$OUT/unaligned.apk"

echo ">> javac"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -Xlint:-options -nowarn -encoding UTF-8 -proc:none \
  -bootclasspath "$ANDROID_JAR" -classpath "$BT/core-lambda-stubs.jar" -d "$OUT/classes" @"$OUT/sources.txt"

# R8 rather than d8: it shrinks unused code as well as dexing, which is worth
# ~22 KB here. It lives inside d8.jar in the build-tools. Names are deliberately
# kept (see proguard.cfg) so logged stack traces stay readable.
echo ">> r8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 \
  --release --min-api $MIN_SDK --lib "$ANDROID_JAR" \
  --pg-conf proguard.cfg --output "$OUT/dex" @"$OUT/classes.txt"
(cd "$OUT/dex" && jar -uf "$OUT/unaligned.apk" classes.dex)

echo ">> zipalign + sign"
"$BT/zipalign" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"
# minSdk 34 verifies with the v2/v3 signature alone, so the v1 JAR signature is
# dropped -- that removes META-INF/{MANIFEST.MF,*.SF,*.RSA}, about 5 KB.
if [ "${TINYTUBE_UNSIGNED:-}" = 1 ]; then
  cp "$OUT/aligned.apk" "$ROOT/tiny-tube.apk"
else
  "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --out "$ROOT/tiny-tube.apk" "$OUT/aligned.apk"
fi
ls -l "$ROOT/tiny-tube.apk"

