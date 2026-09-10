package com.arkhos.stratus.terminal;

import java.util.regex.Pattern;

/**
 * Immutable snapshot of the screen at a point in time.
 * Thread-safe: may be read from any thread after construction.
 *
 * <p>Row and column indices are <strong>1-based</strong> in the public API,
 * matching the convention used by terminal documentation and automation scripts.</p>
 */
public final class ScreenSnapshot {

    private final char[] chars;  // row-major, length = rows*cols
    private final int[]  attrs;
    private final int rows;
    private final int cols;
    private final int cursorRow; // 1-based
    private final int cursorCol; // 1-based

    ScreenSnapshot(char[] chars, int[] attrs, int rows, int cols,
                   int cursorRow, int cursorCol) {
        this.chars     = chars;
        this.attrs     = attrs;
        this.rows      = rows;
        this.cols      = cols;
        this.cursorRow = cursorRow;
        this.cursorCol = cursorCol;
    }

    // -------------------------------------------------------------------------
    // Dimensions & cursor
    // -------------------------------------------------------------------------

    public int rows()      { return rows; }
    public int cols()      { return cols; }

    /** Cursor row, 1-based. */
    public int cursorRow() { return cursorRow; }

    /** Cursor column, 1-based. */
    public int cursorCol() { return cursorCol; }

    // -------------------------------------------------------------------------
    // Single-cell access
    // -------------------------------------------------------------------------

    /**
     * Returns the character at the given 1-based position.
     * Returns {@code ' '} if coordinates are out of bounds.
     */
    public char charAt(int row, int col) {
        if (row < 1 || row > rows || col < 1 || col > cols) return ' ';
        return chars[(row - 1) * cols + (col - 1)];
    }

    /** SGR attribute bitmask at the given 1-based position (see {@link ScreenBuffer} constants). */
    public int attrAt(int row, int col) {
        if (row < 1 || row > rows || col < 1 || col > cols) return 0;
        return attrs[(row - 1) * cols + (col - 1)];
    }

    // -------------------------------------------------------------------------
    // Line and region access
    // -------------------------------------------------------------------------

    /**
     * Returns the full content of a row (1-based) as a string.
     * Trailing spaces are preserved; the string is always {@link #cols()} characters long.
     */
    public String getLine(int row) {
        if (row < 1 || row > rows) return "";
        return new String(chars, (row - 1) * cols, cols);
    }

    /**
     * Returns a substring within a row.
     *
     * @param row    1-based row
     * @param col    1-based starting column
     * @param length number of characters to read
     */
    public String getText(int row, int col, int length) {
        if (row < 1 || row > rows) return "";
        int c0  = Math.max(0, col - 1);
        int len = Math.min(length, cols - c0);
        if (len <= 0) return "";
        return new String(chars, (row - 1) * cols + c0, len);
    }

    /**
     * Returns the trimmed text from a region.
     * Useful for reading field values that may be padded with spaces.
     */
    public String getTextTrimmed(int row, int col, int length) {
        return getText(row, col, length).trim();
    }

    /**
     * Full screen content as a single string with newlines between rows.
     * Trailing spaces on each line are not stripped, preserving column alignment.
     */
    public String getText() {
        StringBuilder sb = new StringBuilder((cols + 1) * rows);
        for (int r = 1; r <= rows; r++) {
            sb.append(getLine(r));
            if (r < rows) sb.append('\n');
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the given text appears anywhere on the screen.
     * The search is performed on the flat screen buffer (spans only within rows,
     * not across row boundaries).
     */
    public boolean containsText(String text) {
        for (int r = 1; r <= rows; r++) {
            if (getLine(r).contains(text)) return true;
        }
        return false;
    }

    /**
     * Returns the 1-based row number of the first row containing {@code text},
     * or {@code -1} if not found.
     */
    public int rowOf(String text) {
        for (int r = 1; r <= rows; r++) {
            if (getLine(r).contains(text)) return r;
        }
        return -1;
    }

    /**
     * Returns {@code true} if any row matches the given regular expression.
     */
    public boolean matchesPattern(Pattern pattern) {
        for (int r = 1; r <= rows; r++) {
            if (pattern.matcher(getLine(r)).find()) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Debug
    // -------------------------------------------------------------------------

    /**
     * Renders the screen as a bordered ASCII block, useful for logging and tests.
     */
    public String toDebugString() {
        StringBuilder sb = new StringBuilder();
        String border = new String(new char[cols + 2]).replace('\0', '-');
        sb.append('+').append(border).append("+\n");
        for (int r = 1; r <= rows; r++) {
            sb.append('|').append(getLine(r)).append("|\n");
        }
        sb.append('+').append(border).append('+');
        sb.append("\n cursor=(").append(cursorRow).append(',').append(cursorCol).append(')');
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ScreenSnapshot{" + rows + "x" + cols +
               ", cursor=(" + cursorRow + "," + cursorCol + ")}";
    }
}
