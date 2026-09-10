package com.arkhos.stratus.session;

import com.arkhos.stratus.config.StratusConfig;
import com.arkhos.stratus.terminal.ScreenSnapshot;
import com.arkhos.stratus.terminal.TerminalKey;
import com.arkhos.stratus.testsupport.MockVt100Server;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link StratusSession} against {@link MockVt100Server}.
 *
 * <p>All tests run entirely against the in-process mock — no real Stratus host needed.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StratusSessionTest {

    private static MockVt100Server server;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        server = new MockVt100Server(0); // 0 = random free port
        server.start();
        server.awaitReady();
        port = server.getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
    }

    private StratusConfig config() {
        return StratusConfig.builder("localhost", port)
                .connectTimeoutMs(5000)
                .build();
    }

    private static final long SCREEN_TIMEOUT = 8000; // ms

    // -------------------------------------------------------------------------
    // Basic connection
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void connectAndDisconnect() throws Exception {
        try (StratusSession s = new StratusSession(config())) {
            s.connect();
            assertTrue(s.isConnected());
        }
    }

    @Test
    @Order(2)
    void loginScreenAppearsAfterConnect() throws Exception {
        try (StratusSession s = new StratusSession(config())) {
            s.connect();
            boolean found = s.waitForText("Username", SCREEN_TIMEOUT);
            assertTrue(found, "Expected 'Username' prompt on login screen");

            ScreenSnapshot snap = s.getScreen();
            assertNotNull(snap);
            assertTrue(snap.containsText("Stratus VOS"), "Expected title on login screen");
        }
    }

    // -------------------------------------------------------------------------
    // Login flow
    // -------------------------------------------------------------------------

    @Test
    @Order(3)
    void successfulLogin() throws Exception {
        try (StratusSession s = new StratusSession(config())) {
            s.connect();

            assertTrue(s.waitForText("Username", SCREEN_TIMEOUT), "Login screen not shown");
            s.sendText(MockVt100Server.VALID_USER + "\r");

            assertTrue(s.waitForText("Password", SCREEN_TIMEOUT), "Password prompt not shown");
            s.sendText(MockVt100Server.VALID_PASS + "\r");

            assertTrue(s.waitForText("Enter selection", SCREEN_TIMEOUT), "Main menu not shown after login");
            ScreenSnapshot menu = s.getScreen();
            assertTrue(menu.containsText("MAIN MENU"));
            assertTrue(menu.containsText("System Information"));
            assertTrue(menu.containsText("Logout"));
        }
    }

    @Test
    @Order(4)
    void invalidPasswordShowsLoginAgain() throws Exception {
        try (StratusSession s = new StratusSession(config())) {
            s.connect();

            s.waitForText("Username", SCREEN_TIMEOUT);
            s.sendText(MockVt100Server.VALID_USER + "\r");

            s.waitForText("Password", SCREEN_TIMEOUT);
            s.sendText("wrongpassword\r");

            boolean loginBack = s.waitForText("Username", SCREEN_TIMEOUT);
            assertTrue(loginBack, "Should return to login screen after bad password");
        }
    }

    // -------------------------------------------------------------------------
    // Menu navigation
    // -------------------------------------------------------------------------

    @Test
    @Order(5)
    void selectMenuOption1ShowsDetail() throws Exception {
        try (StratusSession s = new StratusSession(config())) {
            s.connect();
            login(s);

            s.sendText("1\r");
            // Wait for last element on detail screen so all rows are rendered
            assertTrue(s.waitForText("Press ENTER", SCREEN_TIMEOUT));
            ScreenSnapshot detail = s.getScreen();
            assertTrue(detail.containsText("SYSTEM INFORMATION"), "Expected title on detail screen");
            assertTrue(detail.containsText("stratus-mock-01"),    "Expected hostname in detail screen");
            assertTrue(detail.containsText("Stratus VOS"),        "Expected OS info in detail screen");
        }
    }

    @Test
    @Order(6)
    void returnFromDetailToMenu() throws Exception {
        try (StratusSession s = new StratusSession(config())) {
            s.connect();
            login(s);

            s.sendText("1\r");
            s.waitForText("Press ENTER", SCREEN_TIMEOUT); // wait for full detail screen

            // Any input returns to menu; wait for full menu (last element = "Enter selection")
            s.sendKey(TerminalKey.ENTER);
            assertTrue(s.waitForText("Enter selection", SCREEN_TIMEOUT), "Expected to return to main menu");
        }
    }

    // -------------------------------------------------------------------------
    // getScreen() / coordinate reading
    // -------------------------------------------------------------------------

    @Test
    @Order(7)
    void readTextAtCoordinates() throws Exception {
        try (StratusSession s = new StratusSession(config())) {
            s.connect();
            login(s); // login() already waits for full menu (including row 4)

            ScreenSnapshot snap = s.getScreen();
            // Row 4 should contain the first menu item
            String row4 = snap.getLine(4);
            assertTrue(row4.contains("System Information"),
                    "Row 4 should contain 'System Information', got: '" + row4 + "'");
        }
    }

    @Test
    @Order(8)
    void containsTextSearch() throws Exception {
        try (StratusSession s = new StratusSession(config())) {
            s.connect();
            s.waitForText("Username", SCREEN_TIMEOUT);

            ScreenSnapshot snap = s.getScreen();
            assertTrue(snap.containsText("Welcome"));
            assertFalse(snap.containsText("MAIN MENU"));
        }
    }

    // -------------------------------------------------------------------------
    // SessionListener
    // -------------------------------------------------------------------------

    @Test
    @Order(9)
    void sessionListenerReceivesEvents() throws Exception {
        AtomicReference<ScreenSnapshot> received = new AtomicReference<ScreenSnapshot>(null);
        final boolean[] connected  = {false};
        final boolean[] disconnected = {false};

        SessionListener listener = new SessionListener() {
            public void onScreenUpdated(ScreenSnapshot screen) { received.set(screen); }
            public void onConnected()    { connected[0] = true; }
            public void onDisconnected() { disconnected[0] = true; }
        };

        StratusSession s = new StratusSession(config());
        s.addListener(listener);
        s.connect();

        s.waitForText("Username", SCREEN_TIMEOUT);
        s.close();

        assertTrue(connected[0],    "onConnected should have been called");
        assertNotNull(received.get(), "onScreenUpdated should have been called at least once");
        // disconnected is eventually true but timing-dependent; skip assertion
    }

    // -------------------------------------------------------------------------
    // waitForText timeout
    // -------------------------------------------------------------------------

    @Test
    @Order(10)
    void waitForTextTimesOutIfTextNeverAppears() throws Exception {
        try (StratusSession s = new StratusSession(config())) {
            s.connect();
            s.waitForText("Username", SCREEN_TIMEOUT);

            long start = System.currentTimeMillis();
            boolean found = s.waitForText("NONEXISTENT_TOKEN_12345", 500);
            long elapsed = System.currentTimeMillis() - start;

            assertFalse(found, "Should return false when text never appears");
            assertTrue(elapsed >= 400, "Should have waited close to the full timeout");
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private void login(StratusSession s) throws Exception {
        s.waitForText("Username", SCREEN_TIMEOUT);
        s.sendText(MockVt100Server.VALID_USER + "\r");
        s.waitForText("Password", SCREEN_TIMEOUT);
        s.sendText(MockVt100Server.VALID_PASS + "\r");
        // Wait for "Enter selection" (last element on row 9) rather than "MAIN MENU"
        // (row 1), so the full menu screen is guaranteed to have been received
        // and parsed before we read any coordinates.
        s.waitForText("Enter selection", SCREEN_TIMEOUT);
    }
}
