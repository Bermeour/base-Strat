package com.arkhos.stratus.session;

import com.arkhos.stratus.terminal.ScreenSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * Operaciones de espera sobre el contenido de la pantalla.
 *
 * <p>Centraliza toda la lógica de sincronización: bloquea el hilo llamador
 * mediante {@link Object#wait(long)} y se despierta cada vez que el hilo
 * lector notifica un nuevo snapshot mediante {@link Object#notifyAll()}.</p>
 *
 * <p>Cada método retorna {@code true} si la condición se cumplió dentro
 * del tiempo indicado, o {@code false} si se agotó el timeout.
 * La fachada {@link StratusSession} convierte {@code false} en
 * {@link StratusTimeoutException} para permitir el encadenamiento sin
 * verificaciones manuales.</p>
 *
 * <p>Clase de uso interno del paquete; acceder siempre a través de
 * {@link StratusSession}.</p>
 */
final class StratusWaiter {

    private static final Logger log = LoggerFactory.getLogger(StratusWaiter.class);

    private final SessionContext ctx;

    StratusWaiter(SessionContext ctx) {
        this.ctx = ctx;
    }

    // ── Métodos de espera ────────────────────────────────────────────────────

    /**
     * Bloquea hasta que el texto aparezca en cualquier fila de la pantalla,
     * o hasta que se agote {@code timeoutMs}.
     */
    boolean waitForText(String texto, long timeoutMs) throws InterruptedException {
        log.debug("[WAITER] Esperando texto: \"{}\" (max {} ms)", texto, timeoutMs);
        return esperar(new CondicionTexto(texto), timeoutMs);
    }

    /**
     * Bloquea hasta que alguna fila de la pantalla coincida con el patrón regex,
     * o hasta que se agote {@code timeoutMs}.
     */
    boolean waitForPattern(Pattern patron, long timeoutMs) throws InterruptedException {
        log.debug("[WAITER] Esperando patron: {} (max {} ms)", patron, timeoutMs);
        return esperar(new CondicionPatron(patron), timeoutMs);
    }

    /**
     * Bloquea hasta que el texto aparezca en la fila indicada (1-based),
     * o hasta que se agote {@code timeoutMs}.
     */
    boolean waitForTextInRow(String texto, int fila, long timeoutMs) throws InterruptedException {
        log.debug("[WAITER] Esperando texto: \"{}\" en fila {} (max {} ms)", texto, fila, timeoutMs);
        return esperar(new CondicionTextoEnFila(texto, fila), timeoutMs);
    }

    /**
     * Bloquea hasta que el host envíe cualquier actualización de pantalla
     * distinta a la que había antes de llamar al método.
     * Útil para esperar el primer contenido tras la conexión.
     */
    boolean waitForUpdate(long timeoutMs) throws InterruptedException {
        log.debug("[WAITER] Esperando cualquier actualizacion (max {} ms)", timeoutMs);
        ScreenSnapshot anterior = ctx.lastSnapshot.get();
        long limite   = System.currentTimeMillis() + timeoutMs;
        long settleMs = ctx.settleMs;

        synchronized (ctx.screenLock) {
            while (ctx.connected.get()) {
                ScreenSnapshot actual = ctx.lastSnapshot.get();
                if (actual != null && actual != anterior) {
                    if (settleMs <= 0) return true;
                    if (esperarQuietud(settleMs, limite)) return true;
                    anterior = ctx.lastSnapshot.get(); // actualizar referencia tras quietud
                    continue;
                }
                long restante = limite - System.currentTimeMillis();
                if (restante <= 0) return false;
                ctx.screenLock.wait(restante);
            }
        }
        // La conexión cayó: revisamos una última vez
        ScreenSnapshot actual = ctx.lastSnapshot.get();
        return actual != null && actual != anterior;
    }

    // ── Núcleo de espera ─────────────────────────────────────────────────────

    private boolean esperar(CondicionPantalla condicion, long timeoutMs)
            throws InterruptedException {
        long limite   = System.currentTimeMillis() + timeoutMs;
        long settleMs = ctx.settleMs;

        synchronized (ctx.screenLock) {
            while (ctx.connected.get()) {
                ScreenSnapshot snap = ctx.lastSnapshot.get();
                if (snap != null && condicion.cumplida(snap)) {
                    if (settleMs <= 0) return true;
                    // Esperar hasta que no llegue ningún update nuevo por settleMs
                    if (esperarQuietud(settleMs, limite)) return true;
                    // Si hubo más updates, volver al bucle principal
                    continue;
                }
                long restante = limite - System.currentTimeMillis();
                if (restante <= 0) return false;
                ctx.screenLock.wait(restante);
            }
        }
        // La conexión cayó: revisamos el último snapshot disponible
        ScreenSnapshot snap = ctx.lastSnapshot.get();
        return snap != null && condicion.cumplida(snap);
    }

    /**
     * Espera hasta que no llegue ningún update durante {@code settleMs} ms.
     * Debe llamarse dentro de {@code synchronized(ctx.screenLock)}.
     * Retorna {@code true} si se alcanzó la quietud, {@code false} si venció el timeout total.
     */
    private boolean esperarQuietud(long settleMs, long limiteTotal)
            throws InterruptedException {
        ScreenSnapshot snapAntes = ctx.lastSnapshot.get();
        long limiteSettle = System.currentTimeMillis() + settleMs;

        while (true) {
            long restanteSettle = limiteSettle - System.currentTimeMillis();
            long restanteTotal  = limiteTotal  - System.currentTimeMillis();
            if (restanteTotal <= 0) return false;
            if (restanteSettle <= 0) return true; // pantalla quieta durante settleMs

            ctx.screenLock.wait(Math.min(restanteSettle, restanteTotal));

            ScreenSnapshot snapAhora = ctx.lastSnapshot.get();
            if (snapAhora != snapAntes) {
                // Llegó un update nuevo — reiniciar el contador de quietud
                snapAntes    = snapAhora;
                limiteSettle = System.currentTimeMillis() + settleMs;
            }
        }
    }

    // ── Condiciones internas ─────────────────────────────────────────────────

    /** Contrato que deben cumplir todas las condiciones de espera. */
    interface CondicionPantalla {
        boolean cumplida(ScreenSnapshot snap);
    }

    private static final class CondicionTexto implements CondicionPantalla {
        private final String texto;
        CondicionTexto(String texto) { this.texto = texto; }
        public boolean cumplida(ScreenSnapshot snap) { return snap.containsText(texto); }
    }

    private static final class CondicionTextoEnFila implements CondicionPantalla {
        private final String texto;
        private final int    fila;
        CondicionTextoEnFila(String texto, int fila) { this.texto = texto; this.fila = fila; }
        public boolean cumplida(ScreenSnapshot snap) { return snap.containsTextInRow(texto, fila); }
    }

    private static final class CondicionPatron implements CondicionPantalla {
        private final Pattern patron;
        CondicionPatron(Pattern patron) { this.patron = patron; }
        public boolean cumplida(ScreenSnapshot snap) { return snap.matchesPattern(patron); }
    }
}
