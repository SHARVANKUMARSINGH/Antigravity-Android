package com.antigravity.linux.desktop

/**
 * Standard X11 and RFB (VNC) Key Symbols (Keysyms).
 */
object KeySymbols {
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val LINEFEED = 0xff0a
    const val CLEAR = 0xff0b
    const val RETURN = 0xff0d
    const val PAUSE = 0xff13
    const val SCROLL_LOCK = 0xff14
    const val SYS_REQ = 0xff15
    const val ESCAPE = 0xff1b
    const val DELETE = 0xffff

    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PRIOR = 0xff55 // Page Up
    const val NEXT = 0xff56  // Page Down
    const val END = 0xff57
    const val BEGIN = 0xff58

    const val SELECT = 0xff60
    const val PRINT = 0xff61
    const val EXECUTE = 0xff62
    const val INSERT = 0xff63
    const val UNDO = 0xff65
    const val REDO = 0xff66
    const val MENU = 0xff67
    const val FIND = 0xff68
    const val CANCEL = 0xff69
    const val HELP = 0xff6a
    const val BREAK = 0xff6b

    const val SHIFT_L = 0xffe1
    const val SHIFT_R = 0xffe2
    const val CONTROL_L = 0xffe3
    const val CONTROL_R = 0xffe4
    const val CAPS_LOCK = 0xffe5
    const val SHIFT_LOCK = 0xffe6
    const val META_L = 0xffe7
    const val META_R = 0xffe8
    const val ALT_L = 0xffe9
    const val ALT_R = 0xffea
    const val SUPER_L = 0xffeb
    const val SUPER_R = 0xffec

    const val F1 = 0xffbe
    const val F2 = 0xffbf
    const val F3 = 0xffc0
    const val F4 = 0xffc1
    const val F5 = 0xffc2
    const val F6 = 0xffc3
    const val F7 = 0xffc4
    const val F8 = 0xffc5
    const val F9 = 0xffc6
    const val F10 = 0xffc7
    const val F11 = 0xffc8
    const val F12 = 0xffc9

    /**
     * Converts a standard unicode character to an X11/RFB keysym.
     */
    fun charToKeysym(char: Char): Int {
        val code = char.code
        return when (char) {
            '\n', '\r' -> RETURN
            '\t' -> TAB
            '\b' -> BACKSPACE
            '\u001B' -> ESCAPE
            else -> {
                if (code in 0x0020..0x00ff) {
                    code
                } else if (code in 0x0100..0xffff) {
                    // Unicode keysym mapping: 0x01000000 + unicode
                    0x01000000 or code
                } else {
                    code
                }
            }
        }
    }
}
