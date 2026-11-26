# One lexer for every guard that has to tell code from prose (R52-2, R52-5).
#
# Reads Java source on stdin and writes exactly one line per input line, with
#   * comments removed (a block comment may open on one line and close on a later one),
#   * the contents of string and char literals blanked, quotes kept.
# Line numbers therefore survive, and every judge downstream reads code only.
#
# Before this file existed the stripper was copied into body_has and walk_scan and
# written a third time, line by line, inside viewport(). Only one of the three knew
# about block comments, so a javadoc body like "measured against width and height"
# was reported as a field read (R52-5); and none of them knew about string literals,
# so `String note = "see // not a comment"; }` lost its closing brace and the next
# method's body was read as this one's (R52-2).
#
# Not handled: text blocks (""" ... """), which this tree does not use. A string
# literal never spans a line in Java, so a stray quote is dropped at the end of its
# line instead of eating the rest of the file.
BEGIN { block = 0 }
{
    line = $0
    out = ""
    quote = ""
    i = 1
    n = length(line)
    while (i <= n) {
        c = substr(line, i, 1)
        two = substr(line, i, 2)
        if (block) {
            if (two == "*/") { block = 0; i += 2 } else { i += 1 }
            continue
        }
        if (quote != "") {
            if (c == "\\") { i += 2; continue }
            if (c == quote) { quote = ""; out = out c }
            i += 1
            continue
        }
        if (c == "\"" || c == "'") { quote = c; out = out c; i += 1; continue }
        if (two == "//") break
        if (two == "/*") { block = 1; i += 2; continue }
        out = out c
        i += 1
    }
    print out
}
