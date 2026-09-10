package com.arkhos.stratus.terminal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser de secuencias de escape VT100/ANSI (ECMA-48).
 *
 * <p>Procesa un flujo de bytes carácter a carácter, actualizando un
 * {@link ScreenBuffer} por cada secuencia reconocida. Las secuencias
 * desconocidas o no soportadas se loguean a nivel TRACE y se ignoran.</p>
 *
 * <h3>Secuencias soportadas</h3>
 * <ul>
 *   <li>Caracteres imprimibles → colocados en la posición del cursor</li>
 *   <li>CR ({@code \r}), LF ({@code \n}), BS ({@code \b}), TAB ({@code \t}), BEL (ignorado)</li>
 *   <li>ESC [ (CSI): A B C D E F G H J K L M P @ X S T r s u m c n d ?h ?l</li>
 *   <li>ESC 7 / 8 — guardar/restaurar cursor</li>
 *   <li>ESC M — reverse index (scroll hacia abajo)</li>
 *   <li>ESC D — index (LF)</li>
 *   <li>ESC E — siguiente línea (CR + LF)</li>
 *   <li>ESC c — reset completo</li>
 *   <li>ESC ( B / 0 — designación de charset (aceptada; cambio ignorado, se asume ASCII)</li>
 *   <li>ESC ] ... BEL/ST — OSC (Operating System Command), consumido en silencio</li>
 * </ul>
 *
 * <h3>Extensión</h3>
 * Para añadir soporte a secuencias adicionales, extiende {@link #handleCsi(char, int[])}
 * o agrega un nuevo estado en {@link #feed(byte)}. El buffer está completamente
 * aislado del parser.
 */
public final class Vt100Parser {

    private static final Logger log = LoggerFactory.getLogger(Vt100Parser.class);

    private enum State {
        NORMAL,
        ESCAPE,       // se recibió ESC
        CSI,          // se recibió ESC [
        CSI_PRIV,     // se recibió ESC [ ?
        OSC,          // se recibió ESC ]
        CHARSET       // se recibió ESC (
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
     * Procesa un byte individual.
     * Invocar repetidamente a medida que llegan bytes de la red.
     */
    public void feed(byte b) {
        int c = b & 0xFF;

        switch (state) {
            case NORMAL:    handleNormal(c); break;
            case ESCAPE:    handleEscape(c); break;
            case CSI:       handleCsiInput(c, false); break;
            case CSI_PRIV:  handleCsiInput(c, true);  break;
            case OSC:       handleOsc(c);   break;
            case CHARSET:   state = State.NORMAL; break; // consume un char tras ESC(
            default:        state = State.NORMAL; break;
        }
    }

    /**
     * Procesa un bloque de bytes.
     * Más eficiente que llamar a {@link #feed(byte)} en bucle porque el
     * llamador puede pasar directamente el buffer de red sin copias.
     */
    public void feed(byte[] data, int off, int len) {
        for (int i = off; i < off + len; i++) {
            feed(data[i]);
        }
    }

    // ── Manejadores de estado ─────────────────────────────────────────────────

    private void handleNormal(int c) {
        if (c == 0x1B) {               // ESC — inicio de secuencia de escape
            state = State.ESCAPE;
        } else if (c == 0x0D) {        // CR — retorno de carro
            buffer.carriageReturn();
        } else if (c == 0x0A) {        // LF — avance de línea
            buffer.lineFeed();
        } else if (c == 0x08) {        // BS — retroceso
            buffer.backspace();
        } else if (c == 0x09) {        // HT — tabulador horizontal
            buffer.tab();
        } else if (c == 0x07) {        // BEL — campana, ignorada
            // no-op
        } else if (c == 0x0C) {        // FF — form-feed, se trata como LF
            buffer.lineFeed();
        } else if (c >= 0x20 && c < 0xFF) {
            // Carácter imprimible — decodificar byte a char con el charset configurado
            char ch = decodeChar((byte) c);
            buffer.putChar(ch);
        }
        // Otros caracteres de control (0x00-0x1F no listados) se ignoran en silencio
    }

    private void handleEscape(int c) {
        switch (c) {
            case '[':                          // CSI — inicio de secuencia de control
                paramBuf.setLength(0);
                state = State.CSI;
                break;
            case ']':                          // OSC — Operating System Command
                paramBuf.setLength(0);
                oscReceivingBel = false;
                state = State.OSC;
                break;
            case '(':                          // designación de charset (ESC ( B/0)
                state = State.CHARSET;
                break;
            case ')': case '*': case '+':      // otros slots de charset — consumir un char
                state = State.CHARSET;
                break;
            case '7':                          // guardar cursor
                buffer.saveCursor();
                state = State.NORMAL;
                break;
            case '8':                          // restaurar cursor
                buffer.restoreCursor();
                state = State.NORMAL;
                break;
            case 'M':                          // reverse index — scroll hacia abajo una línea
                buffer.scrollDown(1);
                state = State.NORMAL;
                break;
            case 'c':                          // reset completo
                buffer.reset();
                state = State.NORMAL;
                break;
            case 'D':                          // index — equivale a LF
                buffer.lineFeed();
                state = State.NORMAL;
                break;
            case 'E':                          // siguiente línea — CR + LF
                buffer.carriageReturn();
                buffer.lineFeed();
                state = State.NORMAL;
                break;
            case '=': case '>':               // modo application/numeric keypad — ignorar
                state = State.NORMAL;
                break;
            default:
                log.trace("[VT100] ESC desconocido: {}", (char) c);
                state = State.NORMAL;
                break;
        }
    }

    private void handleCsiInput(int c, boolean isPrivate) {
        if (c == '?') {
            // ESC [ ? — inicio de modo privado
            state = State.CSI_PRIV;
            return;
        }
        if ((c >= '0' && c <= '9') || c == ';') {
            // Acumular dígitos y separadores de parámetros
            paramBuf.append((char) c);
            return;
        }
        // Byte final de la secuencia CSI
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
        if (c == 0x07) {               // BEL termina el OSC
            state = State.NORMAL;
        } else if (c == 0x1B) {        // ESC podría iniciar ST (ESC \)
            oscReceivingBel = true;
        } else if (oscReceivingBel && c == '\\') {  // ST = ESC '\'
            state = State.NORMAL;
        } else {
            oscReceivingBel = false;
            // acumular datos OSC en silencio
        }
    }

    // ── Despacho de secuencias CSI ────────────────────────────────────────────

    private void handleCsi(char cmd, int[] p) {
        switch (cmd) {
            case 'A': buffer.moveCursorUp(   param(p, 0, 1)); break; // cursor arriba
            case 'B': buffer.moveCursorDown( param(p, 0, 1)); break; // cursor abajo
            case 'C': buffer.moveCursorRight(param(p, 0, 1)); break; // cursor derecha
            case 'D': buffer.moveCursorLeft( param(p, 0, 1)); break; // cursor izquierda
            case 'E':  // cursor a siguiente línea
                buffer.moveCursorDown(param(p, 0, 1));
                buffer.carriageReturn();
                break;
            case 'F':  // cursor a línea anterior
                buffer.moveCursorUp(param(p, 0, 1));
                buffer.carriageReturn();
                break;
            case 'G':  // columna absoluta del cursor (1-based)
                buffer.setCursor(buffer.cursorRow(), param(p, 0, 1) - 1);
                break;
            case 'H':  // posición del cursor ESC [ fila ; col H
            case 'f':  // igual que H
                buffer.setCursor(param(p, 0, 1) - 1, param(p, 1, 1) - 1);
                break;
            case 'J': buffer.eraseInDisplay(param(p, 0, 0)); break; // borrar en pantalla
            case 'K': buffer.eraseInLine(   param(p, 0, 0)); break; // borrar en línea
            case 'L': buffer.insertLines(   param(p, 0, 1)); break; // insertar líneas
            case 'M': buffer.deleteLines(   param(p, 0, 1)); break; // eliminar líneas
            case 'P': buffer.deleteCharacters(param(p, 0, 1)); break; // eliminar caracteres
            case '@': buffer.insertCharacters(param(p, 0, 1)); break; // insertar caracteres
            case 'X': buffer.eraseCharacters( param(p, 0, 1)); break; // borrar caracteres
            case 'S': buffer.scrollUp(  param(p, 0, 1)); break; // scroll hacia arriba
            case 'T': buffer.scrollDown(param(p, 0, 1)); break; // scroll hacia abajo
            case 'r':  // definir región de scroll (1-based)
                buffer.setScrollRegion(param(p, 0, 1), param(p, 1, buffer.rows()));
                break;
            case 's': buffer.saveCursor();    break; // guardar cursor
            case 'u': buffer.restoreCursor(); break; // restaurar cursor
            case 'm': buffer.applySgr(p);     break; // atributos de texto (SGR)
            case 'c':  // atributos del dispositivo — el host pregunta qué terminal somos
                // No respondemos (no tenemos acceso al output aquí); ignorar en silencio
                break;
            case 'n':  // informe de estado del dispositivo — también ignorado
                break;
            case 'd':  // posición de línea absoluta (1-based)
                buffer.setCursor(param(p, 0, 1) - 1, buffer.cursorCol());
                break;
            default:
                log.trace("[VT100] CSI no manejado: {} params={}", cmd, paramBuf);
                break;
        }
    }

    private void handlePrivateCsi(char cmd, int[] p) {
        // ESC [ ? <p> h/l — activar/desactivar modo privado
        // Los más relevantes para emulación VT100:
        if (cmd == 'h' || cmd == 'l') {
            boolean enable = (cmd == 'h');
            for (int mode : p) {
                switch (mode) {
                    case 7:   buffer.setAutoWrap(enable);  break;  // DECAWM — auto-wrap
                    case 25:  /* visibilidad del cursor — ignorar */ break;
                    case 1:   /* DECCKM teclas de cursor en modo application — ignorar */ break;
                    case 47:
                    case 1047:
                    case 1049: /* buffer de pantalla alternativo — no soportado, ignorar */ break;
                    default:
                        log.trace("[VT100] Modo privado no manejado: {} {}", mode, cmd);
                        break;
                }
            }
        } else {
            log.trace("[VT100] CSI privado no manejado: {} params={}", cmd, paramBuf);
        }
    }

    // ── Helpers internos ──────────────────────────────────────────────────────

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

    /** Lee el parámetro en el índice indicado; retorna {@code def} si está ausente o es cero. */
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
