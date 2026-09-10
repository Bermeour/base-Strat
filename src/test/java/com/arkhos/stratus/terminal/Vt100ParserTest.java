package com.arkhos.stratus.terminal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Vt100Parser} — sequences → buffer state.
 * No network, no mock server needed.
 */
class Vt100ParserTest {

    private ScreenBuffer buffer;
    private Vt100Parser  parser;

    @BeforeEach
    void setUp() {
        buffer = new ScreenBuffer(24, 80);
        parser = new Vt100Parser(buffer, "ISO-8859-1");
    }

    // -------------------------------------------------------------------------
    // Printable characters
    // -------------------------------------------------------------------------

    @Test
    void plainTextAppearsOnFirstRow() {
        feed("Hello");
        assertEquals("Hello", snapshot().getText(1, 1, 5));
    }

    @Test
    void carriageReturnMovesToColumnOne() {
        feed("Hello\rWorld");
        // CR moves to col 0, then "World" overwrites from col 0
        assertEquals("World", snapshot().getText(1, 1, 5));
    }

    @Test
    void lineFeedAdvancesRow() {
        // VT100 LF only moves down (no CR), so use CR+LF to start at column 1
        feed("Line1\r\nLine2");
        assertEquals("Line1", snapshot().getText(1, 1, 5));
        assertEquals("Line2", snapshot().getText(2, 1, 5));
    }

    @Test
    void backspaceMovesLeft() {
        feed("Helloo\bWorld");
        // 'o' then BS, then 'World' — cursor goes back one, World overwrites
        String line = snapshot().getLine(1);
        assertTrue(line.startsWith("HelloWorld"), "Expected HelloWorld at start, got: " + line);
    }

    @Test
    void tabAdvancesToNextTabStop() {
        feed("AB\tC");
        // Tab at col 2 should advance to col 8 (next 8-col boundary)
        ScreenSnapshot snap = snapshot();
        assertEquals('A', snap.charAt(1, 1));
        assertEquals('B', snap.charAt(1, 2));
        assertEquals('C', snap.charAt(1, 9));
    }

    // -------------------------------------------------------------------------
    // Cursor positioning (CSI H)
    // -------------------------------------------------------------------------

    @Test
    void cursorPositionAbsolute() {
        feed("\033[5;10HX");
        assertEquals('X', snapshot().charAt(5, 10));
    }

    @Test
    void cursorHomeWithNoParams() {
        feed("ABC");                   // puts cursor at (1,4)
        feed("\033[H");               // ESC [ H = home (1,1)
        feed("Z");
        assertEquals('Z', snapshot().charAt(1, 1));
    }

    @Test
    void cursorPositionDefaultsToOne() {
        // ESC [ ; H — no params → row=1, col=1
        feed("MOVE\033[;HZ");
        assertEquals('Z', snapshot().charAt(1, 1));
    }

    // -------------------------------------------------------------------------
    // Cursor movement (CSI A B C D)
    // -------------------------------------------------------------------------

    @Test
    void cursorUp() {
        feed("\033[5;5H");  // go to (5,5)
        feed("\033[2A");    // up 2
        assertEquals(3, buffer.cursorRow() + 1); // 1-based = 3
    }

    @Test
    void cursorDown() {
        feed("\033[3;1H");  // go to (3,1)
        feed("\033[3B");    // down 3
        assertEquals(6, buffer.cursorRow() + 1);
    }

    @Test
    void cursorRight() {
        feed("\033[1;1H");  // (1,1)
        feed("\033[5C");    // right 5
        assertEquals(6, buffer.cursorCol() + 1);
    }

    @Test
    void cursorLeft() {
        feed("\033[1;10H"); // (1,10)
        feed("\033[3D");    // left 3
        assertEquals(7, buffer.cursorCol() + 1);
    }

    // -------------------------------------------------------------------------
    // Erase in display (CSI J)
    // -------------------------------------------------------------------------

    @Test
    void eraseToEndOfDisplay() {
        // Place known content on two rows
        feed("\033[1;1HLine1");
        feed("\033[2;1HLine2");
        // Position cursor after "Line1" (col 6 on row 1) and erase to end of screen
        feed("\033[1;6H");
        feed("\033[J");    // ESC[J = ESC[0J = erase from cursor to end

        ScreenSnapshot snap = snapshot();
        // "Line1" before the cursor is intact
        assertEquals("Line1", snap.getText(1, 1, 5));
        // Cursor position and beyond (col 6+) on row 1 are spaces
        assertEquals(' ', snap.charAt(1, 6));
        // All of row 2 is now blank
        assertEquals(' ', snap.charAt(2, 1));
        assertEquals(' ', snap.charAt(2, 40));
    }

    @Test
    void eraseEntireScreen() {
        feed("ABCDEF");
        feed("\033[2J");    // erase entire display
        ScreenSnapshot snap = snapshot();
        // Every cell should be space
        for (int r = 1; r <= 24; r++) {
            assertEquals(new String(new char[80]).replace('\0', ' '), snap.getLine(r),
                    "Row " + r + " should be blank after ESC[2J");
        }
    }

    // -------------------------------------------------------------------------
    // Erase in line (CSI K)
    // -------------------------------------------------------------------------

    @Test
    void eraseToEndOfLine() {
        feed("Hello World");
        feed("\033[1;6H");  // position to col 6
        feed("\033[K");     // erase to end of line
        ScreenSnapshot snap = snapshot();
        assertEquals("Hello", snap.getText(1, 1, 5));
        assertEquals(' ', snap.charAt(1, 6));
        assertEquals(' ', snap.charAt(1, 11));
    }

    @Test
    void eraseEntireLine() {
        feed("Hello World");
        feed("\033[1;1H");  // col 1
        feed("\033[2K");    // erase entire line
        ScreenSnapshot snap = snapshot();
        assertEquals(new String(new char[80]).replace('\0', ' '), snap.getLine(1));
    }

    // -------------------------------------------------------------------------
    // Scrolling
    // -------------------------------------------------------------------------

    @Test
    void scrollUpWhenCursorAtBottom() {
        // Fill first 24 rows
        for (int i = 1; i <= 24; i++) {
            feed("\033[" + i + ";1H");
            feed("Row" + i);
        }
        // Cursor is at row 24 — another LF should scroll
        feed("\n");
        ScreenSnapshot snap = snapshot();
        // Row 1 should now contain what was row 2
        assertTrue(snap.getLine(1).contains("Row2"), "Row1 after scroll: " + snap.getLine(1));
        // Last row should be blank
        assertEquals(new String(new char[80]).replace('\0', ' '), snap.getLine(24));
    }

    // -------------------------------------------------------------------------
    // SGR attributes (CSI m)
    // -------------------------------------------------------------------------

    @Test
    void sgrResetClearsAttributes() {
        // Just verify no exception and parser continues working
        feed("\033[1mBold\033[0mNormal");
        ScreenSnapshot snap = snapshot();
        assertEquals("Bold", snap.getText(1, 1, 4));
        assertEquals("Normal", snap.getText(1, 5, 6));
    }

    // -------------------------------------------------------------------------
    // Save / restore cursor (ESC 7 / 8)
    // -------------------------------------------------------------------------

    @Test
    void saveThenRestoreCursor() {
        feed("\033[10;20H");   // position to (10,20)
        feed("\0337");         // save cursor
        feed("\033[1;1H");     // move elsewhere
        feed("\0338");         // restore cursor
        assertEquals(10, buffer.cursorRow() + 1);
        assertEquals(20, buffer.cursorCol() + 1);
    }

    // -------------------------------------------------------------------------
    // Full reset (ESC c)
    // -------------------------------------------------------------------------

    @Test
    void fullResetClearsScreen() {
        feed("Some content on screen");
        feed("\033c");         // full reset
        ScreenSnapshot snap = snapshot();
        for (int r = 1; r <= 24; r++) {
            assertEquals(new String(new char[80]).replace('\0', ' '), snap.getLine(r));
        }
        assertEquals(1, snap.cursorRow());
        assertEquals(1, snap.cursorCol());
    }

    // -------------------------------------------------------------------------
    // OSC sequences
    // -------------------------------------------------------------------------

    @Test
    void oscSequenceIsSilentlyConsumed() {
        // Title-setting OSC sequence — should not corrupt buffer
        feed("\033]0;My Window Title\007Hello");
        assertEquals("Hello", snapshot().getText(1, 1, 5));
    }

    // -------------------------------------------------------------------------
    // Delete / insert characters
    // -------------------------------------------------------------------------

    @Test
    void deleteCharacters() {
        feed("ABCDE");
        feed("\033[1;2H");  // position at col 2 ('B')
        feed("\033[2P");    // delete 2 chars (B, C) → "ADEZ..."
        ScreenSnapshot snap = snapshot();
        assertEquals('A', snap.charAt(1, 1));
        assertEquals('D', snap.charAt(1, 2));
        assertEquals('E', snap.charAt(1, 3));
        assertEquals(' ', snap.charAt(1, 4));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void feed(String s) {
        byte[] bytes;
        try { bytes = s.getBytes("ISO-8859-1"); }
        catch (Exception e) { bytes = s.getBytes(); }
        parser.feed(bytes, 0, bytes.length);
    }

    private ScreenSnapshot snapshot() {
        return buffer.snapshot();
    }
}
