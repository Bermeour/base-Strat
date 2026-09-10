package com.arkhos.stratus.connection;

import com.arkhos.stratus.config.StratusConfig;
import org.apache.commons.net.telnet.EchoOptionHandler;
import org.apache.commons.net.telnet.SuppressGAOptionHandler;
import org.apache.commons.net.telnet.TelnetClient;
import org.apache.commons.net.telnet.TerminalTypeOptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * TELNET transport layer for a Stratus VOS connection.
 *
 * <p>Wraps Apache Commons Net {@link TelnetClient} to handle the TELNET IAC
 * negotiation (RFC 854/855/856) automatically. After {@link #connect()} the caller
 * receives clean {@link InputStream} / {@link OutputStream} streams with no IAC
 * bytes — those are handled transparently by Commons Net.</p>
 *
 * <p>Negotiated options:</p>
 * <ul>
 *   <li>TERMINAL-TYPE — we announce the type from {@link StratusConfig#terminalType()}</li>
 *   <li>SUPPRESS-GO-AHEAD — both ends agree to suppress GA</li>
 *   <li>ECHO — server echoes (DO ECHO from our side)</li>
 * </ul>
 *
 * <p>Raw capture: if enabled in config or via the system property
 * {@code stratus.rawCapture=true}, every received byte is logged at DEBUG level
 * before being delivered to the caller. Useful for diagnosing protocol differences
 * on a real Stratus host.</p>
 */
public final class TelnetConnection {

    private static final Logger log = LoggerFactory.getLogger(TelnetConnection.class);

    private final StratusConfig config;
    private TelnetClient client;
    private InputStream  inputStream;
    private OutputStream outputStream;

    public TelnetConnection(StratusConfig config) {
        this.config = config;
    }

    /**
     * Opens the TCP socket and completes TELNET negotiation.
     *
     * @throws IOException if the connection cannot be established
     */
    public void connect() throws IOException {
        client = new TelnetClient(config.terminalType());

        // TERMINAL-TYPE: we will supply our type when the server requests it
        TerminalTypeOptionHandler ttHandler = new TerminalTypeOptionHandler(
                config.terminalType(),
                false,  // initLocal  — don't initiate WILL
                false,  // initRemote — don't initiate DO
                true,   // acceptLocal  — accept if server sends DO
                false   // acceptRemote — don't accept WILL from server
        );

        // SUPPRESS-GO-AHEAD: both directions
        SuppressGAOptionHandler gaHandler = new SuppressGAOptionHandler(
                true, true, true, true
        );

        // ECHO: we ask the server to echo (DO ECHO)
        EchoOptionHandler echoHandler = new EchoOptionHandler(
                false, // initLocal  — we don't echo locally
                true,  // initRemote — we ask server to echo (DO ECHO)
                false, // acceptLocal
                true   // acceptRemote — accept server WILL ECHO
        );

        try {
            client.addOptionHandler(ttHandler);
            client.addOptionHandler(gaHandler);
            client.addOptionHandler(echoHandler);
        } catch (Exception e) {
            throw new IOException("Failed to register TELNET option handlers", e);
        }

        client.setConnectTimeout(config.connectTimeoutMs());

        boolean rawCapture = config.rawCapture() ||
                "true".equalsIgnoreCase(System.getProperty("stratus.rawCapture"));

        if (rawCapture) {
            // Commons Net spy stream: every received byte is copied here before
            // being forwarded to the normal InputStream. Perfect for protocol capture.
            client.registerSpyStream(new RawCaptureStream(log));
            log.info("[TELNET] Raw capture mode enabled — received bytes will be logged at DEBUG");
        }

        log.info("[TELNET] Connecting to {}:{} (term={}, timeout={}ms)",
                config.host(), config.port(), config.terminalType(), config.connectTimeoutMs());

        client.connect(config.host(), config.port());

        inputStream  = client.getInputStream();
        outputStream = client.getOutputStream();

        log.info("[TELNET] Connected to {}:{}", config.host(), config.port());
    }

    /**
     * Closes the connection. Safe to call multiple times.
     */
    public void disconnect() {
        if (client != null && client.isConnected()) {
            try {
                client.disconnect();
                log.info("[TELNET] Disconnected from {}:{}", config.host(), config.port());
            } catch (IOException e) {
                log.debug("[TELNET] Error while disconnecting: {}", e.getMessage());
            }
        }
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    /**
     * Returns the de-IAC'd input stream.
     * Read bytes from this stream to receive terminal data from the host.
     */
    public InputStream getInputStream() {
        return inputStream;
    }

    /**
     * Returns the output stream.
     * Write bytes here to send data to the host (Commons Net handles IAC escaping).
     */
    public OutputStream getOutputStream() {
        return outputStream;
    }

    // -------------------------------------------------------------------------
    // Raw capture stream
    // -------------------------------------------------------------------------

    /**
     * OutputStream that logs every received byte in hex + ASCII for debugging.
     * Registered with TelnetClient.registerSpyStream().
     */
    private static final class RawCaptureStream extends java.io.OutputStream {

        private static final int COLS = 16;
        private final Logger log;
        private final byte[] lineBuf = new byte[COLS];
        private int pos = 0;

        RawCaptureStream(Logger log) { this.log = log; }

        @Override
        public void write(int b) {
            lineBuf[pos++] = (byte) b;
            if (pos == COLS) flush();
        }

        @Override
        public void flush() {
            if (pos == 0) return;
            StringBuilder hex = new StringBuilder();
            StringBuilder asc = new StringBuilder();
            for (int i = 0; i < pos; i++) {
                int v = lineBuf[i] & 0xFF;
                hex.append(String.format("%02X ", v));
                asc.append(v >= 0x20 && v < 0x7F ? (char) v : '.');
            }
            log.debug("[RAW] {}{}", hex, asc);
            pos = 0;
        }

        @Override
        public void close() { flush(); }
    }
}
