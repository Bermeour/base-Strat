package com.arkhos.stratus.session;

import com.arkhos.stratus.config.StratusConfig;
import com.arkhos.stratus.terminal.ScreenSnapshot;
import com.arkhos.stratus.terminal.TerminalKey;
import com.arkhos.stratus.testsupport.MockVt100Server;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración de {@link StratusSession} contra {@link MockVt100Server}.
 *
 * <p>Todas las pruebas corren contra el mock en proceso; no se necesita acceso
 * a un host Stratus VOS real.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StratusSessionTest {

    private static MockVt100Server server;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        server = new MockVt100Server(0); // 0 = puerto libre aleatorio
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

    // ── Conexión básica ───────────────────────────────────────────────────────

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
            // waitForText lanza StratusTimeoutException si no aparece; si llega aquí, fue encontrado
            s.waitForText("Username", SCREEN_TIMEOUT);

            ScreenSnapshot snap = s.getScreen();
            assertNotNull(snap);
            assertTrue(snap.containsText("Stratus VOS"), "Se esperaba el título en la pantalla de login");
        }
    }

    // ── Flujo de login ────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void successfulLogin() throws Exception {
        try (StratusSession s = new StratusSession(config())) {
            s.connect();

            // Encadenamiento fluente: lanza StratusTimeoutException en cada paso si falla
            s.waitForText("Username", SCREEN_TIMEOUT)
             .sendText(MockVt100Server.VALID_USER + "\r")
             .waitForText("Password", SCREEN_TIMEOUT)
             .sendText(MockVt100Server.VALID_PASS + "\r")
             .waitForText("Enter selection", SCREEN_TIMEOUT);

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

            // Si vuelve a la pantalla de login, la espera no lanza excepción
            s.waitForText("Username", SCREEN_TIMEOUT);
        }
    }

    // ── Navegación del menú ───────────────────────────────────────────────────

    @Test
    @Order(5)
    void selectMenuOption1ShowsDetail() throws Exception {
        try (StratusSession s = new StratusSession(config())) {
            s.connect();
            login(s);

            // Esperamos el último elemento de la pantalla de detalle para garantizar
            // que todas las filas han sido parseadas antes de leer coordenadas
            s.sendText("1\r")
             .waitForText("Press ENTER", SCREEN_TIMEOUT);

            ScreenSnapshot detail = s.getScreen();
            assertTrue(detail.containsText("SYSTEM INFORMATION"), "Se esperaba el título de detalle");
            assertTrue(detail.containsText("stratus-mock-01"),    "Se esperaba el hostname en detalle");
            assertTrue(detail.containsText("Stratus VOS"),        "Se esperaba info del SO en detalle");
        }
    }

    @Test
    @Order(6)
    void returnFromDetailToMenu() throws Exception {
        try (StratusSession s = new StratusSession(config())) {
            s.connect();
            login(s);

            s.sendText("1\r")
             .waitForText("Press ENTER", SCREEN_TIMEOUT); // pantalla de detalle completa

            // Cualquier entrada regresa al menú; esperamos el último elemento del menú
            s.sendKey(TerminalKey.ENTER)
             .waitForText("Enter selection", SCREEN_TIMEOUT);
        }
    }

    // ── Lectura de pantalla / coordenadas ─────────────────────────────────────

    @Test
    @Order(7)
    void readTextAtCoordinates() throws Exception {
        try (StratusSession s = new StratusSession(config())) {
            s.connect();
            login(s); // login() ya espera "Enter selection" (fila 9), garantizando menú completo

            ScreenSnapshot snap = s.getScreen();
            // La fila 4 debe contener el primer ítem del menú
            String row4 = snap.getLine(4);
            assertTrue(row4.contains("System Information"),
                    "La fila 4 debería contener 'System Information', obtuvo: '" + row4 + "'");
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

    // ── SessionListener ───────────────────────────────────────────────────────

    @Test
    @Order(9)
    void sessionListenerReceivesEvents() throws Exception {
        AtomicReference<ScreenSnapshot> received = new AtomicReference<ScreenSnapshot>(null);
        final boolean[] connected    = {false};
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

        assertTrue(connected[0],     "onConnected debería haberse llamado");
        assertNotNull(received.get(), "onScreenUpdated debería haberse llamado al menos una vez");
        // disconnected es eventualmente true pero depende del hilo; no se aserta
    }

    // ── Timeout de waitForText ────────────────────────────────────────────────

    @Test
    @Order(10)
    void waitForTextTimesOutIfTextNeverAppears() throws Exception {
        try (StratusSession s = new StratusSession(config())) {
            s.connect();
            s.waitForText("Username", SCREEN_TIMEOUT);

            long start = System.currentTimeMillis();
            try {
                s.waitForText("NONEXISTENT_TOKEN_12345", 500);
                fail("Debería haber lanzado StratusTimeoutException");
            } catch (StratusTimeoutException e) {
                long elapsed = System.currentTimeMillis() - start;
                assertTrue(elapsed >= 400,
                        "Debería haber esperado cerca del timeout completo, pero tardó solo " + elapsed + " ms");
            }
        }
    }

    // ── Helper de login ───────────────────────────────────────────────────────

    /**
     * Realiza el flujo completo de login y espera hasta que el menú principal
     * esté completamente renderizado (incluyendo "Enter selection" en la fila 9),
     * evitando condiciones de carrera al leer coordenadas de pantalla.
     */
    private void login(StratusSession s) throws Exception {
        s.waitForText("Username", SCREEN_TIMEOUT)
         .sendText(MockVt100Server.VALID_USER + "\r")
         .waitForText("Password", SCREEN_TIMEOUT)
         .sendText(MockVt100Server.VALID_PASS + "\r")
         // Espera "Enter selection" (último elemento en fila 9), no "MAIN MENU" (fila 1),
         // para garantizar que toda la pantalla del menú ha llegado y fue parseada.
         .waitForText("Enter selection", SCREEN_TIMEOUT);
    }
}
