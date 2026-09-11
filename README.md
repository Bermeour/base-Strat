# base-stratus

Librería Java para automatizar sesiones de terminal Stratus VOS mediante TELNET/VT100-ANSI.

---

## Requisitos

- Java 8 o superior
- Maven 3.x

---

## Inicio rápido

```java
StratusConfig config = StratusConfig.builder("mi-servidor", 23)
        .settleMs(400)   // espera que la pantalla se estabilice tras cada wait
        .build();

try (StratusSession s = new StratusSession(config)) {
    s.connect()
     .waitForUpdate(15)
     .sendText("login\r")
     .waitForText("Username:", 5)
     .sendText("miusuario\r")
     .waitForText("Password:", 5)
     .sendText("mipassword\r")
     .waitForUpdate(10);

    System.out.println(s.getScreen().getText());
}
```

---

## Configuración — `StratusConfig`

```java
StratusConfig config = StratusConfig.builder("host", 23)
    .terminalType("VT100")      // tipo de terminal (default: VT100)
    .size(24, 80)               // filas x columnas (default: 24x80)
    .connectTimeoutMs(10_000)   // timeout de conexión TCP en ms (default: 10000)
    .charset("ISO-8859-1")      // codificación de caracteres (default: ISO-8859-1)
    .settleMs(400)              // ms de quietud tras cada wait (default: 0, desactivado)
    .rawCapture(true)           // loguea cada byte recibido en hex, útil para diagnóstico
    .build();
```

### `settleMs` — estabilización automática

Cuando está activo, cada `waitForText` y `waitForUpdate` espera hasta que la pantalla
no reciba ningún update durante ese tiempo antes de retornar. Evita que el código
continúe mientras el host todavía está pintando la pantalla.

```java
.settleMs(300)  // espera 300 ms sin cambios antes de continuar
```

Valores típicos: **200–500 ms** dependiendo de la velocidad del host.

---

## Sesión — `StratusSession`

Punto de entrada principal. Implementa `Closeable` (úsalo en try-with-resources).

### Conexión

```java
StratusSession s = new StratusSession(config);
s.connect();          // abre el socket y negocia TELNET
s.disconnect();       // cierra la conexión
s.isConnected();      // → boolean
```

### Envío de datos

```java
s.sendText("login\r");               // envía texto (\r simula Enter)
s.sendKey(TerminalKey.ENTER);        // envía una tecla especial
s.sendRaw(new byte[]{0x1B, 0x5B});   // envía bytes crudos
s.sendRaw(TerminalKey.ctrl('C'));     // envía Ctrl+C
```

### Esperas

Los tiempos se pueden expresar en **segundos** (`int`) o **milisegundos** (`long`):

```java
s.waitForText("Username:", 5);              // 5 segundos (int)
s.waitForText("Username:", 5_000L);         // 5000 milisegundos (long)
s.waitForUpdate(10);                        // espera cualquier actualización de pantalla
s.waitForTextInRow("Error", 24, 5);         // espera texto en una fila específica
s.waitForPattern(Pattern.compile("\\$"), 5);// espera que una fila coincida con regex
```

Si el tiempo se agota, todos los métodos lanzan `StratusTimeoutException` (RuntimeException):

```java
try {
    s.waitForText("Username:", 5);
} catch (StratusTimeoutException e) {
    System.err.println("No apareció el prompt: " + e.getMessage());
}
```

### Encadenamiento

Todos los métodos retornan `this`, lo que permite encadenar:

```java
s.connect()
 .waitForUpdate(15)
 .sendText("login\r")
 .waitForText("Username:", 5)
 .sendText("usuario\r")
 .waitForText("Password:", 5)
 .sendText("password\r")
 .waitForUpdate(10);
```

---

## Lectura de pantalla — `ScreenSnapshot`

Se obtiene con `s.getScreen()`. Todas las coordenadas son **1-based**.

### Leer texto

```java
ScreenSnapshot snap = s.getScreen();

snap.getLine(3);                   // fila completa (80 chars con espacios)
snap.getText(3, 10, 20);           // fila 3, desde col 10, 20 caracteres
snap.getTextTrimmed(3, 10, 20);    // igual pero sin espacios al inicio/fin
snap.getText();                    // pantalla completa como string con \n
snap.getLines(2, 8);               // filas 2 a 8 → List<String>
snap.getRegion(2, 1, 8, 40);       // región rectangular → List<String>
```

### Buscar texto

```java
snap.containsText("Error");              // ¿existe en alguna fila? → boolean
snap.containsTextInRow("Status", 5);     // ¿existe en la fila 5? → boolean
snap.rowOf("Status");                    // número de fila donde está (-1 si no existe)
snap.colOf("Status", 3);                 // columna donde empieza en fila 3 (-1 si no)
snap.findText("Error");                  // lista de ScreenPosition(fila, col)
```

### Leer campos de formulario

Ideal para pantallas con etiquetas y valores:

```
Username:  admin
Status:    Active
```

```java
snap.getFieldAfter(3, "Username:");   // "admin"  — busca en la fila 3
snap.getFieldAfter("Status:");        // "Active" — busca en toda la pantalla
```

### Cursor y dimensiones

```java
snap.cursorRow();   // fila del cursor (1-based)
snap.cursorCol();   // columna del cursor (1-based)
snap.rows();        // total de filas
snap.cols();        // total de columnas
```

---

## Teclas especiales — `TerminalKey`

```java
// Básicas
s.sendKey(TerminalKey.ENTER);
s.sendKey(TerminalKey.TAB);
s.sendKey(TerminalKey.ESCAPE);
s.sendKey(TerminalKey.BACKSPACE);

// Cursor
s.sendKey(TerminalKey.UP);
s.sendKey(TerminalKey.DOWN);
s.sendKey(TerminalKey.LEFT);
s.sendKey(TerminalKey.RIGHT);

// Navegación
s.sendKey(TerminalKey.HOME);
s.sendKey(TerminalKey.END);
s.sendKey(TerminalKey.PAGE_UP);
s.sendKey(TerminalKey.PAGE_DOWN);
s.sendKey(TerminalKey.INSERT);
s.sendKey(TerminalKey.DELETE);

// Teclas de función (F1–F12)
s.sendKey(TerminalKey.F1);
s.sendKey(TerminalKey.F5);
s.sendKey(TerminalKey.F12);

// Ctrl + letra (constantes predefinidas)
s.sendKey(TerminalKey.CTRL_C);   // SIGINT
s.sendKey(TerminalKey.CTRL_X);   // Ctrl+X
s.sendKey(TerminalKey.CTRL_Z);   // suspender

// Ctrl dinámico (cualquier letra)
s.sendRaw(TerminalKey.ctrl('X'));  // Ctrl+X
s.sendRaw(TerminalKey.ctrl('c'));  // Ctrl+C (mayúscula o minúscula)
```

---

## Visor de pantalla — `ScreenViewer`

Ventana Swing que muestra la terminal en tiempo real. Completamente opcional.

```java
ScreenViewer viewer = new ScreenViewer("Stratus VOS — producción");
viewer.show();
session.addListener(viewer);  // se refresca automáticamente con cada update
```

### Captura de pantalla

```java
// Solo imagen en memoria
BufferedImage img = viewer.captureScreenshot();

// Guardar con nombre explícito
viewer.saveScreenshot(new File("login.png"));

// Guardar con nombre automático en un directorio
// → capturas/stratus-20260910-125854-321.png
viewer.saveScreenshot("capturas/");
```

El screenshot funciona aunque la ventana esté oculta (renderizado off-screen).

### Ciclo de vida

```java
viewer.show();     // abre / trae al frente
viewer.hide();     // oculta sin destruir
viewer.dispose();  // libera todos los recursos
```

> **Nota**: en servidores Linux sin pantalla (headless), el constructor lanza
> `IllegalStateException`. Para screenshots en headless usa Xvfb.

---

## Eventos — `SessionListener`

```java
session.addListener(new SessionListener() {
    @Override
    public void onScreenUpdated(ScreenSnapshot snap) {
        // Llamado cada vez que llega un update del host
    }
    @Override
    public void onConnected() { }

    @Override
    public void onDisconnected() { }

    @Override
    public void onError(Exception e) { }
});
```

`onConnected`, `onDisconnected` y `onError` tienen implementación vacía por defecto.

---

## Ejemplos completos

### Login con visor

```java
StratusConfig config = StratusConfig.builder("mi-servidor", 23)
        .settleMs(400)
        .build();

ScreenViewer viewer = new ScreenViewer("Stratus VOS");
viewer.show();

try (StratusSession s = new StratusSession(config)) {
    s.addListener(viewer);
    s.connect()
     .waitForText("Username:", 10)
     .sendText("miusuario\r")
     .waitForText("Password:", 5)
     .sendText("mipassword\r")
     .waitForUpdate(10);

    viewer.saveScreenshot("capturas/");
    Thread.sleep(5000);
}
```

### Leer valor de un campo

```java
s.waitForText("Status:", 5);
String status = s.getScreen().getFieldAfter("Status:");
System.out.println("Estado: " + status);
```

### Navegar un menú y leer resultado

```java
s.waitForText("Enter selection", 5)
 .sendText("1\r")
 .waitForText("Press ENTER", 10);

ScreenSnapshot snap = s.getScreen();
int filaError = snap.rowOf("Error");
if (filaError > 0) {
    System.out.println(snap.getLine(filaError).trim());
}
```

### Diagnóstico de protocolo

```java
StratusConfig config = StratusConfig.builder("mi-servidor", 23)
        .rawCapture(true)   // loguea todos los bytes en hex a nivel DEBUG
        .build();
```

---

## Ejecutar los ejemplos

```bash
# Mock local (sin host real)
mvn exec:java -Dexec.mainClass="com.arkhos.stratus.examples.LoginExample" \
    -Dexec.classpathScope="test" -Dexec.args="--mock"

# Host real
mvn exec:java -Dexec.mainClass="com.arkhos.stratus.examples.LoginExample" \
    -Dexec.args="--host mi-servidor --port 23 --user miuser --pass mipass"

# Con visor de pantalla
mvn exec:java -Dexec.mainClass="com.arkhos.stratus.examples.LoginExample" \
    -Dexec.args="--host mi-servidor --user miuser --pass mipass --viewer"

# Ejecutar tests
mvn test
```

---

## Estructura del proyecto

```
src/main/java/com/arkhos/stratus/
├── config/
│   └── StratusConfig.java            Configuración inmutable de la sesión
├── session/
│   ├── StratusSession.java           Fachada principal (punto de entrada)
│   ├── SessionListener.java          Interface de eventos
│   └── StratusTimeoutException.java  Excepción de timeout (RuntimeException)
├── terminal/
│   ├── ScreenSnapshot.java           Instantánea inmutable de la pantalla
│   ├── ScreenPosition.java           Coordenada (fila, columna) 1-based
│   └── TerminalKey.java              Teclas especiales VT100/ANSI
└── ui/
    └── ScreenViewer.java             Visor Swing con soporte de screenshot
```
