package com.arkhos.stratus.terminal;

import java.util.Arrays;

/**
 * Buffer de pantalla VT100/ANSI mutable.
 *
 * <p>Las coordenadas son 0-based internamente. La API pública de
 * {@link ScreenSnapshot} las expone como 1-based para coincidir con la
 * convención habitual de la documentación de terminales.</p>
 *
 * <p>Todas las mutaciones ocurren en el hilo lector. Los llamadores que
 * necesitan una vista hilo-segura deben invocar {@link #snapshot()}, que
 * retorna una copia inmutable.</p>
 */
public final class ScreenBuffer {

    private final int rows;
    private final int cols;

    private final char[] chars;   // row-major: chars[r*cols+c]
    private final int[]  attrs;   // máscara de atributos SGR por celda

    private int cursorRow;  // 0-based
    private int cursorCol;  // 0-based

    private int savedCursorRow;
    private int savedCursorCol;

    private int scrollTop;    // 0-based, inclusivo
    private int scrollBottom; // 0-based, inclusivo

    private boolean autoWrap = true;

    // Bits de atributos SGR
    static final int ATTR_BOLD      = 1;
    static final int ATTR_UNDERLINE = 2;
    static final int ATTR_BLINK     = 4;
    static final int ATTR_REVERSE   = 8;
    private int currentAttr = 0;

    public ScreenBuffer(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.chars = new char[rows * cols];
        this.attrs = new int[rows * cols];
        this.scrollTop    = 0;
        this.scrollBottom = rows - 1;
        Arrays.fill(chars, ' ');
    }

    // ── Movimiento del cursor ─────────────────────────────────────────────────

    public void setCursor(int row, int col) {
        cursorRow = clampRow(row);
        cursorCol = clampCol(col);
    }

    public void moveCursorUp(int n) {
        cursorRow = Math.max(scrollTop, cursorRow - Math.max(1, n));
    }

    public void moveCursorDown(int n) {
        cursorRow = Math.min(scrollBottom, cursorRow + Math.max(1, n));
    }

    public void moveCursorRight(int n) {
        cursorCol = Math.min(cols - 1, cursorCol + Math.max(1, n));
    }

    public void moveCursorLeft(int n) {
        cursorCol = Math.max(0, cursorCol - Math.max(1, n));
    }

    public void carriageReturn() {
        cursorCol = 0;
    }

    public void lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1);
        } else {
            cursorRow = Math.min(rows - 1, cursorRow + 1);
        }
    }

    public void backspace() {
        if (cursorCol > 0) cursorCol--;
    }

    public void tab() {
        // avanza al siguiente tabulador (cada 8 columnas)
        cursorCol = Math.min(cols - 1, (cursorCol / 8 + 1) * 8);
    }

    public void saveCursor() {
        savedCursorRow = cursorRow;
        savedCursorCol = cursorCol;
    }

    public void restoreCursor() {
        cursorRow = savedCursorRow;
        cursorCol = savedCursorCol;
    }

    // ── Salida de caracteres ──────────────────────────────────────────────────

    public void putChar(char c) {
        if (cursorCol >= cols) {
            if (autoWrap) {
                carriageReturn();
                lineFeed();
            } else {
                cursorCol = cols - 1;
            }
        }
        int idx = index(cursorRow, cursorCol);
        chars[idx] = c;
        attrs[idx] = currentAttr;
        cursorCol++;
    }

    // ── Operaciones de borrado ────────────────────────────────────────────────

    /** Borrado en pantalla: modo 0=cursor al final, 1=inicio al cursor, 2=pantalla completa. */
    public void eraseInDisplay(int mode) {
        if (mode == 0) {
            fill(cursorRow, cursorCol, rows - 1, cols - 1);
        } else if (mode == 1) {
            fill(0, 0, cursorRow, cursorCol);
        } else if (mode == 2) {
            fill(0, 0, rows - 1, cols - 1);
        }
    }

    /** Borrado en línea: modo 0=cursor al final, 1=inicio al cursor, 2=línea completa. */
    public void eraseInLine(int mode) {
        if (mode == 0) {
            fill(cursorRow, cursorCol, cursorRow, cols - 1);
        } else if (mode == 1) {
            fill(cursorRow, 0, cursorRow, cursorCol);
        } else if (mode == 2) {
            fill(cursorRow, 0, cursorRow, cols - 1);
        }
    }

    /** Borra n caracteres desde el cursor sin moverlo. */
    public void eraseCharacters(int n) {
        int end = Math.min(cols - 1, cursorCol + n - 1);
        fill(cursorRow, cursorCol, cursorRow, end);
    }

    /** Elimina n caracteres en el cursor (los restantes se desplazan a la izquierda, el hueco se rellena con espacios). */
    public void deleteCharacters(int n) {
        n = Math.max(1, n);
        int srcStart = index(cursorRow, cursorCol + n);
        int dstStart = index(cursorRow, cursorCol);
        int count    = cols - cursorCol - n;
        if (count > 0) {
            System.arraycopy(chars, srcStart, chars, dstStart, count);
            Arrays.fill(chars, dstStart + count, dstStart + count + n, ' ');
        } else {
            fill(cursorRow, cursorCol, cursorRow, cols - 1);
        }
    }

    /** Inserta n caracteres en blanco en el cursor (los existentes se desplazan a la derecha, el desbordamiento se descarta). */
    public void insertCharacters(int n) {
        n = Math.max(1, n);
        int srcStart = index(cursorRow, cursorCol);
        int dstStart = index(cursorRow, cursorCol + n);
        int count    = cols - cursorCol - n;
        if (count > 0) {
            System.arraycopy(chars, srcStart, chars, dstStart, count);
        }
        fill(cursorRow, cursorCol, cursorRow, cursorCol + n - 1);
    }

    // ── Operaciones de scroll ─────────────────────────────────────────────────

    /** Define la región de scroll vertical (1-based, inclusivo). */
    public void setScrollRegion(int top, int bottom) {
        this.scrollTop    = clampRow(top - 1);
        this.scrollBottom = clampRow(bottom - 1);
    }

    /** Desplaza el contenido de la región de scroll hacia arriba n líneas; las nuevas líneas quedan en blanco. */
    public void scrollUp(int n) {
        n = Math.max(1, n);
        int regionRows = scrollBottom - scrollTop + 1;
        if (n >= regionRows) {
            fill(scrollTop, 0, scrollBottom, cols - 1);
            return;
        }
        int srcRow = scrollTop + n;
        int dstRow = scrollTop;
        int count  = (regionRows - n) * cols;
        System.arraycopy(chars, index(srcRow, 0), chars, index(dstRow, 0), count);
        fill(scrollBottom - n + 1, 0, scrollBottom, cols - 1);
    }

    /** Desplaza el contenido de la región de scroll hacia abajo n líneas; las nuevas líneas en blanco aparecen arriba. */
    public void scrollDown(int n) {
        n = Math.max(1, n);
        int regionRows = scrollBottom - scrollTop + 1;
        if (n >= regionRows) {
            fill(scrollTop, 0, scrollBottom, cols - 1);
            return;
        }
        int srcRow = scrollTop;
        int dstRow = scrollTop + n;
        int count  = (regionRows - n) * cols;
        System.arraycopy(chars, index(srcRow, 0), chars, index(dstRow, 0), count);
        fill(scrollTop, 0, scrollTop + n - 1, cols - 1);
    }

    /** Inserta n líneas en blanco en la fila del cursor, desplazando el contenido hacia abajo. */
    public void insertLines(int n) {
        n = Math.max(1, n);
        int regionRows = scrollBottom - cursorRow + 1;
        if (n >= regionRows) {
            fill(cursorRow, 0, scrollBottom, cols - 1);
        } else {
            int src = index(cursorRow, 0);
            int dst = index(cursorRow + n, 0);
            System.arraycopy(chars, src, chars, dst, (regionRows - n) * cols);
            fill(cursorRow, 0, cursorRow + n - 1, cols - 1);
        }
    }

    /** Elimina n líneas en la fila del cursor, desplazando el contenido hacia arriba. */
    public void deleteLines(int n) {
        n = Math.max(1, n);
        int regionRows = scrollBottom - cursorRow + 1;
        if (n >= regionRows) {
            fill(cursorRow, 0, scrollBottom, cols - 1);
        } else {
            int src = index(cursorRow + n, 0);
            int dst = index(cursorRow, 0);
            System.arraycopy(chars, src, chars, dst, (regionRows - n) * cols);
            fill(scrollBottom - n + 1, 0, scrollBottom, cols - 1);
        }
    }

    // ── Atributos SGR ─────────────────────────────────────────────────────────

    /** Aplica los parámetros SGR (Select Graphic Rendition). */
    public void applySgr(int[] params) {
        if (params.length == 0) {
            currentAttr = 0;
            return;
        }
        for (int p : params) {
            switch (p) {
                case 0:  currentAttr = 0;                      break;
                case 1:  currentAttr |= ATTR_BOLD;             break;
                case 4:  currentAttr |= ATTR_UNDERLINE;        break;
                case 5:  currentAttr |= ATTR_BLINK;            break;
                case 7:  currentAttr |= ATTR_REVERSE;          break;
                case 22: currentAttr &= ~ATTR_BOLD;            break;
                case 24: currentAttr &= ~ATTR_UNDERLINE;       break;
                case 25: currentAttr &= ~ATTR_BLINK;           break;
                case 27: currentAttr &= ~ATTR_REVERSE;         break;
                // 30-37: color de frente; 40-47: color de fondo — se almacenan pero no se renderizan
                default: break;
            }
        }
    }

    // ── Flags de modo ─────────────────────────────────────────────────────────

    public void setAutoWrap(boolean on) { this.autoWrap = on; }

    // ── Reset completo ────────────────────────────────────────────────────────

    public void reset() {
        Arrays.fill(chars, ' ');
        Arrays.fill(attrs, 0);
        cursorRow = 0;
        cursorCol = 0;
        scrollTop    = 0;
        scrollBottom = rows - 1;
        currentAttr  = 0;
        autoWrap     = true;
    }

    // ── Accesores ─────────────────────────────────────────────────────────────

    public int rows()      { return rows; }
    public int cols()      { return cols; }
    public int cursorRow() { return cursorRow; } // 0-based
    public int cursorCol() { return cursorCol; } // 0-based

    /** Retorna una fila como string (fila 0-based), con espacios finales preservados. */
    public String getLine(int row) {
        return new String(chars, row * cols, cols);
    }

    /**
     * Toma una instantánea inmutable del estado actual de la pantalla.
     * Es seguro pasarla a otros hilos y retenerla después de más mutaciones.
     */
    public ScreenSnapshot snapshot() {
        char[] copy = Arrays.copyOf(chars, chars.length);
        int[]  acp  = Arrays.copyOf(attrs, attrs.length);
        return new ScreenSnapshot(copy, acp, rows, cols, cursorRow + 1, cursorCol + 1);
    }

    // ── Internos ──────────────────────────────────────────────────────────────

    private int index(int row, int col) {
        return row * cols + col;
    }

    private int clampRow(int r) { return Math.max(0, Math.min(rows - 1, r)); }
    private int clampCol(int c) { return Math.max(0, Math.min(cols - 1, c)); }

    /** Rellena la región rectangular con espacios y resetea los atributos. */
    private void fill(int r1, int c1, int r2, int c2) {
        for (int r = r1; r <= r2; r++) {
            int colFrom = (r == r1) ? c1 : 0;
            int colTo   = (r == r2) ? c2 : cols - 1;
            int start   = index(r, colFrom);
            int len     = colTo - colFrom + 1;
            if (len > 0) {
                Arrays.fill(chars, start, start + len, ' ');
                Arrays.fill(attrs, start, start + len, 0);
            }
        }
    }
}
