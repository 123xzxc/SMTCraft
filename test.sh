#!/usr/bin/env bash
# Offline regression tests: pure classes only (no Minecraft jar, no network).
#   ./test.sh <toolchain>
set -euo pipefail
# --require-jar makes the third version assertion fatal instead of conditional: the run that never
# built a jar is exactly the run where "which version sits inside the thing we upload" goes
# unchecked, and a guard whose own condition decides whether it executes protects nothing (AUDIT-31-9).
REQUIRE_JAR=0
ARGS=()
for arg in "$@"; do
    case "$arg" in
        --require-jar) REQUIRE_JAR=1 ;;
        -*) echo "unknown option: $arg" >&2; exit 2 ;;
        *) ARGS+=("$arg") ;;
    esac
done
if [ "${#ARGS[@]}" -lt 1 ]; then
    echo "usage: ./test.sh <toolchain dir> [--require-jar]" >&2
    exit 2
fi
TC="${ARGS[0]}"
# Same probe as build.sh: each host answers to one of the two names, and a script
# that hard-codes the suffix works on exactly one of them (AUDIT-26-2).
pick() { if [ -x "$1" ]; then printf "%s" "$1"; else printf "%s.exe" "$1"; fi; }
JAVA="$(pick "$TC/jdk25/bin/java")"
JAVAC="$(pick "$TC/jdk25/bin/javac")"
# jar reads a .jar on every host; the local tar does not (GNU tar cannot open zip, bsdtar
# can), so the descriptor check below uses this instead of "$(command -v tar)" (AUDIT-32-4).
JAR="$(pick "$TC/jdk25/bin/jar")"
for tool in "$JAVA" "$JAVAC" "$JAR"; do
    if [ ! -x "$tool" ]; then
        echo "no ${tool##*/} at $TC/jdk25/bin (looked for both the bare name and .exe)" >&2
        exit 2
    fi
done
WORK="$(cd "$(dirname "$0")" && pwd)"
OUT="$TC/_selftest"
# The client jar and the dependency set travel together: $TC/mc-libs is the newest supported
# version's set and carries org.lwjgl.sdl, which 26.1 does not have. Compiled against it a 26.1
# target resolves the wheel backend to "sdl" - an answer that version cannot give at runtime,
# so the probe would print the same thing for both targets and assert nothing (AUDIT-32-1).
CLIENT="${SMTCRAFT_MC_CLIENT:-$TC/mc-client-26.1.jar}"
if [ ! -f "$CLIENT" ]; then
    echo "client jar missing: $CLIENT" >&2
    exit 2
fi
case "$CLIENT" in
  *26.1*|*26.2*) LIBS="${SMTCRAFT_MC_LIBS:-$TC/mc-libs-26.1}" ;;
  *) LIBS="${SMTCRAFT_MC_LIBS:-$TC/mc-libs}" ;;
esac
if [ ! -d "$LIBS" ]; then
    echo "dependency set missing: $LIBS" >&2
    exit 2
fi
# The 26.1 set carries gson 2.13.2 where the newest carries 2.14.0, so the jars are found by
# name instead of by exact file name.
firstjar() { local hits=("$LIBS"/$1); if [ -e "${hits[0]}" ]; then printf "%s" "${hits[0]}"; fi; }
GSON="$(firstjar '*gson*.jar')"
if [ ! -f "$GSON" ]; then
    echo "gson jar missing under $LIBS" >&2
    exit 2
fi
SLF4J="$(firstjar '*slf4j-api*.jar')"
if [ ! -f "$SLF4J" ]; then
    echo "slf4j jar missing under $LIBS" >&2
    exit 2
fi
LOADER="$TC/fabric-loader-0.20.1.jar"
if [ ! -f "$LOADER" ]; then
    echo "fabric loader jar missing: $LOADER" >&2
    exit 2
fi
# Three places name the release: gradle.properties, the asset name in README.md and the
# descriptor inside the jar. build/libs also keeps older jars around, so choosing the wrong
# one to upload has no other symptom (AUDIT-30-7).
VER="$(sed -n 's/^mod_version=//p' "$WORK/gradle.properties")"
if [ -z "$VER" ]; then
    echo "no mod_version in gradle.properties" >&2
    exit 1
fi
if ! grep -q "smtcraft-$VER+mc26.1-26.4.jar" "$WORK/README.md"; then
    echo "FAIL README.md does not name the asset smtcraft-$VER+mc26.1-26.4.jar" >&2
    exit 1
fi
# The descriptor ships a ${version} placeholder and build.sh substitutes it, so the source is
# checked for the placeholder and the built jar for the number (AUDIT-30-7).
if ! grep -qF '"version": "${version}"' "$WORK/src/main/resources/fabric.mod.json"; then
    echo "FAIL fabric.mod.json no longer takes the version from the build" >&2
    exit 1
fi
ASSET="smtcraft-$VER+mc26.1-26.4.jar"
BUILT_JAR="$WORK/build/libs/$ASSET"
# Only the jar this tree builds may sit in build/libs. A jar from another version looks just
# as installable, and next to a same-id mod Fabric refuses to start or, worse, the wrong build
# is the one copied into a player's mods folder. Keep archives under build/test-jars
# (AUDIT-46-8).
STALE=""
for candidate in "$WORK"/build/libs/*.jar; do
    [ -e "$candidate" ] || continue
    name="$(basename "$candidate")"
    if [ "$name" != "$ASSET" ]; then
        STALE="$STALE $name"
    fi
done
if [ -n "$STALE" ]; then
    echo "FAIL build/libs holds a jar that is not this build:$STALE" >&2
    echo "note: move archives to build/test-jars so the stale one cannot be shipped" >&2
    exit 1
fi
# The update check is the only code that talks to the network, and three things about it
# cannot fail a Java test: the scheme of its endpoints, the wait Mod Menu needs, and whether
# it points at the repository the README names (AUDIT-46-9).
UCHECK="$WORK/src/main/java/org/yinyun/smtcraft/util/UpdateCheck.java"
# Shape, not spelling (AUDIT-47-5): the old rule looked for the literal '"http://', so
# '"http" + "://evil"' and SCHEME + "://evil" both slipped past it. Every scheme that can be
# assembled here is a small literal or a plain http URL, and both are refused. What counts as
# https is a property of the code, so SelfTest asserts it on the constants themselves.
HTTP_HITS="$(awk '
    /http:\/\// { printf "%s:%d: %s\n", FILENAME, FNR, $0; next }
    /"https?"/ { printf "%s:%d: %s\n", FILENAME, FNR, $0 }
' "$UCHECK" || true)"
if [ -n "$HTTP_HITS" ]; then
    echo "FAIL UpdateCheck builds a scheme out of a small literal:" >&2
    echo "$HTTP_HITS" >&2
    echo "note: write the whole endpoint, so a reader can see the scheme" >&2
    exit 1
fi
# grep -m1 does the stopping, not head: with pipefail a head that exits early hands sed
# SIGPIPE and the whole script dies with 141
# REPO is owner/name and the schemes live in HOME/API, so both halves are checked: the pair
# has two words, and the README names the same repository behind an https URL
REPO_URL="$(grep -m1 'String REPO *= *"' "$UCHECK" | sed 's/.*"\([^"]*\)".*/\1/')"
case "$REPO_URL" in
    */*) ;;
    *) echo "FAIL the update check no longer names owner/repository (got '$REPO_URL')" >&2; exit 1 ;;
esac
if ! grep -qF "https://github.com/$REPO_URL" "$WORK/README.md"; then
    echo "FAIL README does not name https://github.com/$REPO_URL, so a player cannot find the source" >&2
    exit 1
fi
AWAIT="$WORK/src/main/java/org/yinyun/smtcraft/ModMenuIntegration.java"
# The wait has to sit inside the lambda that getUpdateChecker hands to Mod Menu: anywhere else
# in the file (a constant, a constructor) the badge still answers before the first reply, which
# is the bug the wait exists to prevent (AUDIT-47-5).
AWAIT_OK="$(awk '
    /public UpdateChecker getUpdateChecker\(/ { inside = 1; depth = 0 }
    inside {
        opens = gsub(/\{/, "{")
        closes = gsub(/\}/, "}")
        depth += opens - closes
        if (index($0, "awaitAnswer(") > 0) { found = 1 }
        if (depth <= 0 && opens + closes > 0) { inside = 0 }
    }
    END { print (found ? "yes" : "no") }
' "$AWAIT" || true)"
if [ "$AWAIT_OK" != "yes" ]; then
    echo "FAIL getUpdateChecker no longer waits for the first look (Mod Menu asks once)" >&2
    exit 1
fi
if ! grep -qF "2.5" "$WORK/README.md"; then
    echo "FAIL README no longer states the 2.5 s wait the code relies on" >&2
    exit 1
fi
# The upright branch of a vertical run draws CJK one glyph at a time, and it used to draw them at
# 1.0 and only apply the karaoke swell: the lyric size slider moved the column spacing and left
# every glyph alone (m12323). Rendering cannot be asserted offline, so the shape is.
LYR="$WORK/src/main/java/org/yinyun/smtcraft/render/LyricsRenderer.java"
SHEET="$WORK/src/main/java/org/yinyun/smtcraft/gui/SettingsScreen.java"
UPRIGHT_OK="$(awk '
    /if \(upright\(cp\)\)/ { inside = 1; depth = 0 }
    inside {
        opens = gsub(/\{/, "{")
        closes = gsub(/\}/, "}")
        depth += opens - closes
        if (index($0, "scale(s)") > 0) found = 1
        if (depth <= 0 && opens + closes > 0) inside = 0
    }
    END { print (found ? "yes" : "no") }
' "$LYR" || true)"
if [ "$UPRIGHT_OK" != "yes" ]; then
    echo "FAIL the upright glyph branch drops the lyric size (a CJK column ignores the slider)" >&2
    exit 1
fi
# The track columns of a vertical panel walk the way the stacked panel marquees a title: down and
# back, because a title parked at its tail is unreadable from the front (m12414). The lyrics keep
# the one-way path, and both walks answer to their marquee switch (R49-1). Rendering cannot be
# asserted offline, so the wiring is - but judged inside the method that wires it: "the string is
# somewhere in the file" was satisfied by a shadow constant and by a dead call (R49-8).
HUD="$WORK/src/main/java/org/yinyun/smtcraft/render/NowPlayingHud.java"
LAYOUT="$WORK/src/main/java/org/yinyun/smtcraft/util/HudLayout.java"
LEX="$WORK/src/test/resources/guard-lex.awk"
# One lexer, three judges (R52-2, R52-5): comments are removed with the block state carried
# across lines and string literals are blanked, so every judge below reads code and only code.
clean() { awk -f "$LEX" "$1"; }
body_has() {
    awk -v sig="$2" -v call="$3" '
        FNR == 1 { inside = 0; depth = 0 }
        index($0, sig) > 0 { inside = 1; depth = 0 }
        inside {
            opens = gsub(/\{/, "{")
            closes = gsub(/\}/, "}")
            depth += opens - closes
            if (index($0, call) > 0) found = 1
            if (depth <= 0 && opens + closes > 0) inside = 0
        }
        END { print (found ? "yes" : "no") }
    ' <(clean "$1") || true
}
if [ "$(body_has "$HUD" "private void renderVertical(" "HudLayout.verticalScroll(")" != "yes" ]; then
    echo "FAIL the vertical lyric column no longer walks one way (renderVertical)" >&2
    exit 1
fi
if [ "$(body_has "$HUD" "private void renderVertical(" "cfg.marqueeLyrics")" != "yes" ]; then
    echo "FAIL the vertical lyric column ignores the lyric marquee switch (R49-1)" >&2
    exit 1
fi
if [ "$(body_has "$HUD" "private static int verticalColumn(" "HudLayout.verticalScrollCycle(")" != "yes" ]; then
    echo "FAIL the track columns no longer walk back (a long title parks at its tail)" >&2
    exit 1
fi
if [ "$(body_has "$HUD" "private void drawVerticalTrack(" "cfg.marqueeTrack")" != "yes" ]; then
    echo "FAIL the track columns ignore the track marquee switch (R49-1)" >&2
    exit 1
fi
if [ "$(body_has "$LYR" "public static float marqueeOffset(" "HudLayout.MARQUEE_SPEED")" != "yes" ] \
   || [ "$(body_has "$LYR" "public static float lyricOffset(" "HudLayout.MARQUEE_SPEED")" != "yes" ]; then
    echo "FAIL the marquee keeps its own pace instead of HudLayout's (m12491)" >&2
    exit 1
fi
if ! body_has "$LYR" "public static float lyricOffset(" "HudLayout.scrollOver(" | grep -q yes; then
    echo "FAIL the lyric marquee no longer takes the one-way path" >&2
    exit 1
fi
# A call inside the body still is not a switch that does anything: deleting the `walk ?` left
# every assertion above green while the option was read and then ignored (R50-1). The call has
# to be the thing the switch guards, so this one is judged on the line itself.
# ... and judged on the statement, not the line: "walk ?" on the line passed a branch written the
# wrong way round, and a legal wrap onto the next line was reported as missing it (R51-3).
walk_scan() {
    # Statements, not lines (R51-3), and now both shapes of a real switch (R52-1): the
    # switch may guard the call as a ternary (walk ? call : 0) or as the condition of an
    # if (if (walk) { … call … }). The if form was reported as "the option is read but
    # not used", which sent the reader to the switch while the judge was what only knew
    # one spelling. An inverted if (!walk) is still reported.
    awk -v file="$1" '
        {
            text = $0
            sub(/^[ \t]+/, "", text)
            if (text == "") next
            # reported at the line the call sits on, wrapped or not (R51-3)
            if (at == 0 && text ~ /HudLayout\.verticalScroll/) { at = FNR; frag = text }
            stmt = stmt " " text
        }
        /;/ {
            if (stmt ~ /HudLayout\.verticalScroll(Cycle)?\(/) {
                if (stmt !~ /walk[ \t]*\?[ \t]*HudLayout\.verticalScroll/ \
                    && stmt !~ /if[ \t]*\([ \t]*walk[ \t]*\)[^;]*HudLayout\.verticalScroll/)
                    print "  " file ":" at ": " frag
            }
            stmt = ""
            at = 0
            frag = ""
        }
    ' <(clean "$1")
}
WALK_OK="$(walk_scan "$HUD" || true)"
if [ -n "$WALK_OK" ]; then
    echo "FAIL a scroll call ignores its switch (the option is read but not used, R50-1):" >&2
    echo "$WALK_OK" >&2
    exit 1
fi
# The marquee clock is added to sixty times a second for as long as the game stays open. As a
# float it lost its step at 3.03 days (running 1.875x) and stopped at 6.07 days (R50-4). Pinning
# the two lines that did the fix proved nothing once they were replaced by a cast: the clock has
# to travel as a double all the way into the scrolling functions, and no constant may cut it
# (R51-2). SelfTest measures the cut itself ("a title never jumps back on the hour").
if ! grep -qF "private double clock;" "$HUD"; then
    echo "FAIL the marquee clock is not a double again (it loses its step after three days, R50-4)" >&2
    exit 1
fi
CLOCK_BAD="$(awk '
    # Three shapes of the same cut, over every file that hands the clock along (R52-3).
    # "(float) clock" was caught; "(float) (clock)" was not, and one extra pair of
    # parentheses brought the R50-4 bug back. So: the clock cast to float matches with or
    # without parentheses, any cast on the statement that calls a scrolling function counts,
    # and the phase constant that R51-1 removed may come back from either direction. The
    # files are the three that pass the clock along, not just the HUD.
    FNR == 1 { base = FILENAME; sub(/.*\//, "", base) }
    /\(float\)[ \t]*\(*[ \t]*clock[ \t]*\)*[ \t]*[,);]/ {
        line = $0; gsub(/^[ \t]+/, "", line)
        print "  " base ":" FNR ": " line
        next
    }
    /HudLayout\.(verticalScroll|verticalScrollCycle|scrollCycle|scrollOver)\(/ && /\(float\)/ {
        line = $0; gsub(/^[ \t]+/, "", line)
        print "  " base ":" FNR ": " line
        next
    }
    /clockPhase[ \t]*\(|CLOCK_WRAP/ {
        line = $0; gsub(/^[ \t]+/, "", line)
        print "  " base ":" FNR ": " line
    }
' "$HUD" "$LYR" "$LAYOUT" || true)"
if [ -n "$CLOCK_BAD" ]; then
    echo "FAIL the clock is cut before it reaches the scrolling functions (R51-2):" >&2
    echo "$CLOCK_BAD" >&2
    exit 1
fi
for sig in "public static float scrollCycle(float overflow, double clock" "public static int verticalScrollCycle(double clock"; do
    if ! grep -qF "$sig" "$LAYOUT"; then
        echo "FAIL the cycling walk takes its clock as a float again ($sig, R51-2)" >&2
        exit 1
    fi
done
# Same shape one file over: the height slider must hand its value to the function that both
# snaps it and names it, or the label and the panel drift apart again (R50-1).
SLIDER_BAD="$(awk '
    /cfg\.verticalPanelHeight[ \t]*=/ && !/SmtcConfig\.verticalHeightOf\(/ {
        line = $0
        gsub(/^[ \t]+/, "", line)
        print "  " FNR ": " line
        bad = 1
    }
' "$SHEET" || true)"
if [ -n "$SLIDER_BAD" ]; then
    echo "FAIL the height slider writes the panel height without snapping it (R50-1):" >&2
    echo "$SLIDER_BAD" >&2
    exit 1
fi
if [ -f "$BUILT_JAR" ]; then
    JARCHECK="$WORK/build/jarcheck"
    rm -rf "$JARCHECK"
    mkdir -p "$JARCHECK"
    if ! "$JAR" --extract --file "$BUILT_JAR" --dir "$JARCHECK" fabric.mod.json >/dev/null 2>&1; then
        echo "FAIL cannot read fabric.mod.json out of $BUILT_JAR" >&2
        exit 1
    fi
    if ! grep -qF "\"version\": \"$VER\"" "$JARCHECK/fabric.mod.json"; then
        echo "FAIL $BUILT_JAR does not declare version $VER" >&2
        exit 1
    fi
elif [ "$REQUIRE_JAR" -eq 1 ]; then
    echo "FAIL --require-jar: there is no $BUILT_JAR to inspect (run build.sh first)" >&2
    exit 1
else
    echo "note: no $BUILT_JAR yet, so the descriptor inside it is unchecked; build.sh or --require-jar covers that" >&2
fi
# SmtcConfig is pulled in by the config-gate test and touches FabricLoader for its path.
# The separator belongs to the host, not to the script: a Windows JDK wants ";"
# and needs backslash paths, a Linux JDK wants ":" (AUDIT-36).
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
# (AUDIT-23-1). The file list is an array, never an unquoted command substitution: a
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
        # violation was reported at the previous start line (AUDIT-24-1).
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
          # extra information for whoever has to fix it (AUDIT-24-4)
          if (!reported) printf "%s:%d: two javadoc blocks in a row\n", FILENAME, FNR
          reported = 1
          pending = 0
        }
        if ($0 ~ /\/\*\*/) started = 1
        # any block terminator ends the block, even with code behind it on the same
        # line: "/** doc */ void f() {" is a block, not the absence of one (AUDIT-24-4)
        if ($0 ~ /\*\//) { pending = started; started = 0 }
        else if ($0 !~ /^[ \t]*$/) { pending = 0; reported = 0 }
      }
    ' "$@"
}

# A rebindable action reads its KeyMapping, never a raw key code: a physical code keeps firing
# after the player moves the binding elsewhere - the offset key spent a release doing exactly that,
# and the wheel gate armed on the same predicate.
#
# Matching one literal spelling ("Keys.key(\"KEY_F7\"") only caught the form that had shipped
# once: a computed name, an extra space, or a bare Keys.isKeyDown(296) - the original R28-1 shape -
# all stayed green (AUDIT-31-7). So the shape of the call is checked instead of one string:
#   Keys.key("NAME", ...)  allowed only for the modifiers (there is no mapping for Ctrl) and for
#                          the probe's deliberately missing field, and that one only inside
#                          ShimProbe.java - a global SMTCRAFT_ prefix exempted production code too,
#                          where 296 is exactly the wrong number this guard exists for (AUDIT-32-3)
#   Keys.key(expr, ...)    a name assembled at run time is the same escape with extra steps
#   Keys.isKeyDown(296)    a bare code - no constant, no name, nothing to rebind
# A call that opens at the end of a line continues on the next one, so the two lines are joined
# and judged at the line the call starts on: matching line by line read a legal wrap as "the name
# is computed" and would have failed the build on a pure reformat (AUDIT-32-2).
# Comment lines are skipped: prose about the rule is not an instance of it.
rawkey() {
    awk '
      FNR == 1 { probe = (FILENAME ~ /ShimProbe\.java$/) }
      $0 ~ /^[ \t]*(\/\/|\*)/ { next }
      {
        line = $0
        at = FNR
        if (pending != "") { line = pending line; at = pendingLine; pending = "" }
        if (line ~ /Keys\.key[ \t]*\([ \t]*$/) { pending = line " "; pendingLine = at; next }
        reported = 0
        rest = line
        while (match(rest, /Keys\.key[ \t]*\(/)) {
          tail = substr(rest, RSTART + RLENGTH)
          rest = tail
          if (tail ~ /^[ \t]*"/) {
            name = tail
            sub(/^[ \t]*"/, "", name)
            sub(/".*$/, "", name)
            if (name !~ /^KEY_[LR]CONTROL$/ && !(probe && name == "SMTCRAFT_NO_SUCH_FIELD")) {
              printf "%s:%d: raw key read bypasses the key mapping (%s)\n", FILENAME, at, name
              reported = 1
            }
          } else {
            printf "%s:%d: a key name is computed instead of named\n", FILENAME, at
            reported = 1
          }
        }
        if (!reported && line ~ /Keys\.isKeyDown[ \t]*\([ \t]*[0-9]/)
          printf "%s:%d: a bare key code is passed to isKeyDown()\n", FILENAME, at
      }
    ' "$@"
}

# The entrypoint runs before GLX._initGlfw, and on 26.1 a non-empty GLFW error queue there is
# fatal ("GLFW error before init: [0x10001]"): initialising org.lwjgl.glfw.GLFW loads glfw.dll,
# and Class.forName(name) does initialise while Class.forName(name, false, loader) does not.
# Reading an InputConstants field is safe - its static initialiser only builds the unknown key -
# but turning a key into a display name is not: InputConstants$Type.lambda$static$0 ends in
# GLFW.glfwGetKeyName, which is what the startup line for the offset key used to reach (AUDIT-30-3).
# So key codes and key names may only be read from a tick onwards; the guard cuts the
# entrypoint at onEndTick's definition.
#
# This is an enumeration, not a judgement (AUDIT-31-8): the names below are the reads that have
# actually bitten, plus the method key names end in. Wrapping one of them in a new helper still
# passes - test.sh cannot tell "an entrypoint that reads no key name" from "an entrypoint that
# reads one nobody thought of". A new key-name read at entry time means a new name here, and the
# README says so.
# A page that reads this.width / this.height is a page that has forgotten the multiplier: it
# lays itself out in screen pixels again, and the GUI-scale fix silently stops applying to it.
# Only ScaledScreen may ask the window for its size (AUDIT-36-4).
# The bare name counts too: "int left = (width - contentWidth) / 2" forgets the multiplier just
# as thoroughly as "this.width" does, and the first version of this rule let it through (AUDIT-34-5).
# But a page is allowed to name its own local "width" - "int width = vw() - 24;" is the second
# most natural line to write after "int width = vw()", and calling it a read of the field made
# the guard reject the style it recommends. So the two shapes are judged separately: "this.width"
# is always the field; a bare name is only the field when the file never declares one (AUDIT-35-1).
viewport() {
    # Judged per statement, per block and per word (AUDIT-45-1/2/4): a scope opens on every
    # brace, a name is looked up along the enclosing blocks, a declaration counts only after
    # the statement it sits in has been judged, and "width(" / "height(" is a method, not the
    # field. What changed in R52-5: comment handling is no longer a per-line guess ("skip the
    # lines that start with a star"), it is the shared lexer in src/test/resources/guard-lex.awk
    # - a block comment that opens on one line and runs on for three more is code's absence,
    # not code, and the prose inside it cannot be read as a field (the old rule reported
    # "measured against width and height" on a continuation line).
    local f
    for f in "$@"; do
    awk -v file="$f" '
      # every brace opens a scope; a name is visible in the block that declares it and below
      function visible(id, name,   b) {
        b = id
        while (1) {
          if ((b "," name) in declared) return 1
          if (b == 0) return 0
          b = parent[b]
        }
      }
      # strings and chars were blanked by the lexer before this program saw the line
      # "width()" is a method of that name, not the field
      function calls(s) {
        gsub(/width[ \t]*\(/, "width_(", s)
        gsub(/height[ \t]*\(/, "height_(", s)
        return s
      }
      # a declaration binds a name in the block it appears in; every name in a comma list counts
      function register(stmt,   parts, n, i, p, words, first, name, typed) {
        n = split(stmt, parts, /[,;=()]/)
        typed = 0
        for (i = 1; i <= n; i++) {
          p = parts[i]
          gsub(/^[ \t]+/, "", p)
          gsub(/[ \t]+$/, "", p)
          if (p !~ /^((public|private|protected|static|final|volatile|transient)[ \t]+)*[A-Za-z_][A-Za-z0-9_.]*([ \t]*<[^<>]*>)?([ \t]*\[\])*[ \t]+(width|height)$/) continue
          split(p, words, /[ \t]+/)
          # "return height" has the same shape as "int height": the first word decides
          first = words[1]
          if (first ~ /^(return|new|if|else|while|for|switch|case|throw|assert|instanceof|do|try|yield|catch|synchronized)$/) continue
          typed = 1
          name = words[length(words)]
          declared[target "," name] = 1
        }
        if (typed) {
          # "int width = 0, height = 0" declares the second name as well
          for (i = 1; i <= n; i++) {
            name = parts[i]
            gsub(/^[ \t]+/, "", name)
            gsub(/[ \t]+$/, "", name)
            if (name == "width" || name == "height") declared[target "," name] = 1
          }
        }
      }
      # one statement: its own declaration counts for its own initialiser, and only for that
      # statement - a declaration on a later line is still a read before it (AUDIT-45-2)
      function judge(stmt,   code, k, name) {
        register(stmt)
        code = calls(stmt)
        if (code ~ /this\.(width|height)([^a-zA-Z0-9_]|$)/) {
          printf "%s:%d: a page reads this.width / this.height instead of vw() / vh()\n", file, FNR
          return
        }
        for (k = 1; k <= 2; k++) {
          name = (k == 1) ? "width" : "height"
          if (code ~ ("(^|[^a-zA-Z0-9_.])" name "([^a-zA-Z0-9_]|$)") && !visible(scope, name)) {
            printf "%s:%d: a page reads a bare " name " instead of vw() / vh()\n", file, FNR
            return
          }
        }
      }
      function statements(text,   n, parts, i) {
        n = split(text, parts, ";")
        for (i = 1; i <= n; i++) judge(parts[i])
      }
      FNR == 1 { depth = 0; scope = 0; nextId = 0; target = 0; delete declared; delete parent }
      {
        # the lexer already removed the comments (block ones across lines) and blanked the
        # string literals, so every word left on this line is code (R52-2, R52-5)
        line = $0
        stmt = ""
        for (i = 1; i <= length(line); i++) {
          c = substr(line, i, 1)
          if (c == "{") {
            # the parameters of a signature belong to the block it opens, not to its parent
            target = nextId + 1
            register(stmt)
            stmt = ""
            parent[++nextId] = scope
            scope = nextId
            target = scope
          } else if (c == "}") {
            statements(stmt)
            stmt = ""
            if (scope != 0) scope = parent[scope]
          } else {
            stmt = stmt c
          }
        }
        statements(stmt)
      }
    ' <(clean "$f")
    done
}

beforetick() {
    # Brace depth rather than indentation or a member scan: the sample files and the real
    # source indent differently, and a lambda inside the entrypoint must stay in scope.
    awk '
      /void onInitializeClient\(/ { inside = 1; depth = 0; next }
      inside {
        if ($0 ~ /Keys\.key\(|offsetName\(|getTranslatedKeyMessage\(|getDisplayName\(/)
          printf "%s:%d: a key name is read in the entrypoint\n", FILENAME, FNR
        opens = gsub(/\{/, "{")
        closes = gsub(/\}/, "}")
        depth += opens - closes
        if (opens + closes > 0 && depth <= 0) inside = 0
      }
    ' "$@"
}
# R37: an unclosed /** swallows the members under it. javac says nothing - the block is
# closed legally by the next end-of-comment marker in the file - so the code simply
# disappears and the class silently loses methods (ScaledScreen lost all four mouse
# handlers that way, and every page became unclickable). The rule is lexical: track the
# block comment state line by line, report a "/*" that opens while one is already open,
# and report the opening line of a comment that reaches end of file.
comment() {
    awk '
      FNR == 1 { open = 0 }
      {
        line = $0
        i = 1
        while (i <= length(line)) {
          two = substr(line, i, 2)
          if (open) {
            if (two == "*/") { open = 0; i += 2; continue }
            if (two == "/*") {
              printf "%s:%d: a /* opens inside a comment\n", FILENAME, FNR
              i += 2; continue
            }
          } else if (two == "/*") {
            open = 1; openLine = FNR; i += 2; continue
          }
          i++
        }
      }
      END { if (open) printf "%s:%d: a comment never closes\n", FILENAME, openLine }
    ' "$@"
}

# A row on a page takes its label from a translation key. A key with no entry in the
# language files does not fail anything - the GUI just prints "smtcraft.opt.something"
# where a word belongs - so the keys the pages ask for are checked, not remembered.
ZH="$WORK/src/main/resources/assets/smtcraft/lang/zh_cn.json"
EN="$WORK/src/main/resources/assets/smtcraft/lang/en_us.json"
langkeys() {
    awk '
      {
        rest = $0
        while (match(rest, /"smtcraft\.[a-z0-9_.]+"/)) {
          key = substr(rest, RSTART + 1, RLENGTH - 2)
          rest = substr(rest, RSTART + RLENGTH)
          # "smtcraft.source." + name is a prefix, not a label: it has no entry of its
          # own and never will
          if (key ~ /\.$/ || rest ~ /^[ \t]*\+/) continue
          if (!(key in seen)) { seen[key] = 1; print key }
        }
      }
    ' "$@"
}

missingkeys() {
    local key f
    langkeys "$@" | while read -r key; do
        for f in "$ZH" "$EN"; do
            if [ ! -f "$f" ]; then
                printf '%s: there is no %s\n' "$key" "${f##*/}"
            elif ! grep -qF "\"$key\"" "$f"; then
                printf '%s: no entry in %s\n' "$key" "${f##*/}"
            fi
        done
    done
}


# A language file is JSON. One missing or extra comma and Minecraft skips the whole
# file without a word - every label then shows its raw key, which is exactly what the
# key check above cannot see, because the text is still all there. So the shape is
# checked too: every key line but the last needs a trailing comma, the last one must
# not have one, and the file must not be empty (AUDIT-45-6).
langjson() {
    awk '
      function iskey(line) { return line ~ /^[ \t]*"[^"]*"[ \t]*:/ }
      FNR == 1 { prev = ""; prevKey = 0; count = 0 }
      {
        line = $0
        if (line ~ /^[ \t]*$/) next
        if (line ~ /^[ \t]*\}[ \t]*$/) {
          if (prevKey && prev ~ /,[ \t]*$/) {
            printf "%s:%d: a comma before the closing brace\n", FILENAME, FNR
            bad = 1
          }
          prevKey = 0
          prev = ""
          next
        }
        if (!iskey(line)) next
        if (prevKey && prev !~ /,[ \t]*$/) {
          printf "%s:%d: no comma after the line above\n", FILENAME, FNR
          bad = 1
        }
        prev = line
        prevKey = 1
        count++
      }
      END {
        if (count == 0) { printf "%s: the file has no keys\n", FILENAME; bad = 1 }
        exit (bad ? 1 : 0)
      }
    ' "$@"
}
SAMPLES="$WORK/build/guard-samples"
# SAMPLES comes from $WORK, which pwd reports in MSYS form (/c/...), so the absolute
# path rule from the $OUT guard above cannot be reused verbatim. The protection that
# matters here is narrower: still exactly one directory, directly under build/ (AUDIT-25).
case "$SAMPLES" in ""|"/"|*..) echo "refusing to clean '$SAMPLES'" >&2; exit 1 ;; esac
case "$SAMPLES" in "$WORK"/build/guard-samples) ;; *) echo "refusing to clean '$SAMPLES'" >&2; exit 1 ;; esac
rm -rf "$SAMPLES"
mkdir -p "$SAMPLES"
printf 'class X {\n  /** a */\n  /** b */\n  void f() {}\n}\n' > "$SAMPLES/Dup.java"
printf 'class Y {\n  /** only one */\n  void f() {}\n}\n' > "$SAMPLES/One.java"
printf 'class Z {\n  void f(String what, RuntimeException failed) {\n    LOG.warn("x: {} ({})",\n            what,\n            failed.toString());\n  }\n}\n' > "$SAMPLES/Folded.java"
printf 'class W {\n  void f(String what) {\n    LOG.warn("x: {}", what);\n  }\n}\n' > "$SAMPLES/Quiet.java"
# the two boundaries a rule like this loses first: the line that closes the call
# also carries a comment, and the call sits inside a brace (AUDIT-24-1)
# Each sample carries a clean call first: with a narrow close rule that call never
# closes, open hangs to the end of the file and the violation is reported on the
# clean line - so the check is about the line number, not about "did it print".
printf 'class T {\n  void f(boolean ok, RuntimeException failed) {\n    LOG.warn("a: {}", ok); // a clean call\n    LOG.warn("b: {}", failed.toString()); // why\n  }\n}\n' > "$SAMPLES/Trailing.java"
printf 'class Br {\n  void f(boolean ok, RuntimeException failed) {\n    if (ok) { LOG.warn("a: {}", ok); }\n    if (!ok) { LOG.warn("b: {}", failed.toString()); }\n  }\n}\n' > "$SAMPLES/Braced.java"
# a block ends at */ even when the member follows on the same line (AUDIT-24-4)
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
    echo "guard self-check: the log guard misses a folded LOG.warn(..., x.toString()) (AUDIT-23-2)" >&2
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
        echo "guard self-check: a call ending in '); // why' is not closed where it ends (AUDIT-24-1):" >&2
        echo "$TRAILING_HITS" >&2
        exit 1
        ;;
esac
BRACED_HITS="$(logscan "$SAMPLES/Braced.java")"
case "$BRACED_HITS" in
    *Braced.java:4:*) ;;
    *)
        echo "guard self-check: a call closing inside a brace is not closed where it ends (AUDIT-24-1):" >&2
        echo "$BRACED_HITS" >&2
        exit 1
        ;;
esac
if [ -z "$(dupdoc "$SAMPLES/SameLine.java")" ]; then
    echo "guard self-check: the javadoc guard misses a block that ends on the member's line (AUDIT-24-4)" >&2
    exit 1
fi
printf 'class Raw {\n  int k = Keys.key("KEY_F7", 296);\n}\n' > "$SAMPLES/Raw.java"
printf 'class CleanKey {\n  int k = Keys.key("KEY_LCONTROL", 341);\n}\n' > "$SAMPLES/CleanKey.java"
if [ -z "$(rawkey "$SAMPLES/Raw.java")" ]; then
    echo "guard self-check: the key guard no longer sees a raw read of a rebindable key" >&2
    exit 1
fi
if [ -n "$(rawkey "$SAMPLES/CleanKey.java")" ]; then
    echo "guard self-check: the key guard reports a modifier read" >&2
    exit 1
fi
printf 'class Early {\n  void onInitializeClient() {\n    int k = Keys.key("KEY_LCONTROL", 341);\n  }\n}\n' > "$SAMPLES/Early.java"
printf 'class Late {\n  void onInitializeClient() {\n    int no = 0;\n  }\n\n  private void onEndTick() {\n    int k = Keys.key("KEY_LCONTROL", 341);\n  }\n}\n' > "$SAMPLES/Late.java"
# The second half of the same rule: the value of a key is safe, its display name is not.
# This sample reads no constant at all and still has to be reported.
printf 'class Named {\n  void onInitializeClient() {\n    String label = offsetName();\n  }\n\n  private String offsetName() {\n    return "";\n  }\n}\n' > "$SAMPLES/Named.java"
if [ -z "$(beforetick "$SAMPLES/Early.java")" ]; then
    echo "guard self-check: a key constant read in the entrypoint is not reported" >&2
    exit 1
fi
if [ -z "$(beforetick "$SAMPLES/Named.java")" ]; then
    echo "guard self-check: a key name read in the entrypoint is not reported (AUDIT-30-3)" >&2
    exit 1
fi
printf 'class Shown {\n  void onInitializeClient() {\n    String label = key.getDisplayName();\n  }\n}\n' > "$SAMPLES/Shown.java"
SHOWN_HITS="$(beforetick "$SAMPLES/Shown.java")"
case "$SHOWN_HITS" in
    *Shown.java:3:*) ;;
    *) echo "guard self-check: a key name read through getDisplayName() is not reported (AUDIT-30-3):" >&2; echo "$SHOWN_HITS" >&2; exit 1 ;;
esac
if [ -n "$(beforetick "$SAMPLES/Late.java")" ]; then
    echo "guard self-check: the before-tick guard reports a read from a tick" >&2
    exit 1
fi
# R31-7: three more spellings of the same violation, each of which the old literal pattern missed,
# and one comment that talks about the rule without breaking it.
printf 'class RawNum {\n  boolean f() { return Keys.isKeyDown(296); }\n}\n' > "$SAMPLES/RawNum.java"
printf 'class RawVar {\n  int f(String name) { return Keys.key(name, 296); }\n}\n' > "$SAMPLES/RawVar.java"
printf 'class RawSpaced {\n  int k = Keys.key( "KEY_F7", 296);\n}\n' > "$SAMPLES/RawSpaced.java"
printf 'class RawComment {\n  // Keys.key("KEY_F7", 296) only in prose\n  int k = 0;\n}\n' > "$SAMPLES/RawComment.java"
printf 'class RawOther {\n  int k = Keys.key("KEY_F8", 296);\n}\n' > "$SAMPLES/RawOther.java"
RAW_NUM="$(rawkey "$SAMPLES/RawNum.java")"
case "$RAW_NUM" in
    *RawNum.java:2:*) ;;
    *) echo "guard self-check: a bare Keys.isKeyDown(296) is not reported (AUDIT-31-7):" >&2; echo "$RAW_NUM" >&2; exit 1 ;;
esac
RAW_VAR="$(rawkey "$SAMPLES/RawVar.java")"
case "$RAW_VAR" in
    *RawVar.java:2:*) ;;
    *) echo "guard self-check: a computed key name is not reported (AUDIT-31-7):" >&2; echo "$RAW_VAR" >&2; exit 1 ;;
esac
RAW_SPACED="$(rawkey "$SAMPLES/RawSpaced.java")"
case "$RAW_SPACED" in
    *RawSpaced.java:2:*) ;;
    *) echo "guard self-check: an extra space hides a raw read (AUDIT-31-7):" >&2; echo "$RAW_SPACED" >&2; exit 1 ;;
esac
RAW_OTHER="$(rawkey "$SAMPLES/RawOther.java")"
case "$RAW_OTHER" in
    *RawOther.java:2:*) ;;
    *) echo "guard self-check: a literal name other than the offset key is not reported (AUDIT-31-7):" >&2; echo "$RAW_OTHER" >&2; exit 1 ;;
esac
if [ -n "$(rawkey "$SAMPLES/RawComment.java")" ]; then
    echo "guard self-check: the key guard reports a comment" >&2
    exit 1
fi
# R32-2: a legal wrap and a wrapped violation. The first must stay quiet (the old rule read it
# as a computed name and failed the build on a reformat), the second must be reported on the
# line the call starts on.
printf 'class FoldedKey {\n  int k = Keys.key(\n      "KEY_LCONTROL", 341);\n}\n' > "$SAMPLES/FoldedKey.java"
printf 'class FoldedRaw {\n  int k = Keys.key(\n      "KEY_F7", 296);\n}\n' > "$SAMPLES/FoldedRaw.java"
# R32-3: the probe-offline field is exempt inside ShimProbe.java and nowhere else.
printf 'class ShimProbe {\n  int k = Keys.key("SMTCRAFT_NO_SUCH_FIELD", 7);\n}\n' > "$SAMPLES/ShimProbe.java"
printf 'class ProdProbe {\n  int k = Keys.key("SMTCRAFT_NO_SUCH_FIELD", 7);\n}\n' > "$SAMPLES/ProdProbe.java"
# R36-4: a page laid out in screen pixels instead of design pixels. The first sample must be
# reported on the line that reads the window, the second - which asks the base class - must not.
printf 'class Wide {\n  int w = this.width - 24;\n}\n' > "$SAMPLES/Wide.java"
printf 'class Narrow {\n  int w = vw() - 24;\n}\n' > "$SAMPLES/Narrow.java"
# R34-5: the bare name, and a mention inside a comment that must stay silent.
printf 'class Bare {\n  int w = width - 24;\n}\n' > "$SAMPLES/Bare.java"
printf 'class Noted {\n  int w = vw() - 24; // width - 24 was the old way\n}\n' > "$SAMPLES/Noted.java"
# R35-1: the style the rule recommends - take the width from vw(), then name the local "width".
# This one has to stay silent, and the mutation that removes the declaration registry turns it red.
printf 'class Local {\n  static int bar() {\n    int width = vw() - 24;\n    int left = (width - 40) / 2;\n    return left + width;\n  }\n  static int foo(int height) {\n    return height;\n  }\n}\n' > "$SAMPLES/Local.java"
if [ -n "$(rawkey "$SAMPLES/FoldedKey.java")" ]; then
    echo "guard self-check: the key guard reports a legally wrapped call (AUDIT-32-2)" >&2
    exit 1
fi
FOLDED_RAW="$(rawkey "$SAMPLES/FoldedRaw.java")"
case "$FOLDED_RAW" in
    *FoldedRaw.java:2:*) ;;
    *) echo "guard self-check: a wrapped raw read is not reported where the call starts (AUDIT-32-2):" >&2; echo "$FOLDED_RAW" >&2; exit 1 ;;
esac
if [ -n "$(rawkey "$SAMPLES/ShimProbe.java")" ]; then
    echo "guard self-check: the key guard reports the probe's own missing field (AUDIT-32-3)" >&2
    exit 1
fi
PROD_PROBE="$(rawkey "$SAMPLES/ProdProbe.java")"
case "$PROD_PROBE" in
    *ProdProbe.java:2:*) ;;
    *) echo "guard self-check: the probe exemption leaks into production code (AUDIT-32-3):" >&2; echo "$PROD_PROBE" >&2; exit 1 ;;
esac
VIEW_HITS="$(viewport "$SAMPLES/Wide.java")"
case "$VIEW_HITS" in
    *Wide.java:2:*) ;;
    *) echo "guard self-check: a page reading this.width is not reported (AUDIT-36-4):" >&2; echo "$VIEW_HITS" >&2; exit 1 ;;
esac
VIEW_CLEAN="$(viewport "$SAMPLES/Narrow.java")"
if [ -n "$VIEW_CLEAN" ]; then
    echo "guard self-check: the viewport guard reports a page that uses vw()" >&2
    echo "$VIEW_CLEAN" >&2
    exit 1
fi
BARE_HITS="$(viewport "$SAMPLES/Bare.java")"
case "$BARE_HITS" in
    *Bare.java:2:*) ;;
    *) echo "guard self-check: a bare width / height is not reported (AUDIT-34-5):" >&2; echo "$BARE_HITS" >&2; exit 1 ;;
esac
NOTED_CLEAN="$(viewport "$SAMPLES/Noted.java")"
if [ -n "$NOTED_CLEAN" ]; then
    echo "guard self-check: the viewport guard reports a name that only appears in a comment (AUDIT-34-5)" >&2
    echo "$NOTED_CLEAN" >&2
    exit 1
fi
LOCAL_CLEAN="$(viewport "$SAMPLES/Local.java")"
if [ -n "$LOCAL_CLEAN" ]; then
    echo "guard self-check: the viewport guard rejects a local variable named width (AUDIT-35-1)" >&2
    echo "$LOCAL_CLEAN" >&2
    exit 1
fi
# AUDIT-44-1/2/3: the registry has to be per name and per block, not one flag per file. A local
# "width" in one method must not exempt a bare field read in the next method; a generic
# declaration is a declaration; a comma list declares every name in it; and a block comment in
# the middle of a line is prose, not a read. One sample per shape.
printf 'class Mixed {\n  static int a() { int width = vw() - 24; return width; }\n  static int b() { return (width - 40) / 2; }\n}\n' > "$SAMPLES/Mixed.java"
printf 'class Generic {\n  void a() { java.util.List<String> width = null; }\n  static int b() { return width;\n  }\n}\n' > "$SAMPLES/Generic.java"
printf 'class Pair {\n  static int a() { int width = 0, height = 0; return width + height; }\n}\n' > "$SAMPLES/Pair.java"
printf 'class Inline {\n  int a() { int cx = 0; /* width of the band */ return cx; }\n}\n' > "$SAMPLES/Inline.java"
MIXED_HITS="$(viewport "$SAMPLES/Mixed.java")"
case "$MIXED_HITS" in
    *Mixed.java:3:*) ;;
    *) echo "guard self-check: one local width exempts the whole file (AUDIT-44-1):" >&2; echo "$MIXED_HITS" >&2; exit 1 ;;
esac
if [ "$(printf '%s\n' "$MIXED_HITS" | grep -c 'Mixed.java:')" -ne 1 ]; then
    echo "guard self-check: the viewport guard reports more than the field read (AUDIT-44-1):" >&2
    echo "$MIXED_HITS" >&2
    exit 1
fi
GENERIC_HITS="$(viewport "$SAMPLES/Generic.java")"
case "$GENERIC_HITS" in
    *Generic.java:3:*) ;;
    *) echo "guard self-check: a generic declaration is not registered as a name (AUDIT-44-1):" >&2; echo "$GENERIC_HITS" >&2; exit 1 ;;
esac
case "$GENERIC_HITS" in
    *Generic.java:2:*) echo "guard self-check: a generic declaration is reported as a read (AUDIT-44-1):" >&2; echo "$GENERIC_HITS" >&2; exit 1 ;;
esac
PAIR_CLEAN="$(viewport "$SAMPLES/Pair.java")"
if [ -n "$PAIR_CLEAN" ]; then
    echo "guard self-check: the second name of a comma list is not registered (AUDIT-44-3)" >&2
    echo "$PAIR_CLEAN" >&2
    exit 1
fi
INLINE_CLEAN="$(viewport "$SAMPLES/Inline.java")"
if [ -n "$INLINE_CLEAN" ]; then
    echo "guard self-check: a block comment inside a line is reported as a read (AUDIT-44-2)" >&2
    echo "$INLINE_CLEAN" >&2
    exit 1
fi
# AUDIT-45-1/2/4: scope is the block, not the method; a declaration only counts from the line it
# stands on; and judgement is lexical, not by name. Each sample pins one of those three.
printf 'class Nested {\n  static int a(boolean k) {\n    if (k) { int width = 5; }\n    return width - 24;\n  }\n}\n' > "$SAMPLES/Nested.java"
printf 'class After {\n  static int a() {\n    int cx = (width - 40) / 2;\n    int width = 0;\n    return cx + width;\n  }\n}\n' > "$SAMPLES/After.java"
printf 'class StrW {\n  static void a() {\n    log("do not read this.width here");\n    log("guard: this.height too");\n  }\n}\n' > "$SAMPLES/StrW.java"
printf 'class StrB {\n  static void a() {\n    log("the bare width is the trap");\n  }\n}\n' > "$SAMPLES/StrB.java"
printf 'class Call {\n  static int width() { return 1; }\n  static int height() { return 2; }\n  static int a() { return width() - height(); }\n}\n' > "$SAMPLES/Call.java"
printf 'class Btn {\n  static void a() {\n    widget("x", width - 8);\n  }\n}\n' > "$SAMPLES/Btn.java"
NESTED_HITS="$(viewport "$SAMPLES/Nested.java")"
case "$NESTED_HITS" in
    *Nested.java:4:*) ;;
    *) echo "guard self-check: a local inside a block exempts the read outside it (AUDIT-45-1):" >&2; echo "$NESTED_HITS" >&2; exit 1 ;;
esac
case "$NESTED_HITS" in
    *Nested.java:3:*) echo "guard self-check: the declaration inside the block is reported as a read (AUDIT-45-1):" >&2; echo "$NESTED_HITS" >&2; exit 1 ;;
esac
AFTER_HITS="$(viewport "$SAMPLES/After.java")"
case "$AFTER_HITS" in
    *After.java:3:*) ;;
    *) echo "guard self-check: a declaration after the read exempts the read (AUDIT-45-2):" >&2; echo "$AFTER_HITS" >&2; exit 1 ;;
esac
for sample in StrW StrB Call; do
    SAMPLE_CLEAN="$(viewport "$SAMPLES/$sample.java")"
    if [ -n "$SAMPLE_CLEAN" ]; then
        echo "guard self-check: the viewport guard judges by name, not by word ($sample, AUDIT-45-4):" >&2
        echo "$SAMPLE_CLEAN" >&2
        exit 1
    fi
done
BTN_HITS="$(viewport "$SAMPLES/Btn.java")"
case "$BTN_HITS" in
    *Btn.java:3:*) ;;
    *) echo "guard self-check: a field read passed as an argument is not reported (AUDIT-45-4):" >&2; echo "$BTN_HITS" >&2; exit 1 ;;
esac
# R37: a javadoc that never closes, and one that closes on its own line.
printf 'class Nest {\n  /**\n   * a description\n\n  @Override\n  public void f() {}\n}\n' > "$SAMPLES/Nest.java"
printf 'class Closed {\n  /** a description */\n  public void f() {}\n}\n' > "$SAMPLES/Closed.java"
NEST_HITS="$(comment "$SAMPLES/Nest.java")"
case "$NEST_HITS" in
    *Nest.java:2:*) ;;
    *) echo "guard self-check: an unclosed javadoc block is not reported (AUDIT-37):" >&2; echo "$NEST_HITS" >&2; exit 1 ;;
esac
CLOSED_HITS="$(comment "$SAMPLES/Closed.java")"
if [ -n "$CLOSED_HITS" ]; then
    echo "guard self-check: the comment guard reports a closed block (AUDIT-37)" >&2
    echo "$CLOSED_HITS" >&2
    exit 1
fi
# a label with an entry and a label without one: the first must stay quiet, the second
# must be reported against the file it is missing from
printf 'class Lang {\n  String a = "smtcraft.opt.karaoke_color";\n  String b = "smtcraft.opt.no_such_key";\n}\n' > "$SAMPLES/Lang.java"
LANG_SEEN="$(langkeys "$SAMPLES/Lang.java" | grep -cx 'smtcraft.opt.karaoke_color')"
if [ "$LANG_SEEN" -ne 1 ]; then
    echo "guard self-check: the key scan does not see a label key (AUDIT-38)" >&2
    exit 1
fi
LANG_MISSING="$(missingkeys "$SAMPLES/Lang.java")"
case "$LANG_MISSING" in
    *smtcraft.opt.no_such_key*) ;;
    *) echo "guard self-check: a label without a translation is not reported (AUDIT-38):" >&2; echo "$LANG_MISSING" >&2; exit 1 ;;
esac
case "$LANG_MISSING" in
    *karaoke_color*) echo "guard self-check: the key guard reports a label that exists (AUDIT-38)" >&2; exit 1 ;;
esac
# three shapes of the same mistake: a missing comma, a trailing one, and a file with
# no keys at all. The first two are what a hand edit produces (AUDIT-45-6).
printf '{\n  "a": "b",\n  "c": "d"\n}\n' > "$SAMPLES/lang-ok.json"
printf '{\n  "a": "b"\n  "c": "d"\n}\n' > "$SAMPLES/lang-nocomma.json"
printf '{\n  "a": "b",\n}\n' > "$SAMPLES/lang-trail.json"
JSON_OK="$(langjson "$SAMPLES/lang-ok.json")"
if [ -n "$JSON_OK" ]; then
    echo "guard self-check: the language guard rejects a well formed file (AUDIT-45-6)" >&2
    echo "$JSON_OK" >&2
    exit 1
fi
JSON_BAD="$(langjson "$SAMPLES/lang-nocomma.json" || true)"
case "$JSON_BAD" in
    *"no comma after the line above"*) ;;
    *) echo "guard self-check: a missing comma is not reported (AUDIT-45-6):" >&2; echo "$JSON_BAD" >&2; exit 1 ;;
esac
JSON_TRAIL="$(langjson "$SAMPLES/lang-trail.json" || true)"
case "$JSON_TRAIL" in
    *"a comma before the closing brace"*) ;;
    *) echo "guard self-check: a trailing comma is not reported (AUDIT-45-6):" >&2; echo "$JSON_TRAIL" >&2; exit 1 ;;
esac
# R51-3/R51-4: the two judges above read statements and code, not lines and words. A branch
# written the wrong way round must be reported, a legal wrap must not be, and a multiply must
# not be mistaken for the start of a comment (it used to swallow the closing brace after it).
printf 'class Inv {\n    int shift(boolean walk, double c, int run, int room) {\n        int s = walk ? 0 : HudLayout.verticalScrollCycle(c, run, room);\n        return s;\n    }\n}\n' > "$SAMPLES/Inv.java"
printf 'class Wrap {\n    int shift(boolean walk, double c, int run, int room) {\n        int s = walk\n                ? HudLayout.verticalScrollCycle(c, run, room)\n                : 0;\n        return s;\n    }\n}\n' > "$SAMPLES/Wrap.java"
printf 'class Body {\n    private void a(int x, int y) {\n        int w = x * y; }\n    private void other() {\n        HudLayout.verticalScroll(1.0d, 2, 3);\n    }\n}\n' > "$SAMPLES/Body.java"
WALK_INV="$(walk_scan "$SAMPLES/Inv.java" || true)"
case "$WALK_INV" in
    *Inv.java:3:*) ;;
    *) echo "guard self-check: an inverted walk branch is not reported (R51-3):" >&2; echo "$WALK_INV" >&2; exit 1 ;;
esac
WALK_WRAP="$(walk_scan "$SAMPLES/Wrap.java" || true)"
if [ -n "$WALK_WRAP" ]; then
    echo "guard self-check: a legal wrap of the walk call is reported (R51-3)" >&2
    echo "$WALK_WRAP" >&2
    exit 1
fi
BODY_HITS="$(body_has "$SAMPLES/Body.java" "private void a(" "HudLayout.verticalScroll(" || true)"
if [ "$BODY_HITS" != "no" ]; then
    echo "guard self-check: a multiply is read as the start of a comment (R51-4)" >&2
    exit 1
fi
# R52-1/R52-2/R52-5: the same three judges one shape further out. The walk switch may be an
# if as well as a ternary, a string literal may contain a comment marker without ending the
# method body, and a block comment's continuation line is not code.
printf 'class IfShape {\n    int shift(boolean walk, double c, int run, int room) {\n        if (walk) {\n            int s = HudLayout.verticalScrollCycle(c, run, room);\n            return s;\n        }\n        return 0;\n    }\n}\n' > "$SAMPLES/IfShape.java"
printf 'class IfNot {\n    int shift(boolean walk, double c, int run, int room) {\n        if (!walk) {\n            int s = HudLayout.verticalScrollCycle(c, run, room);\n            return s;\n        }\n        return 0;\n    }\n}\n' > "$SAMPLES/IfNot.java"
printf 'class StrBody {\n    private void a(int x, int y) {\n        String note = "see // not a comment"; }\n    private void other() {\n        HudLayout.verticalScroll(0.0d, 2, 3);\n    }\n}\n' > "$SAMPLES/StrBody.java"
printf 'class Page {\n    void draw() {\n        /* the band is\n           measured against width and height here\n        */\n        int x = vw();\n    }\n}\n' > "$SAMPLES/BlockCmt.java"
IFSHAPE_HITS="$(walk_scan "$SAMPLES/IfShape.java" || true)"
if [ -n "$IFSHAPE_HITS" ]; then
    echo "guard self-check: an if-shaped walk switch is reported (R52-1)" >&2
    echo "$IFSHAPE_HITS" >&2
    exit 1
fi
IFNOT_HITS="$(walk_scan "$SAMPLES/IfNot.java" || true)"
case "$IFNOT_HITS" in
    *IfNot.java:4:*) ;;
    *) echo "guard self-check: an inverted if walk switch is not reported (R52-1):" >&2; echo "$IFNOT_HITS" >&2; exit 1 ;;
esac
STRBODY_HITS="$(body_has "$SAMPLES/StrBody.java" "private void a(" "HudLayout.verticalScroll(" || true)"
if [ "$STRBODY_HITS" != "no" ]; then
    echo "guard self-check: a string is read as the start of a comment (R52-2)" >&2
    exit 1
fi
BLOCKC_HITS="$(viewport "$SAMPLES/BlockCmt.java" || true)"
if [ -n "$BLOCKC_HITS" ]; then
    echo "guard self-check: a block comment continuation line is read as code (R52-5)" >&2
    echo "$BLOCKC_HITS" >&2
    exit 1
fi
rm -rf "$SAMPLES"

# every file that can put a word on the screen, not just the settings page: the update
# check owns the badge and the reasons, and a key it names alone would be missed (AUDIT-47-1)
MISSING="$(missingkeys \
    "$WORK/src/main/java/org/yinyun/smtcraft/gui/SettingsScreen.java" \
    "$WORK/src/main/java/org/yinyun/smtcraft/util/UpdateCheck.java" \
    "$WORK/src/main/java/org/yinyun/smtcraft/ModMenuIntegration.java")"
if [ -n "$MISSING" ]; then
    echo "a row label has no translation: the GUI prints the raw key instead of a word" >&2
    echo "$MISSING" >&2
    exit 1
fi
JSON_HITS="$(langjson "$ZH" "$EN" || true)"
if [ -n "$JSON_HITS" ]; then
    echo "a language file is not valid JSON: Minecraft skips it and every label shows its key" >&2
    echo "$JSON_HITS" >&2
    exit 1
fi

# A throwable reaches the log through Errors.describe and nowhere else: one
# format, so a reader greps for one spelling. Both hand-written forms appeared in
# the same log file before — package-qualified toString() here, "...: null" in the
# bridges (R16-1, R17-2).
# Java silently keeps only the last of two /** */ blocks in a row: the first one is
# dead text with no warning. Five such leftovers appeared over the rounds (R16-2,
# R20 27.7, R21-1), so the rule is checked instead of remembered (AUDIT-21-1).
# Both scan the whole src tree, tests included: a guard that reads src/main is a
# rule about src/main, not about unnamed code (AUDIT-22-1).
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
# The rule is about the pages, so the list is the pages: whatever extends ScaledScreen. Saying it
# that way inverts the old exemption - "skip the base class by name" became "only subclasses
# count" - and a page that forgot "extends" now shows up as a missing page instead of as a file
# that quietly passed (AUDIT-35-2). A comment mentioning "class ScaledScreen" no longer buys a skip.
GUI_PAGES=()
while IFS= read -r page; do
    if grep -q 'extends ScaledScreen' "$page"; then GUI_PAGES+=("$page"); fi
done < <(find "$WORK/src/main/java/org/yinyun/smtcraft/gui" -name '*.java' | sort)
if [ "${#GUI_PAGES[@]}" -eq 0 ]; then
    echo "no page extends ScaledScreen under src/main/java/org/yinyun/smtcraft/gui" >&2
    exit 1
fi
VIEWPORT_HITS="$(viewport "${GUI_PAGES[@]}")"
if [ -n "$VIEWPORT_HITS" ]; then
    echo "$VIEWPORT_HITS" >&2
    echo "a page must lay itself out in design pixels: use vw() / vh() from ScaledScreen" >&2
    exit 1
fi
COMMENT_HITS="$(comment "${SOURCES[@]}")"
if [ -n "$COMMENT_HITS" ]; then
    echo "$COMMENT_HITS" >&2
    echo "a block comment never closes: the code under it is silently gone" >&2
    exit 1
fi
KEY_HITS="$(rawkey "${SOURCES[@]}")"
if [ -n "$KEY_HITS" ]; then
    echo "$KEY_HITS" >&2
    echo "a rebindable action must read its KeyMapping, not a raw key code" >&2
    exit 1
fi
ENTRY="$WORK/src/main/java/org/yinyun/smtcraft/SmtcCraftClient.java"
EARLY_HITS="$(beforetick "$ENTRY")"
if [ -n "$EARLY_HITS" ]; then
    echo "$EARLY_HITS" >&2
    echo "the entrypoint must not read key codes or key names: on 26.1 that loads glfw.dll too early" >&2
    exit 1
fi
# Same lint set as build.sh: "warning-free" has to mean the same thing in both
# paths, otherwise removal/restricted warnings only show up here (AUDIT-49).
LINT="-Xlint:all,-deprecation,-serial,-removal,-restricted,-cast,-classfile,-dangling-doc-comments,-processing"

# SelfTest's -sourcepath pulls in only what it references, so every GUI class, the
# entry point and the settings screen were never compiled by any test run: a broken
# NowPlayingHud still printed "151 checks, 0 failed" while no jar could be built
# (AUDIT-26-1). Compile src/main the way build.sh does, and fail loudly.
echo "== compiling the whole tree"
TREE_CP="$(hostpath "$CLIENT")$SEP$(hostpath "$LOADER")"
for jar in "$LIBS"/*.jar "$TC"/apijars/META-INF/jars/*.jar "$TC"/modmenu-*.jar; do
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
# stops resolving is otherwise a silent downgrade (AUDIT-28-9).
echo "== probing the compatibility shims"
# Which wheel backend is even possible belongs to the client jar, not to this host: 26.1 and
# 26.2 ship GLFW only, 26.3 moved to SDL. Saying it out loud turns "a backend resolved" into
# "the backend this target has resolved" - the dependency set carries lwjgl-sdl from the newest
# version, so a 26.1 target used to answer "sdl" and assert nothing (AUDIT-32-1).
case "$CLIENT" in
  *26.1*|*26.2*) WHEEL_SHOULD=glfw ;;
  *) WHEEL_SHOULD=sdl ;;
esac
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
        -Dsmtcraft.wheel="$WHEEL_SHOULD" \
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
# when the file moves, not skip the comparison (AUDIT-23-3).
"$JAVA" -Dfile.encoding=UTF-8 \
    -Dsmtcraft.protos="$(hostpath "$WORK/src/test/resources/libdbus-prototypes.txt")" \
    -cp "$(hostpath "$OUT")$SEP$CP" org.yinyun.smtcraft.SelfTest
