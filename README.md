# base-stratus

Programmatic Stratus VOS (OpenVOS) terminal client via TELNET/VT100-ANSI.

Same philosophy as `base-as400`: speaks the wire protocol directly — no desktop
emulator, no UI automation (no Appium, no WinAppDriver). Connects via TELNET,
interprets the VT100/ANSI escape stream, maintains an in-memory screen buffer,
and exposes a clean Java API.

---

## Architecture

```
base-stratus/
├── connection/    TelnetConnection — socket + TELNET IAC negotiation (via Commons Net)
├── terminal/      Vt100Parser + ScreenBuffer + ScreenSnapshot + TerminalKey
├── session/       StratusSession (public API) + SessionListener
└── config/        StratusConfig (immutable builder)

src/test/
└── testsupport/   MockVt100Server — in-process TELNET/VT100 mock
```

### Layer responsibilities

| Layer | What it does | What it does NOT do |
|-------|-------------|----------------------|
| `connection` | Opens TCP socket; handles TELNET IAC bytes, option negotiation (TERMINAL-TYPE, SUPPRESS-GA, ECHO). Returns clean streams. | Knows nothing about VT100 |
| `terminal` | Parses VT100/ANSI escape sequences byte-by-byte; mutates `ScreenBuffer`; produces immutable `ScreenSnapshot` | Does not touch the network |
| `session` | Wires the layers; runs background reader thread; exposes `sendText`, `sendKey`, `waitForText`, `getScreen` | No protocol details |
| `config` | Immutable value object, builder pattern | No logic |

---

## Quick start

### Run the tests (no network needed)

```bash
mvn test
```

All tests run against the in-process `MockVt100Server`. No real Stratus host required.

To see raw byte logs during tests:

```bash
mvn test -Dstratus.rawCapture=true
```

### Run the end-to-end example against the mock

```bash
mvn package -DskipTests
java -cp target/base-stratus-1.0.0-SNAPSHOT.jar examples/LoginAndNavigate.java --mock
```

### Point to a real Stratus VOS host

Change only these values — no code changes needed:

```bash
java -cp target/base-stratus-1.0.0-SNAPSHOT.jar examples/LoginAndNavigate.java \
     --host your-vos-host.example.com \
     --port 23 \
     --user myuser \
     --pass mypassword
```

Or in code:

```java
StratusConfig config = StratusConfig.builder("your-vos-host.example.com", 23)
        .terminalType("VT100")   // change to "ANSI" if needed
        .size(24, 80)
        .connectTimeoutMs(15_000)
        .rawCapture(false)       // set true for protocol debugging
        .charset("ISO-8859-1")   // adjust if the host uses a different encoding
        .build();
```

---

## API reference

### `StratusSession`

```java
StratusSession s = new StratusSession(config);
s.connect();                              // opens socket, starts reader thread
s.waitForText("Username", 10_000);       // block until text appears or timeout
s.sendText("myuser\r");                  // send text (include \r for Enter)
s.sendKey(TerminalKey.F3);               // send special key
ScreenSnapshot snap = s.getScreen();     // immutable screen state
s.waitForPattern(Pattern.compile("\\$"), 5_000); // regex wait
s.disconnect();                           // or use try-with-resources
```

### `ScreenSnapshot`

```java
snap.getText(row, col, length)      // 1-based, fixed-length region
snap.getTextTrimmed(row, col, len)  // same, trimmed
snap.getLine(row)                   // full 80-char line
snap.getText()                      // all rows joined with \n
snap.containsText("text")           // search anywhere on screen
snap.rowOf("text")                  // 1-based row number, or -1
snap.charAt(row, col)               // single character
snap.toDebugString()                // bordered ASCII dump for logging
```

### `TerminalKey` (selection)

| Constant | Sequence sent |
|----------|--------------|
| `ENTER`  | `\r` |
| `UP / DOWN / LEFT / RIGHT` | `ESC[A/B/D/C` |
| `F1–F12` | xterm sequences (`ESC[11~` … `ESC[24~`) |
| `F1_VT100–F4_VT100` | VT100 app-keypad (`ESC OP` … `ESC OS`) |
| `PAGE_UP / PAGE_DOWN` | `ESC[5~` / `ESC[6~` |
| `BACKSPACE` | `DEL` (0x7F) |
| `CTRL_C` | `ETX` (0x03) |

---

## Connecting to a real Stratus VOS host

When you run from the machine that has network access:

1. Set `host` and `port` in `StratusConfig`.
2. Enable raw-capture for the first run:
   ```java
   .rawCapture(true)
   ```
   or
   ```bash
   -Dstratus.rawCapture=true
   ```
3. Capture the log output. Each received byte is logged as `[RAW] HH HH ... chars`.
4. Verify the escape sequences match standard VT100/ANSI.

---

## Raw-capture / debugging

Raw-capture mode logs every byte received from the host *before* VT100 parsing:

```
[RAW] 1B 5B 48 1B 5B 32 4A 1B 5B 31 3B 31 48   .[H.[2J.[1;1H
[RAW] 57 65 6C 63 6F 6D 65                       Welcome
```

Enable via config (`.rawCapture(true)`) or the system property
`-Dstratus.rawCapture=true` (works at test time with `mvn test -Dstratus.rawCapture=true`).

This is the primary tool for diagnosing protocol differences on a real host.

---

## Protocol assumptions & what to do if they differ

The following assumptions were made based on standard TELNET/VT100 documentation.
None of them have been verified against a live Stratus VOS host yet.

| Assumption | Where it lives | What to adjust if wrong |
|---|---|---|
| Host speaks VT100/ANSI (ECMA-48) escape sequences | `Vt100Parser` | Extend `handleCsi()` or add new states for proprietary sequences |
| Encoding is ISO-8859-1 | `StratusConfig.charset()` | Change to `UTF-8` or another charset |
| TELNET negotiation: server sends DO TERMINAL-TYPE, WILL SUPPRESS-GA, WILL ECHO | `TelnetConnection` | Adjust option handlers or write a raw negotiation if Commons Net's model doesn't fit |
| Screen is 24 rows × 80 columns | `StratusConfig.size()` | Change to 25×80 or 132×whatever the host actually uses |
| Enter sends CR (`\r`) | `TerminalKey.ENTER` | Change sequence to `\r\n` or `\n` |
| F-keys use xterm sequences (`ESC[11~` etc.) | `TerminalKey` | Switch to VT100 app-keypad variants (`F1_VT100`) |
| TELNET port is 23 | `StratusConfig.builder(host, port)` | Change port number |

**Workflow when real host differs:**

1. Enable raw-capture, connect, capture traffic.
2. Compare raw bytes to expected VT100 sequences.
3. If sequences are non-standard: add handling in `Vt100Parser.handleCsi()` or a new state.
4. If negotiation differs: adjust `TelnetConnection` option handlers.
5. The `StratusSession` API and tests remain unchanged — only the parser layer needs updates.

---

## Java version compatibility

Target: **Java 8** source and bytecode (see `pom.xml`). Tested on JDK 8 and JDK 17.

Restrictions applied throughout the codebase:
- No `var`, no records, no text blocks, no switch expressions
- No `List.of()`, `Map.of()` — uses `Arrays.asList()` / `new HashMap<>()`
- No JPMS `module-info.java`
- No APIs introduced after Java 8

---

## Dependencies

| Artifact | Purpose |
|---|---|
| `commons-net:commons-net:3.9.0` | TELNET IAC negotiation (RFC 854/855/856) |
| `slf4j-api` + `slf4j-simple` | Logging (no Logback, no Log4j, no Spring) |
| `junit-jupiter` (test) | Unit and integration tests |
