package com.arkhos.stratus.terminal;

import java.util.Arrays;

/**
 * Mutable VT100/ANSI screen buffer.
 *
 * <p>Coordinates are 0-based internally. The public {@link ScreenSnapshot} API
 * exposes 1-based row/col to match common terminal conventions.</p>
 *
 * <p>All mutations happen on the reader thread; callers that need a thread-safe
 * view must call {@link #snapshot()} which returns an immutable copy.</p>
 */
public final class ScreenBuffer {

    private final int rows;
    private final int cols;

    private final char[] chars;   // row-major: chars[r*cols+c]
    private final int[]  attrs;   // SGR attribute bitmask per cell

    private int cursorRow;  // 0-based
    private int cursorCol;  // 0-based

    private int savedCursorRow;
    private int savedCursorCol;

    private int scrollTop;    // 0-based inclusive
    private int scrollBottom; // 0-based inclusive

    private boolean autoWrap = true;

    // SGR attribute bits
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

    // -------------------------------------------------------------------------
    // Cursor movement
    // -------------------------------------------------------------------------

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
        // advance to next tab stop (every 8 cols)
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

    // -------------------------------------------------------------------------
    // Character output
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Erase operations
    // -------------------------------------------------------------------------

    /** Erase in display: mode 0=cursor to end, 1=start to cursor, 2=entire screen. */
    public void eraseInDisplay(int mode) {
        if (mode == 0) {
            fill(cursorRow, cursorCol, rows - 1, cols - 1);
        } else if (mode == 1) {
            fill(0, 0, cursorRow, cursorCol);
        } else if (mode == 2) {
            fill(0, 0, rows - 1, cols - 1);
        }
    }

    /** Erase in line: mode 0=cursor to end, 1=start to cursor, 2=entire line. */
    public void eraseInLine(int mode) {
        if (mode == 0) {
            fill(cursorRow, cursorCol, cursorRow, cols - 1);
        } else if (mode == 1) {
            fill(cursorRow, 0, cursorRow, cursorCol);
        } else if (mode == 2) {
            fill(cursorRow, 0, cursorRow, cols - 1);
        }
    }

    /** Erase n characters starting at cursor without moving cursor. */
    public void eraseCharacters(int n) {
        int end = Math.min(cols - 1, cursorCol + n - 1);
        fill(cursorRow, cursorCol, cursorRow, end);
    }

    /** Delete n characters at cursor (remaining chars shift left, gap filled with spaces). */
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

    /** Insert n blank characters at cursor (existing chars shift right, overflow discarded). */
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

    // -------------------------------------------------------------------------
    // Scroll operations
    // -------------------------------------------------------------------------

    /** Set vertical scrolling region (1-based, inclusive). */
    public void setScrollRegion(int top, int bottom) {
        this.scrollTop    = clampRow(top - 1);
        this.scrollBottom = clampRow(bottom - 1);
    }

    /** Scroll content of scroll region up by n lines; new lines are blank. */
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

    /** Scroll content of scroll region down by n lines; new lines are blank at top. */
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

    /** Insert n blank lines at current cursor row, scrolling content down. */
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

    /** Delete n lines at current cursor row, scrolling content up. */
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

    // -------------------------------------------------------------------------
    // SGR attributes
    // -------------------------------------------------------------------------

    /** Apply SGR (Select Graphic Rendition) parameters. */
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
                // 30-37: foreground color; 40-47: background color — stored but not rendered
                default: break;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Mode flags
    // -------------------------------------------------------------------------

    public void setAutoWrap(boolean on) { this.autoWrap = on; }

    // -------------------------------------------------------------------------
    // Full reset
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int rows()      { return rows; }
    public int cols()      { return cols; }
    public int cursorRow() { return cursorRow; } // 0-based
    public int cursorCol() { return cursorCol; } // 0-based

    /** Returns a line as a string (0-based row), trailing spaces preserved. */
    public String getLine(int row) {
        return new String(chars, row * cols, cols);
    }

    /**
     * Takes an immutable snapshot of the current screen state.
     * Safe to pass to other threads and retain after further mutations.
     */
    public ScreenSnapshot snapshot() {
        char[] copy = Arrays.copyOf(chars, chars.length);
        int[]  acp  = Arrays.copyOf(attrs, attrs.length);
        return new ScreenSnapshot(copy, acp, rows, cols, cursorRow + 1, cursorCol + 1);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private int index(int row, int col) {
        return row * cols + col;
    }

    private int clampRow(int r) { return Math.max(0, Math.min(rows - 1, r)); }
    private int clampCol(int c) { return Math.max(0, Math.min(cols - 1, c)); }

    /** Fill rectangular region with spaces and reset attributes. */
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
