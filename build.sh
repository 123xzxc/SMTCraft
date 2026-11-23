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
# The jar supports 26.1 and up and is therefore compiled against the oldest client
# jar in that range: an API that exists there exists in every later build too, and
# the four places where the range differs are resolved at runtime (see util/Keys,
# gui/Widgets, gui/ScreenHooks and render/CoverTexture).
MC_CLIENT="${SMTCRAFT_MC_CLIENT:-$TC/mc-client-26.1.jar}"
if [ ! -f "$MC_CLIENT" ]; then
  echo "client jar missing: $MC_CLIENT" >&2
  echo "  copy the 26.1 client jar there, or set SMTCRAFT_MC_CLIENT=<path to client.jar>" >&2
  exit 2
fi
# The dependency set has to belong to the client jar. $TC/mc-libs is the newest supported
# version's set and carries org.lwjgl.sdl, which 26.1 does not have: compiled against it, a
# 26.1 target resolves the wheel backend to "sdl" - an answer that version can never give at
# runtime, so the probe's assertion would pass without distinguishing anything (R32-1).
case "$MC_CLIENT" in
  *26.1*|*26.2*) LIBS="${SMTCRAFT_MC_LIBS:-$TC/mc-libs-26.1}" ;;
  *) LIBS="${SMTCRAFT_MC_LIBS:-$TC/mc-libs}" ;;
esac
if [ ! -d "$LIBS" ]; then
  echo "dependency set missing: $LIBS" >&2
  echo "  build it from the client jar's libraries, or set SMTCRAFT_MC_LIBS=<dir with *.jar>" >&2
  exit 2
fi
echo "libs      : $LIBS"
CP="$(hostpath "$MC_CLIENT")$SEP$(hostpath "$TC/fabric-loader-0.20.1.jar")"
for jar in "$LIBS"/*.jar "$TC"/apijars/META-INF/jars/*.jar "$TC"/modmenu-*.jar; do
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
mkdir -p "$STAGE"
# Everything below builds inside `cd "$OUT/classes"`, so a relative staging path stops
# meaning the same directory there and jar fails on the manifest first (R33-6). Resolve
# it once, in the drive-letter form the Windows JDK understands.
STAGE="$(cd "$STAGE" && { pwd -W 2>/dev/null || pwd; })"
OUT="$STAGE/direct"
mkdir -p "$OUT/classes" "$HERE/build/libs"

mapfile -t SOURCES < <(find "$HERE/src/main/java" -name '*.java' | sort)
echo "sources   : ${#SOURCES[@]}"
# -Xlint:all keeps new API misuse visible; deprecation and serial stay muted
# because the font pack already documents its deprecated call (D4).
"$JAVAC" -encoding UTF-8 --release 25 -Xlint:all,-deprecation,-serial,-removal,-restricted,-cast,-classfile,-dangling-doc-comments,-processing \
  -cp "$CP" -d "$OUT/classes" "${SOURCES[@]}"

cp -r "$HERE/src/main/resources/." "$OUT/classes/"
# tmp + mv, not "sed -i": BSD sed demands a backup suffix after -i, and the first
# non-GNU host died right here (R32-5).
sed "s/[$]{version}/$VER/g" "$OUT/classes/fabric.mod.json" > "$OUT/fabric.mod.json.tmp"
mv -f "$OUT/fabric.mod.json.tmp" "$OUT/classes/fabric.mod.json"
cp "$HERE/LICENSE" "$OUT/classes/LICENSE_$NAME"
# Apache-2.0 attribution for the ported lyric parsers must travel with the jar
[ -f "$HERE/THIRD_PARTY_NOTICES.md" ] && cp "$HERE/THIRD_PARTY_NOTICES.md" "$OUT/classes/THIRD_PARTY_NOTICES_$NAME.md"

# FFM needs native access; declaring it in the manifest means the mod runs
# without an extra launcher flag (A22).
printf 'Manifest-Version: 1.0\r\nEnable-Native-Access: ALL-UNNAMED\r\n\r\n' > "$OUT/MANIFEST.MF"
# The file name states the range this one jar covers: 26.1 through 26.4.
JARNAME="$NAME-$VER+mc26.1-26.4.jar"
# Reproducible on purpose (R31-9): jar stores every entry's mtime, so two builds of one tree came
# out the same size and with different md5 - which reads as tampering to anyone who re-checks a
# published checksum. A fixed date plus a sorted entry list makes the bytes depend on the tree and
# nothing else, so the md5 this script prints can be reproduced by anyone.
STAMP="2026-01-01T00:00:00Z"
mapfile -t ENTRIES < <(cd "$OUT/classes" && find . -type f | sed 's|^\./||' | LC_ALL=C sort)
# jar's -C applies to the operand that follows it, not to a list, so the walk happens inside the
# class tree and every entry is named relative to it (a list after -C is looked up in the current
# directory instead, which fails on the first class).
( cd "$OUT/classes" && "$JAR" --create --file "$STAGE/$JARNAME" --manifest "$OUT/MANIFEST.MF" \
  --date="$STAMP" "${ENTRIES[@]}" )
cp "$STAGE/$JARNAME" "$HERE/build/libs/$JARNAME"
echo "built     : build/libs/$JARNAME ($(wc -c < "$HERE/build/libs/$JARNAME") bytes)"
# A command substitution that fails does not trip "set -e" - it is an argument to echo, so the
# first host without md5sum printed an empty checksum and exited 0 (R32-5).
if command -v md5sum >/dev/null 2>&1; then
  MD5="$(md5sum "$HERE/build/libs/$JARNAME" | cut -d' ' -f1)"
elif command -v md5 >/dev/null 2>&1; then
  MD5="$(md5 -q "$HERE/build/libs/$JARNAME")"
else
  MD5="unavailable"
  echo "warning: no md5 tool on this host, so the checksum cannot be shown" >&2
fi
echo "md5       : $MD5 (fixed --date, sorted entries: re-running this build reproduces it)"
