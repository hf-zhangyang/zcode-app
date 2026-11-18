#!/usr/bin/env bash
# Build ZCodeApp.apk without Gradle, using the local Android SDK directly.
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"          # POSIX path for bash tools
WPROJ="$(cygpath -m "$PROJ")"                  # Windows mixed path for exes
SDK="$(cygpath -m "${LOCALAPPDATA}")/Android/Sdk"
BT="$SDK/build-tools/36.1.0"
PLAT="$SDK/platforms/android-36/android.jar"
JBR="$(cygpath -m 'C:\Program Files\Android\Android Studio\jbr')"
export JAVA_HOME="$(cygpath -w 'C:\Program Files\Android\Android Studio\jbr')"
BUILD="$PROJ/build"
WBUILD="$WPROJ/build"
LIBS="$PROJ/libs"
WLIBS="$WPROJ/libs"

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/obj" "$BUILD/dex" "$LIBS"

echo "[0/6] zxing-core"
ZX="$LIBS/zxing-core.jar"
if [ ! -f "$ZX" ]; then
    curl -fsSL --retry 2 --max-time 60 -o "$ZX" \
        "https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar" \
    || curl -fsSL --retry 2 --max-time 60 -o "$ZX" \
        "https://maven.aliyun.com/repository/public/com/google/zxing/core/3.5.3/core-3.5.3.jar"
fi

echo "[1/6] aapt2 compile resources"
"$BT/aapt2.exe" compile --dir "$WPROJ/res" -o "$WBUILD/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2.exe" link \
    -o "$WBUILD/app.unsigned.apk" \
    -I "$PLAT" \
    --manifest "$WPROJ/AndroidManifest.xml" \
    --java "$WBUILD/gen" \
    --min-sdk-version 26 \
    --target-sdk-version 36 \
    --version-code 1 \
    --version-name "1.0" \
    "$WBUILD/res.zip"

echo "[3/6] javac"
find "$BUILD/gen" "$PROJ/src" -name "*.java" > "$BUILD/sources.txt"
sed -i "s|^/c/|C:/|; s|^/./|./|" "$BUILD/sources.txt"   # javac.exe needs Windows paths too
# -J-Duser.language=en keeps messages ASCII (GBK output broke error grepping);
# no output filtering: any javac failure must abort the build here.
if ! "$JBR/bin/javac.exe" -J-Duser.language=en -source 1.8 -target 1.8 -encoding UTF-8 \
    -classpath "$WLIBS/zxing-core.jar" \
    -bootclasspath "$PLAT" -d "$WBUILD/obj" @"$WBUILD/sources.txt"; then
    echo "FATAL: javac failed" >&2
    exit 1
fi
NCLASSES=$(find "$BUILD/obj" -name "*.class" | wc -l)
echo "javac produced $NCLASSES classes"
if [ "$NCLASSES" -eq 0 ]; then
    echo "FATAL: no classes compiled" >&2
    exit 1
fi

echo "[4/6] d8 dex"
CLASS_ARGS=()
while IFS= read -r f; do
    case "$f" in /c/*) f="C:/${f#/c/}" ;; /d/*) f="D:/${f#/d/}" ;; esac
    CLASS_ARGS+=("$f")
done < <(find "$BUILD/obj" -name "*.class")
"$JBR/bin/java.exe" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --release --lib "$PLAT" --min-api 26 \
    --output "$WBUILD/dex" "${CLASS_ARGS[@]}" "$WLIBS/zxing-core.jar"

echo "[5/6] package + zipalign"
"$JBR/bin/jar.exe" --update --file "$WBUILD/app.unsigned.apk" -C "$WBUILD/dex" classes.dex
"$BT/zipalign.exe" -f -p 4 "$WBUILD/app.unsigned.apk" "$WBUILD/app.aligned.apk"

echo "[6/6] sign"
# Signing secrets live in signing.local (git-ignored, never committed):
#   KS_PASS=... KEY_PASS=...
if [ ! -f "$PROJ/signing.local" ]; then
    echo "FATAL: missing signing.local (KS_PASS/KEY_PASS) - secrets stay out of the public repo" >&2
    exit 1
fi
# shellcheck disable=SC1091
source "$PROJ/signing.local"
: "${KS_PASS:?set KS_PASS in signing.local}"
: "${KEY_PASS:?set KEY_PASS in signing.local}"
if [ ! -f "$PROJ/zremote.keystore" ]; then
    "$JBR/bin/keytool.exe" -genkeypair -v \
        -keystore "$WPROJ/zremote.keystore" -alias zremote \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KS_PASS" -keypass "$KEY_PASS" \
        -dname "CN=ZCode Remote, OU=Personal, O=JACK, C=CN"
fi
"$BT/apksigner.bat" sign \
    --ks "$WPROJ/zremote.keystore" --ks-key-alias zremote \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$WPROJ/ZCodeApp.apk" "$WBUILD/app.aligned.apk"

cmd //c "$(cygpath -w "$BT/apksigner.bat")" verify --print-certs "$WPROJ/ZCodeApp.apk" | head -4
ls -la "$PROJ/ZCodeApp.apk"
echo "DONE -> $WPROJ/ZCodeApp.apk"
