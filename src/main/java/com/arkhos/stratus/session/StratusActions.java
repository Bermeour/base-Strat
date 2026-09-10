package com.arkhos.stratus.session;

import com.arkhos.stratus.terminal.TerminalKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Operaciones de envío de datos hacia el host Stratus VOS.
 *
 * <p>Centraliza todo lo que implica <em>escribir</em> en la sesión:
 * texto libre, teclas especiales VT100/ANSI y bytes crudos.
 * La fachada {@link StratusSession} delega aquí y retorna {@code this}
 * para permitir encadenamiento.</p>
 *
 * <p>Clase de uso interno del paquete; acceder siempre a través de
 * {@link StratusSession}.</p>
 */
final class StratusActions {

    private static final Logger log = LoggerFactory.getLogger(StratusActions.class);

    private final SessionContext ctx;

    StratusActions(SessionContext ctx) {
        this.ctx = ctx;
    }

    // ── Métodos de envío ─────────────────────────────────────────────────────

    /**
     * Envía una cadena de texto al host codificada con el charset de la sesión.
     * Agrega {@code \r} al final del texto para simular Enter si es necesario.
     */
    void sendText(String texto) throws IOException {
        byte[] bytes = texto.getBytes(ctx.charset);
        sendRaw(bytes);
        log.debug("[ACTIONS] sendText → {} chars", texto.length());
    }

    /**
     * Envía una tecla especial VT100/ANSI (Enter, flechas, F-keys, Ctrl+tecla, etc.).
     *
     * @see TerminalKey
     */
    void sendKey(TerminalKey tecla) throws IOException {
        byte[] bytes = tecla.bytes(ctx.charset);
        sendRaw(bytes);
        log.debug("[ACTIONS] sendKey → {}", tecla);
    }

    /**
     * Envía bytes crudos al host sin ningún tipo de transformación ni codificación.
     * Útil para secuencias de control precisas o diagnóstico de protocolo.
     */
    void sendRaw(byte[] bytes) throws IOException {
        verificarConexion();
        OutputStream out = ctx.outputStream;
        out.write(bytes);
        out.flush();
    }

    // ── Validación ───────────────────────────────────────────────────────────

    private void verificarConexion() throws IOException {
        if (!ctx.connected.get()) {
            throw new IOException("La sesión no está conectada");
        }
    }
}
