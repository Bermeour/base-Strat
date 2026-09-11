package com.arkhos.stratus.config;

/**
 * Configuración inmutable de una sesión Stratus VOS.
 * Se construye con el builder fluente: {@code StratusConfig.builder("host", 23).build()}.
 *
 * <p>Para cambiar de entorno (mock / host real) basta con modificar host y puerto;
 * el resto de los valores por defecto están diseñados para una sesión TELNET
 * estándar de Stratus VOS.</p>
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
    private final long settleMs;

    private StratusConfig(Builder b) {
        this.host             = b.host;
        this.port             = b.port;
        this.terminalType     = b.terminalType;
        this.rows             = b.rows;
        this.cols             = b.cols;
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.rawCapture       = b.rawCapture;
        this.charset          = b.charset;
        this.settleMs         = b.settleMs;
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
    public long settleMs()           { return settleMs; }

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
        private long settleMs        = 0;

        private Builder(String host, int port) {
            this.host = host;
            this.port = port;
        }

        /** Tipo de terminal anunciado durante la negociación TELNET (por defecto: VT100). */
        public Builder terminalType(String t) { this.terminalType = t; return this; }

        /** Dimensiones de pantalla reportadas y usadas para el buffer (por defecto: 24x80). */
        public Builder size(int rows, int cols) { this.rows = rows; this.cols = cols; return this; }

        /** Timeout de conexión TCP en milisegundos (por defecto: 10 000). */
        public Builder connectTimeoutMs(int ms) { this.connectTimeoutMs = ms; return this; }

        /**
         * Activa el modo raw-capture: cada byte recibido del host se loguea a nivel
         * DEBUG en hex y ASCII. Útil para capturar una sesión real de Stratus VOS
         * y diagnosticar diferencias de protocolo. También se puede activar con la
         * propiedad de sistema {@code -Dstratus.rawCapture=true}.
         */
        public Builder rawCapture(boolean enabled) { this.rawCapture = enabled; return this; }

        /** Codificación de caracteres para convertir bytes a chars (por defecto: ISO-8859-1). */
        public Builder charset(String cs) { this.charset = cs; return this; }

        /**
         * Tiempo de estabilización en milisegundos (por defecto: 0, desactivado).
         *
         * <p>Cuando es mayor que cero, cada {@code waitForText} / {@code waitForUpdate}
         * espera adicionalmente hasta que la pantalla no reciba ningún nuevo update
         * durante {@code ms} milisegundos antes de retornar. Esto evita que el código
         * continúe mientras el host todavía está pintando la pantalla.</p>
         *
         * <p>Valores típicos: 200–500 ms.</p>
         */
        public Builder settleMs(long ms) { this.settleMs = ms; return this; }

        public StratusConfig build() {
            if (host == null || host.isEmpty()) throw new IllegalStateException("host es obligatorio");
            if (port < 1 || port > 65535)      throw new IllegalStateException("puerto inválido: " + port);
            return new StratusConfig(this);
        }
    }
}
