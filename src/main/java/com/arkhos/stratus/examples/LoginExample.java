package com.arkhos.stratus.examples;

import com.arkhos.stratus.config.StratusConfig;
import com.arkhos.stratus.session.StratusSession;
import com.arkhos.stratus.session.StratusTimeoutException;
import com.arkhos.stratus.terminal.ScreenSnapshot;
import com.arkhos.stratus.terminal.TerminalKey;
import com.arkhos.stratus.ui.ScreenViewer;

/**
 * Ejemplo ejecutable end-to-end para sesiones Stratus VOS.
 *
 * <h3>Flujo real de Stratus VOS (desde la maquina de trabajo)</h3>
 * <pre>
 *   Conectar -> banner/aviso legal -> escribir "login" + Enter
 *            -> prompt "username:" -> usuario -> prompt "password:" -> password -> sesion
 * </pre>
 *
 * <h3>Ejemplos de uso</h3>
 *
 * Host real (flujo tipico Stratus VOS):
 * <pre>
 *   mvn exec:java -Dexec.mainClass="com.arkhos.stratus.examples.LoginExample"
 *       -Dexec.args="--host vos-host --port 23 --user miuser --pass mipass --pre-login login"
 * </pre>
 *
 * Mock local (desarrollo sin acceso al host):
 * <pre>
 *   mvn exec:java -Dexec.mainClass="com.arkhos.stratus.examples.LoginExample"
 *       -Dexec.classpathScope="test" -Dexec.args="--mock"
 * </pre>
 *
 * Diagnostico de protocolo (primera conexion al host real):
 * <pre>
 *   mvn exec:java -Dexec.mainClass="com.arkhos.stratus.examples.LoginExample"
 *       -Dexec.args="--host vos-host --raw --pre-login login"
 * </pre>
 *
 * <h3>Argumentos</h3>
 * <ul>
 *   <li>{@code --host}        Host al que conectar (default: localhost)</li>
 *   <li>{@code --port}        Puerto TELNET (default: 23)</li>
 *   <li>{@code --user}        Usuario</li>
 *   <li>{@code --pass}        Password</li>
 *   <li>{@code --pre-login}   Comando a enviar ANTES del username, tras el banner
 *                             (ej: "login" requerido en Stratus VOS real)</li>
 *   <li>{@code --banner-text} Texto a esperar en el banner inicial (opcional;
 *                             si se omite se espera cualquier contenido del host)</li>
 *   <li>{@code --user-prompt} Texto del prompt de usuario (default: "username")</li>
 *   <li>{@code --pass-prompt} Texto del prompt de password (default: "assword")</li>
 *   <li>{@code --timeout}     Timeout en ms para cada espera (default: 20000)</li>
 *   <li>{@code --raw}         Activa captura raw: loguea cada byte recibido en hex</li>
 *   <li>{@code --mock}        Inicia el servidor mock local en vez de conectar a un host real</li>
 * </ul>
 */
public class LoginExample {

    public static void main(String[] args) throws Exception {

        // ── Parseo de argumentos ─────────────────────────────────────────────
        boolean useMock    = has(args, "--mock");
        boolean rawMode    = has(args, "--raw");
        boolean useViewer  = has(args, "--viewer");
        String  host       = arg(args, "--host",        "localhost");
        int     port       = Integer.parseInt(arg(args, "--port",   "23"));
        String  user       = arg(args, "--user",        "admin");
        String  pass       = arg(args, "--pass",        "secret");
        String  preLogin   = arg(args, "--pre-login",   null);   // ej: "login"
        String  bannerText = arg(args, "--banner-text", null);   // texto a esperar en el banner
        String  userPrompt = arg(args, "--user-prompt", "username");
        String  passPrompt = arg(args, "--pass-prompt", "assword"); // cubre "Password"/"password"
        long    timeout    = Long.parseLong(arg(args, "--timeout", "20000"));

        // ── Mock opcional ────────────────────────────────────────────────────
        Object mockRef = null;
        if (useMock) {
            mockRef = startMock();
            if (mockRef == null) return;
            port = (int) mockRef.getClass().getMethod("getPort").invoke(mockRef);
            host = "localhost";
            preLogin = null; // el mock no usa pre-login
            System.out.println("Mock iniciado en puerto " + port);
        }

        try {
            StratusConfig config = StratusConfig.builder(host, port)
                    .rawCapture(rawMode)
                    .connectTimeoutMs(30_000)
                    .build();

            try (StratusSession session = new StratusSession(config)) {

                // ── Visor de pantalla opcional ───────────────────────────────
                ScreenViewer viewer = null;
                if (useViewer) {
                    viewer = new ScreenViewer("Stratus VOS — " + host + ":" + port);
                    session.addListener(viewer);
                    viewer.show();
                }

                // ── 1. Conectar ──────────────────────────────────────────────
                System.out.println("Conectando a " + host + ":" + port + " ...");
                session.connect();

                // ── 2. Esperar banner inicial ────────────────────────────────
                System.out.println("Esperando contenido inicial del host...");
                try {
                    if (bannerText != null) {
                        session.waitForText(bannerText, timeout);
                    } else {
                        session.waitForUpdate(timeout);
                    }
                } catch (StratusTimeoutException e) {
                    System.err.println("ERROR: El host no envio contenido en " + timeout + " ms.");
                    System.err.println("Verifica host, puerto y que el host este accesible.");
                    return;
                }
                printScreen(session.getScreen(), "=== Banner / Pantalla inicial ===");

                // ── 3. Comando pre-login (ej: "login" en Stratus VOS real) ───
                if (preLogin != null && !preLogin.isEmpty()) {
                    System.out.println("Enviando comando pre-login: \"" + preLogin + "\"");
                    session.sendText(preLogin + "\r");
                }

                // ── 4. Esperar prompt de usuario ─────────────────────────────
                System.out.println("Esperando prompt de usuario (\"" + userPrompt + "\")...");
                try {
                    session.waitForText(userPrompt, timeout);
                } catch (StratusTimeoutException e) {
                    System.err.println("ERROR: Prompt de usuario no aparecio en " + timeout + " ms.");
                    System.err.println("Pantalla actual:");
                    printScreen(session.getScreen(), "=== Estado ===");
                    System.err.println("Sugerencia: verifica --user-prompt o usa --raw para");
                    System.err.println("  ver exactamente que envia el host.");
                    return;
                }

                // ── 5. Enviar usuario ────────────────────────────────────────
                System.out.println("Enviando usuario: " + user);
                session.sendText(user + "\r");

                // ── 6. Esperar prompt de password ────────────────────────────
                System.out.println("Esperando prompt de password (\"" + passPrompt + "\")...");
                try {
                    session.waitForText(passPrompt, timeout);
                } catch (StratusTimeoutException e) {
                    System.err.println("ERROR: Prompt de password no aparecio en " + timeout + " ms.");
                    printScreen(session.getScreen(), "=== Estado ===");
                    return;
                }

                // ── 7. Enviar password ───────────────────────────────────────
                System.out.println("Enviando password: ***");
                session.sendText(pass + "\r");

                // ── 8. Esperar pantalla post-login ───────────────────────────
                System.out.println("Esperando pantalla post-login...");
                try {
                    session.waitForUpdate(timeout);
                } catch (StratusTimeoutException e) {
                    System.err.println("ERROR: No se recibio respuesta del host tras el login.");
                    return;
                }
                printScreen(session.getScreen(), "=== Post-login ===");

                // ── 9. Leer pantalla por coordenadas ─────────────────────────
                ScreenSnapshot snap = session.getScreen();
                System.out.println("Fila 1: " + snap.getLine(1).trim());
                System.out.println("Fila 4: " + snap.getLine(4).trim());
                System.out.println("Cursor en: (" + snap.cursorRow() + ", " + snap.cursorCol() + ")");

                // ── 10. Navegacion de ejemplo (solo si es el mock) ───────────
                if (useMock) {
                    System.out.println("\n[Mock] Seleccionando opcion 1...");
                    session.sendText("1\r");
                    session.waitForText("Press ENTER", timeout);
                    printScreen(session.getScreen(), "=== Informacion del Sistema ===");

                    ScreenSnapshot detail = session.getScreen();
                    int statusRow = detail.rowOf("Status");
                    if (statusRow > 0) {
                        System.out.println("Estado: " + detail.getLine(statusRow).trim());
                    }

                    session.sendKey(TerminalKey.ENTER);
                    session.waitForText("Enter selection", timeout);

                    System.out.println("\n[Mock] Logout...");
                    session.sendText("2\r");
                }

                System.out.println("\nFin del ejemplo.");

                // Mantener el visor abierto unos segundos para poder ver el estado final
                if (useViewer) {
                    Thread.sleep(3000);
                }

            }
        } finally {
            if (mockRef != null) {
                mockRef.getClass().getMethod("stop").invoke(mockRef);
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static void printScreen(ScreenSnapshot snap, String title) {
        System.out.println("\n" + title);
        if (snap == null) { System.out.println("  (sin datos todavia)"); return; }
        System.out.println(snap.toDebugString());
        System.out.println();
    }

    private static Object startMock() {
        try {
            Class<?> cls = Class.forName("com.arkhos.stratus.testsupport.MockVt100Server");
            Object mock = cls.getConstructor(int.class).newInstance(0);
            cls.getMethod("start").invoke(mock);
            cls.getMethod("awaitReady").invoke(mock);
            return mock;
        } catch (ClassNotFoundException e) {
            System.err.println("MockVt100Server no disponible. Usa -Dexec.classpathScope=test");
            return null;
        } catch (Exception e) {
            System.err.println("Error iniciando mock: " + e.getMessage());
            return null;
        }
    }

    private static boolean has(String[] args, String flag) {
        for (String a : args) if (flag.equals(a)) return true;
        return false;
    }

    private static String arg(String[] args, String key, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (key.equals(args[i])) return args[i + 1];
        }
        return def;
    }
}
