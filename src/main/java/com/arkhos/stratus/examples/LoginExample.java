package com.arkhos.stratus.examples;

import com.arkhos.stratus.config.StratusConfig;
import com.arkhos.stratus.session.StratusSession;
import com.arkhos.stratus.terminal.ScreenSnapshot;
import com.arkhos.stratus.terminal.TerminalKey;

/**
 * Ejemplo ejecutable end-to-end contra un host Stratus VOS real o el mock local.
 *
 * <p>Uso básico (host real):</p>
 * <pre>
 *   java -jar base-stratus-1.0.0-SNAPSHOT.jar \
 *        --host vos-host.example.com --port 23 \
 *        --user miusuario --pass mipassword
 * </pre>
 *
 * <p>Con mock local (inicia el servidor en memoria y se conecta):</p>
 * <pre>
 *   java -jar base-stratus-1.0.0-SNAPSHOT.jar --mock
 * </pre>
 *
 * <p>Para captura raw (diagnóstico del protocolo en host real):</p>
 * <pre>
 *   java -jar base-stratus-1.0.0-SNAPSHOT.jar --host ... --raw
 * </pre>
 */
public class LoginExample {

    public static void main(String[] args) throws Exception {

        // --- parse args ---
        boolean useMock = has(args, "--mock");
        boolean rawMode = has(args, "--raw");
        String  host    = arg(args, "--host", "localhost");
        int     port    = Integer.parseInt(arg(args, "--port", "23"));
        String  user    = arg(args, "--user", "admin");
        String  pass    = arg(args, "--pass", "secret");

        // --- arrancar el mock si se pidió ---
        Object mockRef = null;
        if (useMock) {
            // Carga dinámica para no requerir las clases de test en runtime de producción
            // En un contexto de desarrollo simplemente use el fat-jar con test-classes incluidas.
            try {
                Class<?> mockClass = Class.forName(
                        "com.arkhos.stratus.testsupport.MockVt100Server");
                mockRef = mockClass.getConstructor(int.class).newInstance(0);
                mockClass.getMethod("start").invoke(mockRef);
                mockClass.getMethod("awaitReady").invoke(mockRef);
                port = (int) mockClass.getMethod("getPort").invoke(mockRef);
                host = "localhost";
                System.out.println("Mock server iniciado en puerto " + port);
            } catch (ClassNotFoundException e) {
                System.err.println("MockVt100Server no disponible en el classpath.");
                System.err.println("Compile con 'mvn package -Pinclude-tests' o use el fat-jar de tests.");
                System.exit(1);
            }
        }

        try {
            StratusConfig config = StratusConfig.builder(host, port)
                    .rawCapture(rawMode)
                    .build();

            try (StratusSession session = new StratusSession(config)) {

                System.out.println("Conectando a " + host + ":" + port + " ...");
                session.connect();

                // 1. Esperar pantalla de login
                System.out.println("Esperando prompt de login...");
                if (!session.waitForText("Username", 10_000)) {
                    System.err.println("ERROR: Pantalla de login no aparecio en 10 s");
                    printScreen(session.getScreen(), "=== Estado actual ===");
                    return;
                }
                printScreen(session.getScreen(), "=== Pantalla de Login ===");

                // 2. Enviar usuario
                System.out.println("Enviando usuario: " + user);
                session.sendText(user + "\r");
                session.waitForText("Password", 5_000);

                // 3. Enviar password
                System.out.println("Enviando password: ***");
                session.sendText(pass + "\r");

                // 4. Esperar menú completo (esperar el último elemento del menú)
                if (!session.waitForText("Enter selection", 10_000)) {
                    System.err.println("ERROR: Menu principal no aparecio. Pantalla actual:");
                    printScreen(session.getScreen(), "=== Estado actual ===");
                    return;
                }
                printScreen(session.getScreen(), "=== Menu Principal ===");

                // 5. Leer contenido por coordenadas
                ScreenSnapshot menu = session.getScreen();
                System.out.println("Opcion 1: '" + menu.getTextTrimmed(4, 10, 30) + "'");
                System.out.println("Opcion 2: '" + menu.getTextTrimmed(5, 10, 30) + "'");
                System.out.println("Cursor en: (" + menu.cursorRow() + ", " + menu.cursorCol() + ")");

                // 6. Ir a Informacion del sistema
                System.out.println("\nSeleccionando opcion 1...");
                session.sendText("1\r");
                session.waitForText("Press ENTER", 5_000);
                printScreen(session.getScreen(), "=== Informacion del Sistema ===");

                // 7. Leer campo especifico
                ScreenSnapshot detail = session.getScreen();
                int statusRow = detail.rowOf("Status");
                if (statusRow > 0) {
                    System.out.println("Linea de estado: " + detail.getLine(statusRow).trim());
                }

                // 8. Volver al menu
                session.sendKey(TerminalKey.ENTER);
                session.waitForText("Enter selection", 5_000);

                // 9. Logout
                System.out.println("\nCerrando sesion...");
                session.sendText("2\r");

                System.out.println("Fin del ejemplo.");
            }
        } finally {
            if (mockRef != null) {
                mockRef.getClass().getMethod("stop").invoke(mockRef);
            }
        }
    }

    private static void printScreen(ScreenSnapshot snap, String title) {
        if (snap == null) { System.out.println(title + " (sin datos aun)"); return; }
        System.out.println("\n" + title);
        System.out.println(snap.toDebugString());
        System.out.println();
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
