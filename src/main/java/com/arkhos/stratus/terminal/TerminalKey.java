package com.arkhos.stratus.terminal;

/**
 * VT100/ANSI key sequences to send to the host.
 *
 * <p>Sequences follow the ANSI/VT100 standard (ECMA-48). Some hosts respond to
 * application-keypad mode sequences (ESC O prefix) for F1-F4; others use the
 * VT220 xterm sequences (ESC [ n ~). Both variants are provided where relevant.
 * When connecting to a real Stratus VOS host, use raw-capture mode to confirm
 * which sequences the host expects for function keys.</p>
 */
public enum TerminalKey {

    ENTER("\r"),
    NEWLINE("\n"),
    TAB("\t"),
    BACKSPACE(""),  // DEL — most VT100 hosts treat this as backspace
    BACKSPACE_BS("\b"),   // BS (0x08) — alternative backspace

    ESCAPE(""),

    // Cursor keys (normal mode)
    UP   ("[A"),
    DOWN ("[B"),
    RIGHT("[C"),
    LEFT ("[D"),

    // Cursor keys (application mode) — some hosts enable this via DECCKM
    UP_APP   ("OA"),
    DOWN_APP ("OB"),
    RIGHT_APP("OC"),
    LEFT_APP ("OD"),

    HOME    ("[H"),
    END     ("[F"),
    INSERT  ("[2~"),
    DELETE  ("[3~"),
    PAGE_UP ("[5~"),
    PAGE_DOWN("[6~"),

    // Function keys — xterm/VT220 sequences (most common in TELNET sessions)
    F1 ("[11~"),
    F2 ("[12~"),
    F3 ("[13~"),
    F4 ("[14~"),
    F5 ("[15~"),
    F6 ("[17~"),   // note: 16~ is unused
    F7 ("[18~"),
    F8 ("[19~"),
    F9 ("[20~"),
    F10("[21~"),
    F11("[23~"),   // note: 22~ is unused
    F12("[24~"),

    // Function keys — VT100 application-keypad sequences (F1-F4 alternative)
    F1_VT100("OP"),
    F2_VT100("OQ"),
    F3_VT100("OR"),
    F4_VT100("OS"),

    // Ctrl combinations
    CTRL_C(""),
    CTRL_D(""),
    CTRL_Z(""),
    CTRL_A(""),
    CTRL_E(""),
    CTRL_L(""),  // form-feed, often "clear screen" in shells
    CTRL_U(""),
    CTRL_W(""),

    // Keypad (application mode)
    KP_0("Op"), KP_1("Oq"), KP_2("Or"),
    KP_3("Os"), KP_4("Ot"), KP_5("Ou"),
    KP_6("Ov"), KP_7("Ow"), KP_8("Ox"),
    KP_9("Oy"), KP_MINUS("Om"), KP_COMMA("Ol"),
    KP_PERIOD("On"), KP_ENTER("OM");

    private final String sequence;

    TerminalKey(String sequence) {
        this.sequence = sequence;
    }

    /** The byte sequence to send to the host for this key. */
    public String sequence() {
        return sequence;
    }

    /** Returns the sequence bytes in the given charset (typically ISO-8859-1 or UTF-8). */
    public byte[] bytes(String charset) {
        try {
            return sequence.getBytes(charset);
        } catch (Exception e) {
            return sequence.getBytes();
        }
    }
}
