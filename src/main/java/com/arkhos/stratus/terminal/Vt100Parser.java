package com.arkhos.stratus.terminal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * VT100/ANSI (ECMA-48) escape sequence parser.
 *
 * <p>Processes a stream of bytes character by character, updating a
 * {@link ScreenBuffer} for each recognized sequence. Unknown or unsupported
 * sequences are logged at TRACE level and skipped.</p>
 *
 * <h3>Supported sequences</h3>
 * <ul>
 *   <li>Printable characters → placed at cursor</li>
 *   <li>CR ({@code \r}), LF ({@code \n}), BS ({@code \b}), TAB ({@code \t}), BEL (ignored)</li>
 *   <li>ESC [ (CSI) sequences: A B C D H J K P @ X L M S T r s u m c n ?h ?l</li>
 *   <li>ESC 7 / 8 — save/restore cursor</li>
 *   <li>ESC M — reverse index (scroll down)</li>
 *   <li>ESC c — full reset</li>
 *   <li>ESC ( B / 0 — charset designation (accepted, charset change ignored; ASCII assumed)</li>
 *   <li>ESC ] ... BEL/ST — OSC (Operating System Command), silently consumed</li>
 * </ul>
 *
 * <h3>Extension</h3>
 * To add support for additional sequences, extend the {@link #handleCsi(char, int[])} method
 * or add a new state in {@link #feed(byte)}. The buffer is fully isolated from the parser.
 */
public final class Vt100Parser {

    private static final Logger log = LoggerFactory.getLogger(Vt100Parser.class);

    private enum State {
        NORMAL,
        ESCAPE,       // received ESC
        CSI,          // received ESC [
        CSI_PRIV,     // received ESC [ ?
        OSC,          // received ESC ]
        CHARSET       // received ESC (
    }

    private final ScreenBuffer buffer;
    private final String charset;

    private State state = State.NORMAL;
    private final StringBuilder paramBuf = new StringBuilder(32);
    private boolean oscReceivingBel = false;

    public Vt100Parser(ScreenBuffer buffer, String charset) {
        this.buffer  = buffer;
        this.charset = charset;
    }

    /**
     * Feed a single byte into the parser.
     * Invoke repeatedly as bytes arrive from the network.
     */
    public void feed(byte b) {
        int c = b & 0xFF;

        switch (state) {
            case NORMAL:    handleNormal(c); break;
            case ESCAPE:    handleEscape(c); break;
            case CSI:       handleCsiInput(c, false); break;
            case CSI_PRIV:  handleCsiInput(c, true);  break;
            case OSC:       handleOsc(c);   break;
            case CHARSET:   state = State.NORMAL; break; // consume one char after ESC(
            default:        state = State.NORMAL; break;
        }
    }

    /**
     * Feed a chunk of bytes.
     * More efficient than calling {@link #feed(byte)} in a loop because
     * the caller can pass the raw network buffer directly.
     */
    public void feed(byte[] data, int off, int len) {
        for (int i = off; i < off + len; i++) {
            feed(data[i]);
        }
    }

    // -------------------------------------------------------------------------
    // State handlers
    // -------------------------------------------------------------------------

    private void handleNormal(int c) {
        if (c == 0x1B) {               // ESC
            state = State.ESCAPE;
        } else if (c == 0x0D) {        // CR
            buffer.carriageReturn();
        } else if (c == 0x0A) {        // LF
            buffer.lineFeed();
        } else if (c == 0x08) {        // BS
            buffer.backspace();
        } else if (c == 0x09) {        // HT (tab)
            buffer.tab();
        } else if (c == 0x07) {        // BEL — ignore
            // no-op
        } else if (c == 0x0C) {        // FF — treat as LF
            buffer.lineFeed();
        } else if (c >= 0x20 && c < 0xFF) {
            // Printable character — decode byte to char
            char ch = decodeChar((byte) c);
            buffer.putChar(ch);
        }
        // Other control chars (0x00-0x1F not listed above) are silently ignored
    }

    private void handleEscape(int c) {
        switch (c) {
            case '[':                          // CSI
                paramBuf.setLength(0);
                state = State.CSI;
                break;
            case ']':                          // OSC
                paramBuf.setLength(0);
                oscReceivingBel = false;
                state = State.OSC;
                break;
            case '(':                          // charset designation (ESC ( B/0)
                state = State.CHARSET;
                break;
            case ')': case '*': case '+':      // other charset slots — consume one char
                state = State.CHARSET;
                break;
            case '7':                          // save cursor
                buffer.saveCursor();
                state = State.NORMAL;
                break;
            case '8':                          // restore cursor
                buffer.restoreCursor();
                state = State.NORMAL;
                break;
            case 'M':                          // reverse index (scroll down one line)
                buffer.scrollDown(1);
                state = State.NORMAL;
                break;
            case 'c':                          // full reset
                buffer.reset();
                state = State.NORMAL;
                break;
            case 'D':                          // index (LF)
                buffer.lineFeed();
                state = State.NORMAL;
                break;
            case 'E':                          // next line
                buffer.carriageReturn();
                buffer.lineFeed();
                state = State.NORMAL;
                break;
            case '=': case '>':               // application/numeric keypad mode — ignore
                state = State.NORMAL;
                break;
            default:
                log.trace("[VT100] Unknown ESC {}", (char) c);
                state = State.NORMAL;
                break;
        }
    }

    private void handleCsiInput(int c, boolean isPrivate) {
        if (c == '?') {
            // ESC [ ? — private mode
            state = State.CSI_PRIV;
            return;
        }
        if ((c >= '0' && c <= '9') || c == ';') {
            paramBuf.append((char) c);
            return;
        }
        // Final byte
        int[] params = parseParams(paramBuf.toString());
        paramBuf.setLength(0);
        state = State.NORMAL;

        if (isPrivate) {
            handlePrivateCsi((char) c, params);
        } else {
            handleCsi((char) c, params);
        }
    }

    private void handleOsc(int c) {
        if (c == 0x07) {               // BEL terminates OSC
            state = State.NORMAL;
        } else if (c == 0x1B) {        // ESC starts possible ST (ESC \)
            oscReceivingBel = true;
        } else if (oscReceivingBel && c == '\\') {  // ST = ESC \
            state = State.NORMAL;
        } else {
            oscReceivingBel = false;
            // accumulate OSC data silently
        }
    }

    // -------------------------------------------------------------------------
    // CSI sequence dispatch
    // -------------------------------------------------------------------------

    private void handleCsi(char cmd, int[] p) {
        switch (cmd) {
            case 'A': buffer.moveCursorUp(   param(p, 0, 1)); break;
            case 'B': buffer.moveCursorDown( param(p, 0, 1)); break;
            case 'C': buffer.moveCursorRight(param(p, 0, 1)); break;
            case 'D': buffer.moveCursorLeft( param(p, 0, 1)); break;
            case 'E':  // cursor next line
                buffer.moveCursorDown(param(p, 0, 1));
                buffer.carriageReturn();
                break;
            case 'F':  // cursor previous line
                buffer.moveCursorUp(param(p, 0, 1));
                buffer.carriageReturn();
                break;
            case 'G':  // cursor column (1-based)
                buffer.setCursor(buffer.cursorRow(), param(p, 0, 1) - 1);
                break;
            case 'H':  // cursor position  ESC [ row ; col H
            case 'f':  // same as H
                buffer.setCursor(param(p, 0, 1) - 1, param(p, 1, 1) - 1);
                break;
            case 'J': buffer.eraseInDisplay(param(p, 0, 0)); break;
            case 'K': buffer.eraseInLine(   param(p, 0, 0)); break;
            case 'L': buffer.insertLines(   param(p, 0, 1)); break;
            case 'M': buffer.deleteLines(   param(p, 0, 1)); break;
            case 'P': buffer.deleteCharacters(param(p, 0, 1)); break;
            case '@': buffer.insertCharacters(param(p, 0, 1)); break;
            case 'X': buffer.eraseCharacters( param(p, 0, 1)); break;
            case 'S': buffer.scrollUp(  param(p, 0, 1)); break;
            case 'T': buffer.scrollDown(param(p, 0, 1)); break;
            case 'r':  // set scroll region (1-based)
                buffer.setScrollRegion(param(p, 0, 1), param(p, 1, buffer.rows()));
                break;
            case 's': buffer.saveCursor();    break;
            case 'u': buffer.restoreCursor(); break;
            case 'm': buffer.applySgr(p);     break;
            case 'c':  // device attributes — host is asking what terminal we are
                // We don't respond (no output stream here); silently ignore
                break;
            case 'n':  // device status report — similarly ignored
                break;
            case 'd':  // line position absolute (1-based)
                buffer.setCursor(param(p, 0, 1) - 1, buffer.cursorCol());
                break;
            default:
                log.trace("[VT100] Unhandled CSI {} params={}", cmd, paramBuf);
                break;
        }
    }

    private void handlePrivateCsi(char cmd, int[] p) {
        // ESC [ ? <p> h/l — private mode set/reset
        // The most relevant ones for VT100 emulation:
        if (cmd == 'h' || cmd == 'l') {
            boolean enable = (cmd == 'h');
            for (int mode : p) {
                switch (mode) {
                    case 7:   buffer.setAutoWrap(enable);  break;  // DECAWM
                    case 25:  /* cursor visibility — ignore */ break;
                    case 1:   /* DECCKM application cursor keys — ignore */ break;
                    case 47:
                    case 1047:
                    case 1049: /* alternate screen buffer — not supported, ignore */ break;
                    default:
                        log.trace("[VT100] Unhandled private mode {} {}", mode, cmd);
                        break;
                }
            }
        } else {
            log.trace("[VT100] Unhandled private CSI {} params={}", cmd, paramBuf);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int[] parseParams(String raw) {
        if (raw == null || raw.isEmpty()) return new int[0];
        String[] parts = raw.split(";", -1);
        List<Integer> list = new ArrayList<Integer>(parts.length);
        for (String part : parts) {
            if (part.isEmpty()) {
                list.add(0);
            } else {
                try {
                    list.add(Integer.parseInt(part));
                } catch (NumberFormatException e) {
                    list.add(0);
                }
            }
        }
        int[] result = new int[list.size()];
        for (int i = 0; i < list.size(); i++) result[i] = list.get(i);
        return result;
    }

    /** Read parameter at index, returning {@code def} if missing or zero. */
    private static int param(int[] p, int idx, int def) {
        if (idx >= p.length || p[idx] == 0) return def;
        return p[idx];
    }

    private char decodeChar(byte b) {
        try {
            return new String(new byte[]{b}, charset).charAt(0);
        } catch (Exception e) {
            return (char) (b & 0xFF);
        }
    }
}
