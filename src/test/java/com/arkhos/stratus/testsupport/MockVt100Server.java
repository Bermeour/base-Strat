package com.arkhos.stratus.testsupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process mock TELNET/VT100 server for unit and integration tests.
 *
 * <p>Simulates a Stratus VOS login session with three screens:</p>
 * <ol>
 *   <li>Login prompt — waits for username then password</li>
 *   <li>Main menu — after successful login</li>
 *   <li>Information screen — after the user picks option 1</li>
 * </ol>
 *
 * <p>Usage in tests:</p>
 * <pre>
 *   MockVt100Server server = new MockVt100Server(0); // 0 = pick a free port
 *   server.start();
 *   server.awaitReady();
 *   int port = server.getPort();
 *   // ... run client against localhost:port ...
 *   server.stop();
 * </pre>
 *
 * <h3>TELNET negotiation</h3>
 * <p>The server sends WILL SUPPRESS-GA and WILL ECHO, and accepts DO TERMINAL-TYPE.
 * Negotiation completes after a 300 ms quiet window on the socket.
 * Any IAC bytes that arrive in the data phase are silently consumed so they cannot
 * corrupt the application data stream.</p>
 */
public final class MockVt100Server {

    private static final Logger log = LoggerFactory.getLogger(MockVt100Server.class);

    // TELNET constants
    private static final int IAC  = 0xFF;
    private static final int WILL = 0xFB;
    private static final int WONT = 0xFC;
    private static final int DO   = 0xFD;
    private static final int DONT = 0xFE;
    private static final int SB   = 0xFA;
    private static final int SE   = 0xF0;

    private static final int OPT_ECHO           = 1;
    private static final int OPT_SUPPRESS_GA    = 3;
    private static final int OPT_TERMINAL_TYPE  = 24;

    private final int requestedPort;
    private ServerSocket serverSocket;
    private Thread       acceptThread;
    private final AtomicBoolean running    = new AtomicBoolean(false);
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    // Credentials accepted by the mock
    public static final String VALID_USER = "admin";
    public static final String VALID_PASS = "secret";

    public MockVt100Server(int port) {
        this.requestedPort = port;
    }

    /** Starts the server on a background thread. Returns after the port is bound. */
    public void start() throws IOException {
        serverSocket = new ServerSocket(requestedPort);
        serverSocket.setSoTimeout(200);
        log.info("[MOCK] Listening on port {}", serverSocket.getLocalPort());
        running.set(true);
        readyLatch.countDown();

        acceptThread = new Thread(new Runnable() {
            public void run() { acceptLoop(); }
        }, "mock-vt100-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** Blocks until the server is ready (or 5 s). */
    public void awaitReady() throws InterruptedException {
        readyLatch.await(5, TimeUnit.SECONDS);
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (acceptThread != null) {
            try { acceptThread.join(2000); } catch (InterruptedException ignored) {}
        }
        log.info("[MOCK] Stopped");
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }

    // -------------------------------------------------------------------------
    // Accept loop
    // -------------------------------------------------------------------------

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                log.debug("[MOCK] Client connected: {}", client.getRemoteSocketAddress());
                Thread handler = new Thread(new ClientHandler(client), "mock-vt100-client");
                handler.setDaemon(true);
                handler.start();
            } catch (SocketTimeoutException e) {
                // expected — loop to check running flag
            } catch (IOException e) {
                if (running.get()) log.warn("[MOCK] Accept error: {}", e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Per-client handler
    // -------------------------------------------------------------------------

    private static final class ClientHandler implements Runnable {

        private final Socket socket;
        private InputStream  in;
        private OutputStream out;

        private enum State { NEGOTIATE, LOGIN_USER, LOGIN_PASS, MENU, DETAIL, DONE }
        private State state = State.NEGOTIATE;
        private final StringBuilder inputBuf = new StringBuilder();

        ClientHandler(Socket socket) { this.socket = socket; }

        public void run() {
            try {
                in  = socket.getInputStream();
                out = socket.getOutputStream();
                doNegotiate();
                sendLoginScreen();
                state = State.LOGIN_USER;
                readLoop();
            } catch (IOException e) {
                log.debug("[MOCK] Client I/O error: {}", e.getMessage());
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        // ------------------------------------------------------------------
        // TELNET negotiation
        // ------------------------------------------------------------------

        /**
         * Sends our options, then drains whatever the client sends for up to 300 ms.
         * Using a short per-read timeout rather than trying to parse every IAC,
         * which keeps the mock simple and avoids holding the data phase hostage.
         */
        private void doNegotiate() throws IOException {
            // Server announces: WILL ECHO, WILL SUPPRESS-GA, DO TERMINAL-TYPE
            sendIac(WILL, OPT_ECHO);
            sendIac(WILL, OPT_SUPPRESS_GA);
            sendIac(DO,   OPT_TERMINAL_TYPE);
            out.flush();

            // Drain client's negotiation responses (up to 300 ms silence)
            socket.setSoTimeout(300);
            byte[] drain = new byte[256];
            try {
                while (true) {
                    int n = in.read(drain);
                    if (n == -1) break;
                    // Optionally log terminal type
                    logTerminalType(drain, n);
                }
            } catch (SocketTimeoutException e) {
                // 300 ms of silence — negotiation complete
            }
            socket.setSoTimeout(0); // back to blocking for data phase
        }

        private void logTerminalType(byte[] buf, int len) {
            // Look for SB TERMINAL_TYPE IS pattern (0xFA 0x18 0x00) followed by type string
            for (int i = 0; i + 3 < len; i++) {
                if ((buf[i] & 0xFF) == IAC && (buf[i+1] & 0xFF) == SB &&
                    (buf[i+2] & 0xFF) == OPT_TERMINAL_TYPE && (buf[i+3] & 0xFF) == 0) {
                    int start = i + 4;
                    int end   = start;
                    while (end < len && (buf[end] & 0xFF) != IAC) end++;
                    if (end > start) {
                        String termType = new String(buf, start, end - start,
                                Charset.forName("US-ASCII"));
                        log.debug("[MOCK] Client terminal type: {}", termType);
                    }
                    break;
                }
            }
        }

        private void sendIac(int verb, int opt) throws IOException {
            out.write(IAC); out.write(verb); out.write(opt);
        }

        // ------------------------------------------------------------------
        // Read loop — application data phase
        // ------------------------------------------------------------------

        /**
         * Reads characters from the client one byte at a time.
         * IAC sequences (which can arrive late if Commons Net sends them
         * after connect() returns) are silently skipped so they do not
         * corrupt the input buffer or get echoed back as data.
         */
        private void readLoop() throws IOException {
            int b;
            while ((b = in.read()) != -1) {
                if (b == IAC) {
                    skipIacCommand();
                    continue;
                }
                char c = (char) (b & 0xFF);
                if (c == '\r' || c == '\n') {
                    String line = inputBuf.toString();
                    inputBuf.setLength(0);
                    handleLine(line); // allow empty (e.g. bare ENTER on detail screen)
                    if (state == State.DONE) break;
                } else if (c >= 0x20 && c < 0x7F) {
                    // Echo printable ASCII back (we announced WILL ECHO)
                    out.write(b);
                    out.flush();
                    inputBuf.append(c);
                }
            }
        }

        /**
         * Consumes one TELNET command (verb + option, or subnegotiation) after IAC.
         */
        private void skipIacCommand() throws IOException {
            int verb = in.read();
            if (verb == -1) return;
            if (verb == SB) {
                // Read and discard until IAC SE
                int prev = -1;
                while (true) {
                    int c = in.read();
                    if (c == -1) return;
                    if (prev == IAC && c == SE) return;
                    prev = c;
                }
            } else if (verb == IAC) {
                // IAC IAC = literal 0xFF data byte — ignore
            } else {
                // WILL / WONT / DO / DONT — consume option byte
                in.read();
            }
        }

        private void handleLine(String line) throws IOException {
            line = line.trim();
            switch (state) {
                case LOGIN_USER:
                    if (line.isEmpty()) break; // ignore bare ENTER on login screen
                    if (VALID_USER.equalsIgnoreCase(line)) {
                        sendPasswordPrompt();
                        state = State.LOGIN_PASS;
                    } else {
                        sendText("\r\nUnknown user. Try again.\r\n");
                        sendLoginScreen();
                    }
                    break;
                case LOGIN_PASS:
                    if (line.isEmpty()) break;
                    if (VALID_PASS.equals(line)) {
                        sendMenuScreen();
                        state = State.MENU;
                    } else {
                        sendText("\r\nInvalid password.\r\n");
                        sendLoginScreen();
                        state = State.LOGIN_USER;
                    }
                    break;
                case MENU:
                    if (line.isEmpty()) break;
                    if ("1".equals(line)) {
                        sendDetailScreen();
                        state = State.DETAIL;
                    } else if ("2".equals(line)) {
                        sendText(moveTo(1, 1) + esc("[2J") + "Goodbye.\r\n");
                        out.flush();
                        state = State.DONE;
                    } else {
                        sendText("\r\nInvalid option. Enter 1 or 2.\r\n");
                        sendMenuPrompt();
                    }
                    break;
                case DETAIL:
                    // Any input (including bare ENTER) returns to main menu
                    sendMenuScreen();
                    state = State.MENU;
                    break;
                default:
                    break;
            }
        }

        // ------------------------------------------------------------------
        // Screen sequences
        // ------------------------------------------------------------------

        private static final String BOLD_ON  = "\033[1m";
        private static final String BOLD_OFF = "\033[0m";
        private static final String CLEAR    = "\033[2J\033[H";

        private void sendLoginScreen() throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append(CLEAR);
            sb.append(moveTo(1, 25)).append(BOLD_ON).append("Stratus VOS").append(BOLD_OFF);
            sb.append(moveTo(2, 20)).append("OpenVOS Terminal Services");
            sb.append(moveTo(4,  1)).append(line('-', 79));
            sb.append(moveTo(6, 10)).append("Welcome. Please log in.");
            sb.append(moveTo(9, 10)).append("Username: ");
            sendText(sb.toString());
        }

        private void sendPasswordPrompt() throws IOException {
            sendText(moveTo(10, 10) + "Password: ");
        }

        private void sendMenuScreen() throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append(CLEAR);
            sb.append(moveTo(1, 30)).append(BOLD_ON).append("MAIN MENU").append(BOLD_OFF);
            sb.append(moveTo(2,  1)).append(line('=', 79));
            sb.append(moveTo(4, 10)).append("1. System Information");
            sb.append(moveTo(5, 10)).append("2. Logout");
            sb.append(moveTo(7,  1)).append(line('-', 79));
            sb.append(moveTo(9, 10)).append("Enter selection: ");
            sendText(sb.toString());
        }

        private void sendMenuPrompt() throws IOException {
            sendText(moveTo(9, 10) + "Enter selection: ");
        }

        private void sendDetailScreen() throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append(CLEAR);
            sb.append(moveTo(1, 28)).append(BOLD_ON).append("SYSTEM INFORMATION").append(BOLD_OFF);
            sb.append(moveTo(2,  1)).append(line('=', 79));
            sb.append(moveTo(4, 10)).append("Hostname  : stratus-mock-01");
            sb.append(moveTo(5, 10)).append("OS        : Stratus VOS 17.1");
            sb.append(moveTo(6, 10)).append("Status    : RUNNING");
            sb.append(moveTo(7, 10)).append("Uptime    : 42 days");
            sb.append(moveTo(9,  1)).append(line('-', 79));
            sb.append(moveTo(11, 10)).append("Press ENTER to return to menu.");
            sendText(sb.toString());
        }

        // ------------------------------------------------------------------
        // Helpers
        // ------------------------------------------------------------------

        private void sendText(String s) throws IOException {
            out.write(s.getBytes("ISO-8859-1"));
            out.flush();
        }

        private static String esc(String seq) { return "\033" + seq; }

        private static String moveTo(int row, int col) {
            return "\033[" + row + ";" + col + "H";
        }

        private static String line(char ch, int len) {
            char[] arr = new char[len];
            java.util.Arrays.fill(arr, ch);
            return new String(arr);
        }
    }
}
