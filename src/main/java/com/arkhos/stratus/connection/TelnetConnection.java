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
 * Capa de transporte TELNET para la conexión a Stratus VOS.
 *
 * <p>Envuelve {@link TelnetClient} de Apache Commons Net para gestionar
 * automáticamente la negociación IAC (RFC 854/855/856). Tras {@link #connect()},
 * el llamador recibe streams limpios sin bytes IAC — Commons Net los procesa
 * de forma transparente.</p>
 *
 * <p>Opciones negociadas:</p>
 * <ul>
 *   <li>TERMINAL-TYPE — anunciamos el tipo configurado en {@link StratusConfig#terminalType()}</li>
 *   <li>SUPPRESS-GO-AHEAD — ambos extremos suprimen GA</li>
 *   <li>ECHO — el servidor hace eco (DO ECHO desde nuestra parte)</li>
 * </ul>
 *
 * <p>Raw capture: si está habilitado en la config o mediante la propiedad de sistema
 * {@code stratus.rawCapture=true}, cada byte recibido se loguea a nivel DEBUG antes
 * de entregarse al llamador. Útil para diagnosticar diferencias de protocolo en un
 * host Stratus real.</p>
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
     * Abre el socket TCP y completa la negociación TELNET.
     *
     * @throws IOException si no se puede establecer la conexión
     */
    public void connect() throws IOException {
        client = new TelnetClient(config.terminalType());

        // TERMINAL-TYPE: suministramos nuestro tipo cuando el servidor lo solicite
        TerminalTypeOptionHandler ttHandler = new TerminalTypeOptionHandler(
                config.terminalType(),
                false,  // initLocal  — no iniciamos WILL
                false,  // initRemote — no iniciamos DO
                true,   // acceptLocal  — aceptamos si el servidor envía DO
                false   // acceptRemote — no aceptamos WILL del servidor
        );

        // SUPPRESS-GO-AHEAD: ambas direcciones
        SuppressGAOptionHandler gaHandler = new SuppressGAOptionHandler(
                true, true, true, true
        );

        // ECHO: pedimos al servidor que haga eco (DO ECHO)
        EchoOptionHandler echoHandler = new EchoOptionHandler(
                false, // initLocal  — no hacemos eco local
                true,  // initRemote — pedimos eco al servidor (DO ECHO)
                false, // acceptLocal
                true   // acceptRemote — aceptamos WILL ECHO del servidor
        );

        try {
            client.addOptionHandler(ttHandler);
            client.addOptionHandler(gaHandler);
            client.addOptionHandler(echoHandler);
        } catch (Exception e) {
            throw new IOException("Error al registrar manejadores de opciones TELNET", e);
        }

        client.setConnectTimeout(config.connectTimeoutMs());

        boolean rawCapture = config.rawCapture() ||
                "true".equalsIgnoreCase(System.getProperty("stratus.rawCapture"));

        if (rawCapture) {
            // Spy stream de Commons Net: cada byte recibido se copia aquí antes de
            // entregarse al InputStream normal. Ideal para captura de protocolo.
            client.registerSpyStream(new RawCaptureStream(log));
            log.info("[TELNET] Modo raw-capture activo — bytes recibidos se loguean a nivel DEBUG");
        }

        log.info("[TELNET] Connecting to {}:{} (term={}, timeout={}ms)",
                config.host(), config.port(), config.terminalType(), config.connectTimeoutMs());

        client.connect(config.host(), config.port());

        inputStream  = client.getInputStream();
        outputStream = client.getOutputStream();

        log.info("[TELNET] Connected to {}:{}", config.host(), config.port());
    }

    /**
     * Cierra la conexión. Seguro de llamar múltiples veces.
     */
    public void disconnect() {
        if (client != null && client.isConnected()) {
            try {
                client.disconnect();
                log.info("[TELNET] Disconnected from {}:{}", config.host(), config.port());
            } catch (IOException e) {
                log.debug("[TELNET] Error al desconectar: {}", e.getMessage());
            }
        }
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    /**
     * Retorna el stream de entrada sin bytes IAC.
     * Lee de aquí para recibir datos de terminal del host.
     */
    public InputStream getInputStream() {
        return inputStream;
    }

    /**
     * Retorna el stream de salida.
     * Escribe aquí para enviar datos al host (Commons Net gestiona el escape de IAC).
     */
    public OutputStream getOutputStream() {
        return outputStream;
    }

    // ── Stream de captura raw ─────────────────────────────────────────────────

    /**
     * OutputStream que loguea cada byte recibido en hex + ASCII para diagnóstico.
     * Se registra con {@code TelnetClient.registerSpyStream()}.
     */
    private static final class RawCaptureStream extends java.io.OutputStream {

        private static final int COLS = 16;  // bytes por línea de log
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
