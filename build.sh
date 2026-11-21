#!/usr/bin/env bash
# Direct build: javac + jar, no Gradle.
#
# MC 26.3 ships an already deobfuscated client jar and Fabric publishes no
# mappings for the 26.x line, so there is nothing to remap and Loom is not
# involved. This script is the shortest reproducible path; Gradle produces the
# same jar, see README 从源码构建.
#
# Usage:  ./build.sh [/path/to/toolchain]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TC="${1:-$HERE/../toolchain}"
VER="$(sed -n 's/^mod_version=//p' "$HERE/gradle.properties")"
NAME="$(sed -n 's/^archives_base_name=//p' "$HERE/gradle.properties")"
JDK="$TC/jdk25/bin"
# Windows blocks java.exe from creating directories under Downloads (Defender
# Controlled Folder Access), so classes are staged outside the project and only
# the finished jar is copied back with the shell.
# javac.exe cannot create output directories under Downloads here (Defender
# Controlled Folder Access blocks java.exe there), so the class tree is staged
# next to the toolchain and only the finished jar is copied into build/libs.
STAGE="${SMTCRAFT_STAGE:-$(cygpath -m "$TC")/../smtc-build}"
OUT="$STAGE/direct"

# Both JDKs answer to the same name with or without the suffix, and this script is
# not allowed to know which one it is on: build.sh used to call javac.exe, so the
# first Linux packager got "No such file or directory" for a path that looks fine
# (R26-2). The probe mirrors test.sh - prefer the bare name, fall back to .exe.
pick() { if [ -x "$1" ]; then printf "%s" "$1"; else printf "%s.exe" "$1"; fi; }
JAVAC="$(pick "$JDK/javac")"
JAR="$(pick "$JDK/jar")"
for tool in "$JAVAC" "$JAR"; do
  if [ ! -x "$tool" ]; then
    echo "no ${tool##*/} under $JDK (looked for both the bare name and .exe)" >&2
    exit 2
  fi
done

echo "toolchain : $TC"
echo "version   : $VER"
echo "jdk       : $JDK"

# The classpath separator and the path form both depend on the host: javac here
# is a native Windows binary and needs ";C:\..." while a Linux JDK needs ":/...".
# Writing ";" unconditionally is what kept this script Windows-only (D3/R3-6).
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    SEP=";"
    hostpath() { cygpath -w "$1"; }
    ;;
  *)
    SEP=":"
    hostpath() { printf '%s' "$1"; }
    ;;
esac
CP="$(hostpath "$TC/mc-client-26.3.jar")$SEP$(hostpath "$TC/fabric-loader-0.20.1.jar")"
for jar in "$TC"/mc-libs/*.jar "$TC"/apijars/META-INF/jars/*.jar "$TC"/modmenu-*.jar; do
  if [ -f "$jar" ]; then
    CP="$CP$SEP$(hostpath "$jar")"
  fi
done

# The staging directory is deleted below, so never let an empty or root-ish
# value through (the old script removed whatever the variable held) (D2).
case "$STAGE" in
  ""|"/"|*..) echo "refusing to clean staging dir: '$STAGE'" >&2; exit 1 ;;
esac
if [ ! -d "$STAGE" ] && [ -e "$STAGE" ]; then
  echo "staging path exists and is not a directory: $STAGE" >&2; exit 1
fi
rm -rf "$STAGE"
mkdir -p "$OUT/classes" "$HERE/build/libs"

mapfile -t SOURCES < <(find "$HERE/src/main/java" -name '*.java' | sort)
echo "sources   : ${#SOURCES[@]}"
# -Xlint:all keeps new API misuse visible; deprecation and serial stay muted
# because the font pack already documents its deprecated call (D4).
"$JAVAC" -encoding UTF-8 --release 25 -Xlint:all,-deprecation,-serial,-removal,-restricted,-cast,-classfile,-dangling-doc-comments,-processing \
  -cp "$CP" -d "$OUT/classes" "${SOURCES[@]}"

cp -r "$HERE/src/main/resources/." "$OUT/classes/"
sed -i "s/[$]{version}/$VER/g" "$OUT/classes/fabric.mod.json"
cp "$HERE/LICENSE" "$OUT/classes/LICENSE_$NAME"
# Apache-2.0 attribution for the ported lyric parsers must travel with the jar
[ -f "$HERE/THIRD_PARTY_NOTICES.md" ] && cp "$HERE/THIRD_PARTY_NOTICES.md" "$OUT/classes/THIRD_PARTY_NOTICES_$NAME.md"

# FFM needs native access; declaring it in the manifest means the mod runs
# without an extra launcher flag (A22).
printf 'Manifest-Version: 1.0\r\nEnable-Native-Access: ALL-UNNAMED\r\n\r\n' > "$OUT/MANIFEST.MF"
"$JAR" --create --file "$STAGE/$NAME-$VER.jar" --manifest "$OUT/MANIFEST.MF" \
  -C "$OUT/classes" .
cp "$STAGE/$NAME-$VER.jar" "$HERE/build/libs/$NAME-$VER.jar"
echo "built     : build/libs/$NAME-$VER.jar ($(wc -c < "$HERE/build/libs/$NAME-$VER.jar") bytes)"
