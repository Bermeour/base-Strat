package com.arkhos.stratus.session;

import com.arkhos.stratus.config.StratusConfig;
import com.arkhos.stratus.connection.TelnetConnection;
import com.arkhos.stratus.terminal.ScreenBuffer;
import com.arkhos.stratus.terminal.ScreenSnapshot;
import com.arkhos.stratus.terminal.TerminalKey;
import com.arkhos.stratus.terminal.Vt100Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Fachada principal de la sesión Stratus VOS.
 *
 * <p>Orquesta el ciclo de vida de la conexión y delega cada responsabilidad
 * a la clase especializada correspondiente:</p>
 * <ul>
 *   <li>{@link StratusActions} — envío de texto y teclas al host</li>
 *   <li>{@link StratusWaiter}  — esperas sobre el contenido de la pantalla</li>
 *   <li>{@link SessionContext} — estado compartido entre ambas</li>
 * </ul>
 *
 * <p>Todos los métodos de interacción retornan {@code this} para permitir
 * encadenamiento fluente:</p>
 * <pre>
 *   try (StratusSession s = new StratusSession(config)) {
 *       s.connect()
 *        .waitForUpdate(15_000)          // esperar banner inicial
 *        .sendText("login\r")
 *        .waitForText("Username:", 5_000)
 *        .sendText("miusuario\r")
 *        .waitForText("Password:", 5_000)
 *        .sendText("mipassword\r")
 *        .waitForText("$", 10_000);
 *
 *       System.out.println(s.getScreen().getText());
 *   }
 * </pre>
 *
 * <p>Los métodos {@code waitFor*} lanzan {@link StratusTimeoutException}
 * (excepción no comprobada) si se agota el tiempo, en lugar de retornar
 * {@code boolean}, de modo que el encadenamiento no requiere verificaciones
 * manuales en el flujo normal.</p>
 */
public final class StratusSession implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(StratusSession.class);

    private static final int BUFFER_LECTURA = 4096;

    // ── Infraestructura de red y protocolo ───────────────────────────────────
    private final StratusConfig    config;
    private final TelnetConnection transporte;
    private final ScreenBuffer     bufferPantalla;
    private final Vt100Parser      parser;

    // ── Responsabilidades delegadas ──────────────────────────────────────────
    private final SessionContext ctx;
    private final StratusActions actions;
    private final StratusWaiter  waiter;

    private final List<SessionListener> listeners =
            new CopyOnWriteArrayList<SessionListener>();

    private Thread hiloLector;

    public StratusSession(StratusConfig config) {
        this.config         = config;
        this.transporte     = new TelnetConnection(config);
        this.bufferPantalla = new ScreenBuffer(config.rows(), config.cols());
        this.parser         = new Vt100Parser(bufferPantalla, config.charset());
        this.ctx            = new SessionContext(config.charset(), config.settleMs());
        this.actions        = new StratusActions(ctx);
        this.waiter         = new StratusWaiter(ctx);
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    /**
     * Abre la conexión TELNET e inicia el hilo lector en segundo plano.
     * Retorna {@code this} para permitir encadenamiento inmediato.
     *
     * @throws IOException           si la conexión de red falla
     * @throws IllegalStateException si ya estaba conectado
     */
    public StratusSession connect() throws IOException {
        if (ctx.connected.get()) {
            throw new IllegalStateException("La sesión ya está conectada");
        }
        transporte.connect();
        ctx.outputStream = transporte.getOutputStream();
        ctx.connected.set(true);
        notificarConectado();
        iniciarHiloLector(transporte.getInputStream());
        log.info("[SESSION] Lista ({})", config);
        return this;
    }

    /**
     * Cierra la conexión y libera todos los recursos.
     * Seguro de llamar múltiples veces.
     */
    public void disconnect() {
        if (ctx.connected.compareAndSet(true, false)) {
            transporte.disconnect();
            synchronized (ctx.screenLock) {
                ctx.screenLock.notifyAll(); // desbloquea hilos en waitFor*
            }
            notificarDesconectado();
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    /** Indica si la sesión está actualmente conectada al host. */
    public boolean isConnected() {
        return ctx.connected.get();
    }

    // ── Envío de datos (delega a StratusActions) ─────────────────────────────

    /**
     * Envía texto al host con el charset configurado.
     * Incluye {@code \r} al final del texto para simular Enter si es necesario.
     *
     * @return {@code this} para encadenamiento
     */
    public StratusSession sendText(String texto) throws IOException {
        actions.sendText(texto);
        return this;
    }

    /**
     * Envía una tecla especial VT100/ANSI al host
     * (Enter, flechas, F-keys, Ctrl+tecla, Delete, etc.).
     *
     * @return {@code this} para encadenamiento
     * @see TerminalKey
     */
    public StratusSession sendKey(TerminalKey tecla) throws IOException {
        actions.sendKey(tecla);
        return this;
    }

    /**
     * Envía bytes crudos al host sin ninguna transformación.
     * Útil para secuencias de control precisas o diagnóstico de protocolo.
     *
     * @return {@code this} para encadenamiento
     */
    public StratusSession sendRaw(byte[] bytes) throws IOException {
        actions.sendRaw(bytes);
        return this;
    }

    // ── Esperas sobre pantalla (delega a StratusWaiter) ──────────────────────

    /**
     * Bloquea hasta que el texto aparezca en cualquier fila de la pantalla.
     *
     * @param texto     texto a buscar (distinción de mayúsculas)
     * @param timeoutMs tiempo máximo de espera en milisegundos
     * @return {@code this} para encadenamiento
     * @throws StratusTimeoutException si el texto no aparece en el tiempo indicado
     */
    public StratusSession waitForText(String texto, long timeoutMs)
            throws InterruptedException {
        if (!waiter.waitForText(texto, timeoutMs)) {
            throw new StratusTimeoutException(
                    "Texto no encontrado en " + timeoutMs + " ms: \"" + texto + "\"");
        }
        return this;
    }

    /** Igual que {@link #waitForText(String, long)} pero {@code segundos} se expresa en segundos. */
    public StratusSession waitForText(String texto, int segundos)
            throws InterruptedException {
        return waitForText(texto, (long) segundos * 1_000);
    }

    /**
     * Bloquea hasta que alguna fila de la pantalla coincida con el patrón regex.
     *
     * @return {@code this} para encadenamiento
     * @throws StratusTimeoutException si el patrón no coincide en el tiempo indicado
     */
    public StratusSession waitForPattern(Pattern patron, long timeoutMs)
            throws InterruptedException {
        if (!waiter.waitForPattern(patron, timeoutMs)) {
            throw new StratusTimeoutException(
                    "Patrón no encontrado en " + timeoutMs + " ms: " + patron);
        }
        return this;
    }

    /** Igual que {@link #waitForPattern(Pattern, long)} pero {@code segundos} se expresa en segundos. */
    public StratusSession waitForPattern(Pattern patron, int segundos)
            throws InterruptedException {
        return waitForPattern(patron, (long) segundos * 1_000);
    }

    /**
     * Bloquea hasta que el texto aparezca en la fila indicada de la pantalla.
     *
     * <p>Más preciso que {@link #waitForText(String, long)} cuando se sabe exactamente
     * en qué fila debe aparecer un valor (p.ej. el prompt siempre en la fila 24).</p>
     *
     * @param texto     texto a buscar
     * @param fila      fila donde buscar (1-based)
     * @param timeoutMs tiempo máximo de espera en milisegundos
     * @return {@code this} para encadenamiento
     * @throws StratusTimeoutException si el texto no aparece en esa fila en el tiempo indicado
     */
    public StratusSession waitForTextInRow(String texto, int fila, long timeoutMs)
            throws InterruptedException {
        if (!waiter.waitForTextInRow(texto, fila, timeoutMs)) {
            throw new StratusTimeoutException(
                    "Texto no encontrado en fila " + fila + " en " + timeoutMs +
                    " ms: \"" + texto + "\"");
        }
        return this;
    }

    /**
     * Bloquea hasta que el host envíe cualquier actualización de pantalla.
     * Ideal para esperar el banner inicial tras la conexión cuando no se conoce
     * el texto exacto que enviará el host.
     *
     * @return {@code this} para encadenamiento
     * @throws StratusTimeoutException si no llega ninguna actualización en el tiempo indicado
     */
    public StratusSession waitForUpdate(long timeoutMs) throws InterruptedException {
        if (!waiter.waitForUpdate(timeoutMs)) {
            throw new StratusTimeoutException(
                    "No se recibió ninguna actualización del host en " + timeoutMs + " ms");
        }
        return this;
    }

    /** Igual que {@link #waitForUpdate(long)} pero {@code segundos} se expresa en segundos. */
    public StratusSession waitForUpdate(int segundos) throws InterruptedException {
        return waitForUpdate((long) segundos * 1_000);
    }

    // ── Lectura de pantalla ───────────────────────────────────────────────────

    /**
     * Retorna una instantánea inmutable del estado actual de la pantalla.
     * Puede ser {@code null} si aún no se ha recibido ningún dato del host.
     */
    public ScreenSnapshot getScreen() {
        return ctx.lastSnapshot.get();
    }

    // ── Listeners de eventos ──────────────────────────────────────────────────

    /**
     * Registra un listener que recibirá eventos de la sesión
     * (pantalla actualizada, conectado, desconectado, error).
     *
     * @return {@code this} para encadenamiento
     */
    public StratusSession addListener(SessionListener listener) {
        listeners.add(listener);
        return this;
    }

    /** @return {@code this} para encadenamiento */
    public StratusSession removeListener(SessionListener listener) {
        listeners.remove(listener);
        return this;
    }

    // ── Hilo lector ───────────────────────────────────────────────────────────

    private void iniciarHiloLector(final InputStream in) {
        hiloLector = new Thread(new Runnable() {
            public void run() { bucleDeRecepcion(in); }
        }, "stratus-reader");
        hiloLector.setDaemon(true);
        hiloLector.start();
    }

    /**
     * Bucle de recepción que corre en segundo plano.
     * Lee bytes del socket, los parsea con el VT100 parser,
     * actualiza el snapshot y notifica a todos los waiters.
     */
    private void bucleDeRecepcion(InputStream in) {
        byte[] buf = new byte[BUFFER_LECTURA];
        log.debug("[LECTOR] Iniciado");
        try {
            int n;
            while (ctx.connected.get() && (n = in.read(buf)) != -1) {
                parser.feed(buf, 0, n);
                ScreenSnapshot snap = bufferPantalla.snapshot();
                synchronized (ctx.screenLock) {
                    ctx.lastSnapshot.set(snap);
                    ctx.screenLock.notifyAll();
                }
                notificarPantallaActualizada(snap);
            }
        } catch (IOException e) {
            if (ctx.connected.get()) {
                log.warn("[LECTOR] Error de E/S: {}", e.getMessage());
                notificarError(e);
            }
        } finally {
            log.debug("[LECTOR] Detenido");
            disconnect();
        }
    }

    // ── Notificaciones a listeners ────────────────────────────────────────────

    private void notificarConectado() {
        for (SessionListener l : listeners) {
            try { l.onConnected(); }
            catch (Exception e) { log.warn("[LISTENER] onConnected lanzó excepción", e); }
        }
    }

    private void notificarDesconectado() {
        for (SessionListener l : listeners) {
            try { l.onDisconnected(); }
            catch (Exception e) { log.warn("[LISTENER] onDisconnected lanzó excepción", e); }
        }
    }

    private void notificarPantallaActualizada(ScreenSnapshot snap) {
        for (SessionListener l : listeners) {
            try { l.onScreenUpdated(snap); }
            catch (Exception e) { log.warn("[LISTENER] onScreenUpdated lanzó excepción", e); }
        }
    }

    private void notificarError(Exception e) {
        for (SessionListener l : listeners) {
            try { l.onError(e); }
            catch (Exception ex) { log.warn("[LISTENER] onError lanzó excepción", ex); }
        }
    }
}
