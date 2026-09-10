package com.arkhos.stratus.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Instantánea inmutable de la pantalla en un momento dado.
 * Hilo-segura: puede leerse desde cualquier hilo tras su construcción.
 *
 * <p>Los índices de fila y columna son <strong>1-based</strong> en la API pública,
 * siguiendo la convención usada por la documentación de terminales y los scripts
 * de automatización.</p>
 */
public final class ScreenSnapshot {

    private final char[] chars;  // row-major, longitud = rows*cols
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
    // Dimensiones y cursor
    // -------------------------------------------------------------------------

    public int rows()      { return rows; }
    public int cols()      { return cols; }

    /** Fila del cursor, 1-based. */
    public int cursorRow() { return cursorRow; }

    /** Columna del cursor, 1-based. */
    public int cursorCol() { return cursorCol; }

    // -------------------------------------------------------------------------
    // Acceso a celda individual
    // -------------------------------------------------------------------------

    /**
     * Retorna el carácter en la posición indicada (1-based).
     * Retorna {@code ' '} si las coordenadas están fuera de límites.
     */
    public char charAt(int row, int col) {
        if (row < 1 || row > rows || col < 1 || col > cols) return ' ';
        return chars[(row - 1) * cols + (col - 1)];
    }

    /** Máscara de atributos SGR en la posición indicada (1-based); ver constantes en {@link ScreenBuffer}. */
    public int attrAt(int row, int col) {
        if (row < 1 || row > rows || col < 1 || col > cols) return 0;
        return attrs[(row - 1) * cols + (col - 1)];
    }

    // -------------------------------------------------------------------------
    // Line and region access
    // -------------------------------------------------------------------------

    /**
     * Retorna el contenido completo de una fila (1-based) como string.
     * Los espacios finales se preservan; el string siempre tiene {@link #cols()} caracteres.
     */
    public String getLine(int row) {
        if (row < 1 || row > rows) return "";
        return new String(chars, (row - 1) * cols, cols);
    }

    /**
     * Retorna una subcadena dentro de una fila.
     *
     * @param row    fila (1-based)
     * @param col    columna inicial (1-based)
     * @param length número de caracteres a leer
     */
    public String getText(int row, int col, int length) {
        if (row < 1 || row > rows) return "";
        int c0  = Math.max(0, col - 1);
        int len = Math.min(length, cols - c0);
        if (len <= 0) return "";
        return new String(chars, (row - 1) * cols + c0, len);
    }

    /**
     * Retorna el texto de una región recortado de espacios.
     * Útil para leer valores de campo que pueden estar rellenos con espacios.
     */
    public String getTextTrimmed(int row, int col, int length) {
        return getText(row, col, length).trim();
    }

    /**
     * Contenido completo de la pantalla como un único string con saltos de línea entre filas.
     * Los espacios finales de cada línea no se eliminan, preservando la alineación por columnas.
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
    // Acceso a rangos y regiones
    // -------------------------------------------------------------------------

    /**
     * Retorna las filas en el rango indicado (ambos extremos inclusivos, 1-based).
     * Las filas fuera de límites se omiten silenciosamente.
     * Cada string tiene exactamente {@link #cols()} caracteres (sin recortar).
     */
    public List<String> getLines(int fromRow, int toRow) {
        List<String> result = new ArrayList<String>();
        int f = Math.max(1, fromRow);
        int t = Math.min(rows, toRow);
        for (int r = f; r <= t; r++) {
            result.add(getLine(r));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Extrae una región rectangular de la pantalla.
     *
     * <p>Retorna una lista de strings, una por fila, recortadas al ancho de la región.
     * Coordenadas fuera de límites se ajustan al borde de la pantalla.</p>
     *
     * @param row1 fila inicial (1-based, inclusiva)
     * @param col1 columna inicial (1-based, inclusiva)
     * @param row2 fila final (1-based, inclusiva)
     * @param col2 columna final (1-based, inclusiva)
     */
    public List<String> getRegion(int row1, int col1, int row2, int col2) {
        int r1 = Math.max(1, row1);
        int r2 = Math.min(rows, row2);
        int c1 = Math.max(1, col1);
        int c2 = Math.min(cols, col2);
        int len = Math.max(0, c2 - c1 + 1);
        List<String> result = new ArrayList<String>();
        for (int r = r1; r <= r2; r++) {
            result.add(len > 0 ? getText(r, c1, len) : "");
        }
        return Collections.unmodifiableList(result);
    }

    // -------------------------------------------------------------------------
    // Búsqueda
    // -------------------------------------------------------------------------

    /**
     * Retorna {@code true} si el texto aparece en algún lugar de la pantalla.
     * La búsqueda se realiza fila por fila (no cruza límites de fila).
     */
    public boolean containsText(String text) {
        for (int r = 1; r <= rows; r++) {
            if (getLine(r).contains(text)) return true;
        }
        return false;
    }

    /**
     * Retorna {@code true} si el texto aparece en la fila indicada (1-based).
     */
    public boolean containsTextInRow(String text, int row) {
        return row >= 1 && row <= rows && getLine(row).contains(text);
    }

    /**
     * Retorna el número de fila (1-based) de la primera fila que contiene
     * {@code text}, o {@code -1} si no se encuentra.
     */
    public int rowOf(String text) {
        for (int r = 1; r <= rows; r++) {
            if (getLine(r).contains(text)) return r;
        }
        return -1;
    }

    /**
     * Retorna la columna (1-based) donde empieza {@code text} en la fila indicada,
     * o {@code -1} si no se encuentra o la fila está fuera de límites.
     */
    public int colOf(String text, int row) {
        if (row < 1 || row > rows) return -1;
        int idx = getLine(row).indexOf(text);
        return idx >= 0 ? idx + 1 : -1;
    }

    /**
     * Retorna todas las posiciones (fila, columna) donde empieza {@code text},
     * en orden de fila primero, columna después.
     *
     * <p>Útil para encontrar un campo que puede aparecer en varias filas,
     * o para verificar que un elemento sólo aparece una vez.</p>
     */
    public List<ScreenPosition> findText(String text) {
        List<ScreenPosition> result = new ArrayList<ScreenPosition>();
        for (int r = 1; r <= rows; r++) {
            String line = getLine(r);
            int idx = 0;
            while ((idx = line.indexOf(text, idx)) >= 0) {
                result.add(new ScreenPosition(r, idx + 1));
                idx += text.length();
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Lee el valor que aparece inmediatamente después de una etiqueta en la misma fila.
     *
     * <p>Muy útil para formularios VT100 donde los campos tienen el formato:</p>
     * <pre>
     *   Username:  admin
     *   Status:    Active
     * </pre>
     * <pre>
     *   String user   = snap.getFieldAfter(3, "Username:");  // "admin"
     *   String status = snap.getFieldAfter(4, "Status:");    // "Active"
     * </pre>
     *
     * @param row   fila donde buscar la etiqueta (1-based)
     * @param label texto de la etiqueta
     * @return texto que sigue a la etiqueta en la misma fila, recortado de espacios;
     *         cadena vacía si la etiqueta no se encuentra en esa fila
     */
    public String getFieldAfter(int row, String label) {
        if (row < 1 || row > rows) return "";
        String line = getLine(row);
        int idx = line.indexOf(label);
        if (idx < 0) return "";
        int start = idx + label.length();
        return start < line.length() ? line.substring(start).trim() : "";
    }

    /**
     * Busca la etiqueta en toda la pantalla y retorna el valor que la sigue en
     * la misma fila. Versión conveniente de {@link #getFieldAfter(int, String)}
     * cuando no se conoce la fila exacta.
     *
     * @return texto que sigue a la etiqueta, recortado; cadena vacía si no se encuentra
     */
    public String getFieldAfter(String label) {
        int row = rowOf(label);
        return row > 0 ? getFieldAfter(row, label) : "";
    }

    /**
     * Retorna {@code true} si alguna fila coincide con la expresión regular dada.
     */
    public boolean matchesPattern(Pattern pattern) {
        for (int r = 1; r <= rows; r++) {
            if (pattern.matcher(getLine(r)).find()) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Depuración
    // -------------------------------------------------------------------------

    /**
     * Renderiza la pantalla como un bloque ASCII con bordes, útil para logs y tests.
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
