package com.arkhos.stratus.config;

/**
 * Immutable configuration for a Stratus VOS session.
 * Build with the fluent builder: {@code StratusConfig.builder("host", 23).build()}.
 *
 * <p>To switch between environments (mock / real host) change only host and port;
 * all other defaults are designed for a standard Stratus VOS TELNET session.</p>
 */
public final class StratusConfig {

    private final String host;
    private final int port;
    private final String terminalType;
    private final int rows;
    private final int cols;
    private final int connectTimeoutMs;
    private final boolean rawCapture;
    private final String charset;

    private StratusConfig(Builder b) {
        this.host             = b.host;
        this.port             = b.port;
        this.terminalType     = b.terminalType;
        this.rows             = b.rows;
        this.cols             = b.cols;
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.rawCapture       = b.rawCapture;
        this.charset          = b.charset;
    }

    public static Builder builder(String host, int port) {
        return new Builder(host, port);
    }

    public String host()             { return host; }
    public int port()                { return port; }
    public String terminalType()     { return terminalType; }
    public int rows()                { return rows; }
    public int cols()                { return cols; }
    public int connectTimeoutMs()    { return connectTimeoutMs; }
    public boolean rawCapture()      { return rawCapture; }
    public String charset()          { return charset; }

    @Override
    public String toString() {
        return "StratusConfig{host='" + host + "', port=" + port +
               ", term=" + terminalType + ", size=" + rows + "x" + cols + "}";
    }

    public static final class Builder {
        private final String host;
        private final int port;
        private String terminalType  = "VT100";
        private int rows             = 24;
        private int cols             = 80;
        private int connectTimeoutMs = 10_000;
        private boolean rawCapture   = false;
        private String charset       = "ISO-8859-1";

        private Builder(String host, int port) {
            this.host = host;
            this.port = port;
        }

        /** Terminal type advertised during TELNET negotiation (default: VT100). */
        public Builder terminalType(String t) { this.terminalType = t; return this; }

        /** Screen dimensions reported via NAWS and used for the screen buffer (default: 24x80). */
        public Builder size(int rows, int cols) { this.rows = rows; this.cols = cols; return this; }

        /** TCP connect timeout in milliseconds (default: 10000). */
        public Builder connectTimeoutMs(int ms) { this.connectTimeoutMs = ms; return this; }

        /**
         * Activates raw-capture mode: every byte received from the host is logged at
         * DEBUG level in hex and ASCII. Use this to capture a real Stratus VOS session
         * and diagnose protocol differences. Can also be set via system property
         * {@code -Dstratus.rawCapture=true}.
         */
        public Builder rawCapture(boolean enabled) { this.rawCapture = enabled; return this; }

        /** Character encoding for converting bytes to chars (default: ISO-8859-1). */
        public Builder charset(String cs) { this.charset = cs; return this; }

        public StratusConfig build() {
            if (host == null || host.isEmpty()) throw new IllegalStateException("host is required");
            if (port < 1 || port > 65535)      throw new IllegalStateException("invalid port: " + port);
            return new StratusConfig(this);
        }
    }
}
