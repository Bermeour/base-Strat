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
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * High-level API for a Stratus VOS TELNET session.
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   StratusConfig cfg = StratusConfig.builder("vos-host", 23).build();
 *   try (StratusSession s = new StratusSession(cfg)) {
 *       s.connect();
 *       s.waitForText("login:", 10_000);
 *       s.sendText("admin\r");
 *       s.waitForText("Password:", 5_000);
 *       s.sendText("secret\r");
 *       s.waitForText("$", 10_000);
 *       System.out.println(s.getScreen().getText());
 *   }
 * </pre>
 *
 * <h3>Thread safety</h3>
 * <p>A background reader thread continuously reads from the TELNET socket and updates
 * the internal {@link ScreenBuffer}. Public methods are safe to call from any thread.
 * {@link ScreenSnapshot} objects returned by {@link #getScreen()} are immutable.</p>
 *
 * <h3>Raw capture / debugging</h3>
 * <p>Enable raw-capture mode in {@link StratusConfig.Builder#rawCapture(boolean)} or
 * via {@code -Dstratus.rawCapture=true} to log every received byte in hex before
 * it is parsed. Use this to capture real Stratus VOS traffic for protocol analysis.</p>
 */
public final class StratusSession implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(StratusSession.class);

    private static final int READ_BUF_SIZE = 4096;

    private final StratusConfig    config;
    private final TelnetConnection transport;
    private final ScreenBuffer     buffer;
    private final Vt100Parser      parser;

    private final List<SessionListener> listeners = new CopyOnWriteArrayList<SessionListener>();
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /** Shared monitor for waitForText / waitForPattern coordination. */
    private final Object screenLock = new Object();

    /** Latest snapshot, updated after every read burst. Volatile for visibility. */
    private volatile ScreenSnapshot lastSnapshot;

    private Thread readerThread;

    public StratusSession(StratusConfig config) {
        this.config    = config;
        this.transport = new TelnetConnection(config);
        this.buffer    = new ScreenBuffer(config.rows(), config.cols());
        this.parser    = new Vt100Parser(buffer, config.charset());
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Opens the TELNET connection and starts the background reader thread.
     *
     * @throws IOException          if the network connection fails
     * @throws IllegalStateException if already connected
     */
    public void connect() throws IOException {
        if (connected.get()) throw new IllegalStateException("Already connected");
        transport.connect();
        connected.set(true);
        notifyConnected();
        startReaderThread(transport.getInputStream());
        log.info("[SESSION] Ready ({})", config);
    }

    /**
     * Closes the session and releases all resources.
     * Safe to call multiple times.
     */
    public void disconnect() {
        if (connected.compareAndSet(true, false)) {
            transport.disconnect();
            // Wake any threads blocked in waitForText so they can return false
            synchronized (screenLock) {
                screenLock.notifyAll();
            }
            notifyDisconnected();
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    public boolean isConnected() {
        return connected.get();
    }

    // -------------------------------------------------------------------------
    // Output — sending to host
    // -------------------------------------------------------------------------

    /**
     * Sends the text string to the host using the configured charset.
     *
     * <p>Include {@code \r} at the end to simulate pressing Enter.
     * Use {@link #sendKey(TerminalKey)} for special keys like F-keys or arrows.</p>
     */
    public void sendText(String text) throws IOException {
        byte[] bytes = text.getBytes(config.charset());
        sendRaw(bytes);
        log.debug("[SESSION] sendText: {} chars", text.length());
    }

    /**
     * Sends a special key (Enter, arrow, F-key, etc.) to the host.
     */
    public void sendKey(TerminalKey key) throws IOException {
        byte[] bytes = key.bytes(config.charset());
        sendRaw(bytes);
        log.debug("[SESSION] sendKey: {}", key);
    }

    /**
     * Sends raw bytes to the host without any encoding or escaping.
     * Use for low-level control when you know the exact byte sequence.
     */
    public void sendRaw(byte[] bytes) throws IOException {
        checkConnected();
        OutputStream out = transport.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    // -------------------------------------------------------------------------
    // Input — reading the screen
    // -------------------------------------------------------------------------

    /**
     * Returns an immutable snapshot of the current screen state.
     * May return {@code null} before the first update from the host.
     */
    public ScreenSnapshot getScreen() {
        return lastSnapshot;
    }

    /**
     * Blocks until the given text appears anywhere on the screen, or until
     * the timeout elapses.
     *
     * @param text      the text to wait for
     * @param timeoutMs maximum wait time in milliseconds
     * @return {@code true} if the text was found, {@code false} if timed out
     * @throws InterruptedException if the calling thread is interrupted
     */
    public boolean waitForText(String text, long timeoutMs) throws InterruptedException {
        return waitForCondition(new TextCondition(text), timeoutMs);
    }

    /**
     * Blocks until any row on the screen matches the given pattern.
     *
     * @param pattern   compiled regular expression
     * @param timeoutMs maximum wait time in milliseconds
     * @return {@code true} if matched, {@code false} if timed out
     */
    public boolean waitForPattern(Pattern pattern, long timeoutMs) throws InterruptedException {
        return waitForCondition(new PatternCondition(pattern), timeoutMs);
    }

    /**
     * Blocks until the screen has received any update from the host,
     * or until the timeout elapses.
     *
     * @param timeoutMs maximum wait time in milliseconds
     * @return {@code true} if an update was received, {@code false} if timed out
     */
    public boolean waitForUpdate(long timeoutMs) throws InterruptedException {
        ScreenSnapshot before = lastSnapshot;
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (screenLock) {
            while (connected.get()) {
                if (lastSnapshot != null && lastSnapshot != before) return true;
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return false;
                screenLock.wait(remaining);
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Listeners
    // -------------------------------------------------------------------------

    public void addListener(SessionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
    }

    // -------------------------------------------------------------------------
    // Reader thread
    // -------------------------------------------------------------------------

    private void startReaderThread(final InputStream in) {
        readerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop(in);
            }
        }, "stratus-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop(InputStream in) {
        byte[] buf = new byte[READ_BUF_SIZE];
        log.debug("[READER] Started");
        try {
            int n;
            while (connected.get() && (n = in.read(buf)) != -1) {
                parser.feed(buf, 0, n);
                ScreenSnapshot snap = buffer.snapshot();
                synchronized (screenLock) {
                    lastSnapshot = snap;
                    screenLock.notifyAll();
                }
                notifyScreenUpdated(snap);
            }
        } catch (IOException e) {
            if (connected.get()) {
                log.warn("[READER] I/O error: {}", e.getMessage());
                notifyError(e);
            }
        } finally {
            log.debug("[READER] Stopped");
            disconnect();
        }
    }

    // -------------------------------------------------------------------------
    // Wait helper
    // -------------------------------------------------------------------------

    private interface ScreenCondition {
        boolean test(ScreenSnapshot snap);
    }

    private static final class TextCondition implements ScreenCondition {
        private final String text;
        TextCondition(String text) { this.text = text; }
        public boolean test(ScreenSnapshot snap) { return snap.containsText(text); }
    }

    private static final class PatternCondition implements ScreenCondition {
        private final Pattern pattern;
        PatternCondition(Pattern pattern) { this.pattern = pattern; }
        public boolean test(ScreenSnapshot snap) { return snap.matchesPattern(pattern); }
    }

    private boolean waitForCondition(ScreenCondition cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (screenLock) {
            while (connected.get()) {
                if (lastSnapshot != null && cond.test(lastSnapshot)) return true;
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return false;
                screenLock.wait(remaining);
            }
        }
        // connection dropped — check one last snapshot
        ScreenSnapshot snap = lastSnapshot;
        return snap != null && cond.test(snap);
    }

    // -------------------------------------------------------------------------
    // Listener notifications
    // -------------------------------------------------------------------------

    private void notifyConnected() {
        for (SessionListener l : listeners) {
            try { l.onConnected(); } catch (Exception e) { log.warn("[LISTENER] onConnected threw", e); }
        }
    }

    private void notifyDisconnected() {
        for (SessionListener l : listeners) {
            try { l.onDisconnected(); } catch (Exception e) { log.warn("[LISTENER] onDisconnected threw", e); }
        }
    }

    private void notifyScreenUpdated(ScreenSnapshot snap) {
        for (SessionListener l : listeners) {
            try { l.onScreenUpdated(snap); } catch (Exception e) { log.warn("[LISTENER] onScreenUpdated threw", e); }
        }
    }

    private void notifyError(Exception e) {
        for (SessionListener l : listeners) {
            try { l.onError(e); } catch (Exception ex) { log.warn("[LISTENER] onError threw", ex); }
        }
    }

    // -------------------------------------------------------------------------
    // Guard
    // -------------------------------------------------------------------------

    private void checkConnected() throws IOException {
        if (!connected.get()) throw new IOException("Session is not connected");
    }
}
