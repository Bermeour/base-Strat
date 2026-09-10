package com.arkhos.stratus.terminal;

/**
 * Posición inmutable dentro de una pantalla de terminal.
 *
 * <p>Tanto {@code row} como {@code col} son índices <strong>1-based</strong>,
 * siguiendo la convención de la API pública de {@link ScreenSnapshot}.</p>
 */
public final class ScreenPosition {

    /** Fila (1-based). */
    public final int row;

    /** Columna (1-based). */
    public final int col;

    public ScreenPosition(int row, int col) {
        this.row = row;
        this.col = col;
    }

    @Override
    public String toString() {
        return "(" + row + ", " + col + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ScreenPosition)) return false;
        ScreenPosition p = (ScreenPosition) o;
        return row == p.row && col == p.col;
    }

    @Override
    public int hashCode() {
        return 31 * row + col;
    }
}
