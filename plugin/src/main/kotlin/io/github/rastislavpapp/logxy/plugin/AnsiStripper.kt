package io.github.rastislavpapp.logxy.plugin

private const val ESC = '\u001B'
private const val BEL = '\u0007'

/**
 * Removes ANSI escape sequences from one stream's text: CSI (colours, cursor moves), OSC (titles, links) and the
 * short ones, such as a charset selection. ColoredProcessHandler decodes them already; a plain handler passes them
 * on. A sequence split across chunks is removed as a whole, as its state carries over to the next [strip].
 */
class AnsiStripper {

    private enum class State { TEXT, ESCAPE, CSI, OSC, OSC_ESCAPE }

    private var state = State.TEXT

    fun strip(chunk: String): String {
        if (state == State.TEXT && ESC !in chunk) return chunk
        val text = StringBuilder(chunk.length)
        for (c in chunk) {
            state = when (state) {
                State.TEXT -> if (c == ESC) {
                    State.ESCAPE
                } else {
                    text.append(c)
                    State.TEXT
                }
                State.ESCAPE -> when (c) {
                    '[' -> State.CSI
                    ']' -> State.OSC
                    // Intermediate bytes, as in `ESC ( B`, come before the final one.
                    in ' '..'/' -> State.ESCAPE
                    else -> State.TEXT
                }
                // Parameter and intermediate bytes, up to a final byte from @ to ~.
                State.CSI -> if (c in '@'..'~') State.TEXT else State.CSI
                State.OSC -> when (c) {
                    BEL -> State.TEXT
                    ESC -> State.OSC_ESCAPE
                    else -> State.OSC
                }
                State.OSC_ESCAPE -> if (c == '\\') State.TEXT else State.OSC
            }
        }
        return text.toString()
    }
}
