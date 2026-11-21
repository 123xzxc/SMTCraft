#!/usr/bin/env bash
# Offline regression tests: pure classes only (no Minecraft jar, no network).
#   ./test.sh <toolchain>
set -euo pipefail
if [ $# -lt 1 ]; then
    echo "usage: ./test.sh <toolchain dir>" >&2
    exit 2
fi
TC="$1"
# Same probe as build.sh: each host answers to one of the two names, and a script
# that hard-codes the suffix works on exactly one of them (R26-2).
pick() { if [ -x "$1" ]; then printf "%s" "$1"; else printf "%s.exe" "$1"; fi; }
JAVA="$(pick "$TC/jdk25/bin/java")"
JAVAC="$(pick "$TC/jdk25/bin/javac")"
if [ ! -x "$JAVA" ] || [ ! -x "$JAVAC" ]; then
    echo "no JDK at $TC/jdk25/bin (looked for both the bare name and .exe)" >&2
    exit 2
fi
WORK="$(cd "$(dirname "$0")" && pwd)"
OUT="$TC/_selftest"
GSON="$TC/mc-libs/com_google_code_gson_gson_2.14.0_gson-2.14.0.jar"
if [ ! -f "$GSON" ]; then
    echo "gson jar missing: $GSON" >&2
    exit 2
fi
SLF4J="$TC/mc-libs/org_slf4j_slf4j-api_2.0.17_slf4j-api-2.0.17.jar"
if [ ! -f "$SLF4J" ]; then
    echo "slf4j jar missing: $SLF4J" >&2
    exit 2
fi
LOADER="$TC/fabric-loader-0.20.1.jar"
if [ ! -f "$LOADER" ]; then
    echo "fabric loader jar missing: $LOADER" >&2
    exit 2
fi
# SmtcConfig is pulled in by the config-gate test and touches FabricLoader for its path.
# The separator belongs to the host, not to the script: a Windows JDK wants ";"
# and needs backslash paths, a Linux JDK wants ":" (R3-6).
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
CP="$(hostpath "$GSON")$SEP$(hostpath "$SLF4J")$SEP$(hostpath "$LOADER")"
case "$OUT" in ""|"/"|/*|*..) echo "refusing to clean '$OUT'" >&2; exit 1 ;; esac
rm -rf "$OUT"
mkdir -p "$OUT"
echo "== checking source rules"
# Both guards below are shell, not Java: nothing in SelfTest can fail them, and a
# checker that is wrong is wrong silently. So each one is run against a sample that
# must produce a hit and a sample that must stay quiet, before it reads the tree
# (R23-1). The file list is an array, never an unquoted command substitution: a
# checkout under "C:/Users/Zhang San" used to split into two arguments, awk failed
# on stderr, the pipe still exited 0 and the guard passed on an empty stdout.
logscan() {
    awk '
      FNR == 1 { open = 0; call = "" }
      {
        if (!open && $0 ~ /LOG\.(warn|info|error|debug)\(/) { open = 1; start = FNR; call = "" }
        if (open) call = call $0 "\n"
        # The call ends at ");" followed only by a line comment and/or closing
        # braces. Asking for a bare ");" missed "); // why" and "...); }", and a
        # missed close left open set to the end of the file, so the next unrelated
        # violation was reported at the previous start line (R24-1).
        if (open && $0 ~ /\);[ \t]*(\/\/.*|\}.*)?$/) {
          if (call ~ /\.toString\(\)/) printf "%s:%d: log line with a bare toString()\n", FILENAME, start
          open = 0; call = ""
        }
      }
      END {
        if (open && call ~ /\.toString\(\)/) printf "%s:%d: log line with a bare toString()\n", FILENAME, start
      }
    ' "$@"
}

dupdoc() {
    awk '
      FNR == 1 { pending = 0; started = 0; reported = 0 }
      {
        if (pending && $0 ~ /^[ \t]*\/\*\*/) {
          # one report per run of blocks: the second line of a run of three had no
          # extra information for whoever has to fix it (R24-4)
          if (!reported) printf "%s:%d: two javadoc blocks in a row\n", FILENAME, FNR
          reported = 1
          pending = 0
        }
        if ($0 ~ /\/\*\*/) started = 1
        # any block terminator ends the block, even with code behind it on the same
        # line: "/** doc */ void f() {" is a block, not the absence of one (R24-4)
        if ($0 ~ /\*\//) { pending = started; started = 0 }
        else if ($0 !~ /^[ \t]*$/) { pending = 0; reported = 0 }
      }
    ' "$@"
}

SAMPLES="$WORK/build/guard-samples"
# SAMPLES comes from $WORK, which pwd reports in MSYS form (/c/...), so the absolute
# path rule from the $OUT guard above cannot be reused verbatim. The protection that
# matters here is narrower: still exactly one directory, directly under build/ (R25).
case "$SAMPLES" in ""|"/"|*..) echo "refusing to clean '$SAMPLES'" >&2; exit 1 ;; esac
case "$SAMPLES" in "$WORK"/build/guard-samples) ;; *) echo "refusing to clean '$SAMPLES'" >&2; exit 1 ;; esac
rm -rf "$SAMPLES"
mkdir -p "$SAMPLES"
printf 'class X {\n  /** a */\n  /** b */\n  void f() {}\n}\n' > "$SAMPLES/Dup.java"
printf 'class Y {\n  /** only one */\n  void f() {}\n}\n' > "$SAMPLES/One.java"
printf 'class Z {\n  void f(String what, RuntimeException failed) {\n    LOG.warn("x: {} ({})",\n            what,\n            failed.toString());\n  }\n}\n' > "$SAMPLES/Folded.java"
printf 'class W {\n  void f(String what) {\n    LOG.warn("x: {}", what);\n  }\n}\n' > "$SAMPLES/Quiet.java"
# the two boundaries a rule like this loses first: the line that closes the call
# also carries a comment, and the call sits inside a brace (R24-1)
# Each sample carries a clean call first: with a narrow close rule that call never
# closes, open hangs to the end of the file and the violation is reported on the
# clean line - so the check is about the line number, not about "did it print".
printf 'class T {\n  void f(boolean ok, RuntimeException failed) {\n    LOG.warn("a: {}", ok); // a clean call\n    LOG.warn("b: {}", failed.toString()); // why\n  }\n}\n' > "$SAMPLES/Trailing.java"
printf 'class Br {\n  void f(boolean ok, RuntimeException failed) {\n    if (ok) { LOG.warn("a: {}", ok); }\n    if (!ok) { LOG.warn("b: {}", failed.toString()); }\n  }\n}\n' > "$SAMPLES/Braced.java"
# a block ends at */ even when the member follows on the same line (R24-4)
printf 'class S1 {\n  /** a */ void f() {}\n  /** b */\n  void g() {}\n}\n' > "$SAMPLES/SameLine.java"
if [ -z "$(dupdoc "$SAMPLES/Dup.java")" ]; then
    echo "guard self-check: the javadoc guard no longer sees a duplicate block" >&2
    exit 1
fi
if [ -n "$(dupdoc "$SAMPLES/One.java")" ]; then
    echo "guard self-check: the javadoc guard reports a clean file" >&2
    exit 1
fi
if [ -z "$(logscan "$SAMPLES/Folded.java")" ]; then
    echo "guard self-check: the log guard misses a folded LOG.warn(..., x.toString()) (R23-2)" >&2
    exit 1
fi
if [ -n "$(logscan "$SAMPLES/Quiet.java")" ]; then
    echo "guard self-check: the log guard reports a clean call" >&2
    exit 1
fi
TRAILING_HITS="$(logscan "$SAMPLES/Trailing.java")"
case "$TRAILING_HITS" in
    *Trailing.java:4:*) ;;
    *)
        echo "guard self-check: a call ending in '); // why' is not closed where it ends (R24-1):" >&2
        echo "$TRAILING_HITS" >&2
        exit 1
        ;;
esac
BRACED_HITS="$(logscan "$SAMPLES/Braced.java")"
case "$BRACED_HITS" in
    *Braced.java:4:*) ;;
    *)
        echo "guard self-check: a call closing inside a brace is not closed where it ends (R24-1):" >&2
        echo "$BRACED_HITS" >&2
        exit 1
        ;;
esac
if [ -z "$(dupdoc "$SAMPLES/SameLine.java")" ]; then
    echo "guard self-check: the javadoc guard misses a block that ends on the member's line (R24-4)" >&2
    exit 1
fi
rm -rf "$SAMPLES"

# A throwable reaches the log through Errors.describe and nowhere else: one
# format, so a reader greps for one spelling. Both hand-written forms appeared in
# the same log file before — package-qualified toString() here, "...: null" in the
# bridges (R16-1, R17-2).
# Java silently keeps only the last of two /** */ blocks in a row: the first one is
# dead text with no warning. Five such leftovers appeared over the rounds (R16-2,
# R20 27.7, R21-1), so the rule is checked instead of remembered (R21-1).
# Both scan the whole src tree, tests included: a guard that reads src/main is a
# rule about src/main, not about unnamed code (R22-1).
mapfile -d '' -t SOURCES < <(find "$WORK/src" -name '*.java' -print0)
if [ "${#SOURCES[@]}" -eq 0 ]; then
    echo "no sources found under $WORK/src" >&2
    exit 1
fi
LOG_HITS="$(logscan "${SOURCES[@]}")"
if [ -n "$LOG_HITS" ]; then
    echo "$LOG_HITS" >&2
    echo "log lines must use Errors.describe (util/Errors.java)" >&2
    exit 1
fi
JAVADOC_HITS="$(dupdoc "${SOURCES[@]}")"
if [ -n "$JAVADOC_HITS" ]; then
    echo "$JAVADOC_HITS" >&2
    echo "a javadoc block is followed by another one; the first is dead text" >&2
    exit 1
fi
# Same lint set as build.sh: "warning-free" has to mean the same thing in both
# paths, otherwise removal/restricted warnings only show up here (R4-9).
LINT="-Xlint:all,-deprecation,-serial,-removal,-restricted,-cast,-classfile,-dangling-doc-comments,-processing"

# SelfTest's -sourcepath pulls in only what it references, so every GUI class, the
# entry point and the settings screen were never compiled by any test run: a broken
# NowPlayingHud still printed "151 checks, 0 failed" while no jar could be built
# (R26-1). Compile src/main the way build.sh does, and fail loudly.
echo "== compiling the whole tree"
CLIENT="${SMTCRAFT_MC_CLIENT:-$TC/mc-client-26.1.jar}"
if [ ! -f "$CLIENT" ]; then
    echo "client jar missing: $CLIENT" >&2
    exit 2
fi
TREE_CP="$(hostpath "$CLIENT")$SEP$(hostpath "$LOADER")"
for jar in "$TC"/mc-libs/*.jar "$TC"/apijars/META-INF/jars/*.jar "$TC"/modmenu-*.jar; do
    if [ -f "$jar" ]; then
        TREE_CP="$TREE_CP$SEP$(hostpath "$jar")"
    fi
done
mapfile -t MAIN < <(find "$WORK/src/main/java" -name '*.java' | sort)
if [ "${#MAIN[@]}" -eq 0 ]; then
    echo "no sources found under $WORK/src/main/java" >&2
    exit 1
fi
if ! "$JAVAC" -encoding UTF-8 --release 25 $LINT -cp "$TREE_CP" -d "$OUT/tree" "${MAIN[@]}"; then
    echo "FAIL src/main does not compile - the self test would have been green anyway" >&2
    exit 1
fi

# The five shims (Keys, Widgets, ScreenHooks, CoverTexture, WheelWatch) are the only code
# that differs between 26.1 and 26.4, and SelfTest cannot see them: it runs without the
# game on its classpath. Probe them against the same client jar build.sh uses - a shim that
# stops resolving is otherwise a silent downgrade (R28-9).
echo "== probing the compatibility shims"
SHIM_SRC="$WORK/src/test/java/org/yinyun/smtcraft/ShimProbe.java"
if [ ! -f "$SHIM_SRC" ]; then
    echo "shim probe missing: $SHIM_SRC" >&2
    exit 1
fi
if ! "$JAVAC" -encoding UTF-8 --release 25 $LINT \
        -cp "$(hostpath "$OUT/tree")$SEP$TREE_CP" -d "$OUT/shim" "$SHIM_SRC"; then
    echo "FAIL the shim probe does not compile against the compile target" >&2
    exit 1
fi
if ! "$JAVA" --enable-native-access=ALL-UNNAMED \
        -cp "$(hostpath "$OUT/tree")$SEP$(hostpath "$OUT/shim")$SEP$TREE_CP" \
        org.yinyun.smtcraft.ShimProbe; then
    echo "FAIL the compatibility shims did not resolve on the compile target" >&2
    exit 1
fi

echo "== compiling tests"
"$JAVAC" -encoding UTF-8 $LINT \
    -cp "$CP" -sourcepath "$WORK/src/main/java" -d "$OUT" \
    "$WORK/src/test/java/org/yinyun/smtcraft/SelfTest.java"
echo "== running tests"
# The C prototypes are passed in rather than looked up: the tests must fail loudly
# when the file moves, not skip the comparison (R23-3).
"$JAVA" -Dfile.encoding=UTF-8 \
    -Dsmtcraft.protos="$(hostpath "$WORK/src/test/resources/libdbus-prototypes.txt")" \
    -cp "$(hostpath "$OUT")$SEP$CP" org.yinyun.smtcraft.SelfTest
