package com.arkhos.stratus.terminal;

/**
 * Secuencias de teclas VT100/ANSI para enviar al host.
 *
 * <p>Las secuencias siguen el estandar ANSI/VT100 (ECMA-48). Algunos hosts
 * responden a las secuencias de modo application-keypad (prefijo ESC O) para
 * F1-F4; otros usan las secuencias xterm/VT220 (ESC [ n ~). Se proveen ambas
 * variantes donde corresponde.</p>
 *
 * <p>Al conectar a un host Stratus VOS real, usa el modo raw-capture para
 * confirmar que secuencias espera el host para las teclas de funcion.</p>
 *
 * <p>Para generar la secuencia de cualquier combinacion Ctrl+letra en tiempo de
 * ejecucion, usa el metodo estatico {@link #ctrl(char)}.</p>
 */
public enum TerminalKey {

    // Teclas basicas

    ENTER("\r"),
    NEWLINE("\n"),
    TAB("	"),
    BACKSPACE(""),    // DEL (0x7F)
    BACKSPACE_BS(""),     // BS (0x08)

    ESCAPE(""),

    // Teclas de cursor (modo normal)

    UP   ("[A"),
    DOWN ("[B"),
    RIGHT("[C"),
    LEFT ("[D"),

    // Teclas de cursor (modo application)

    UP_APP   ("OA"),
    DOWN_APP ("OB"),
    RIGHT_APP("OC"),
    LEFT_APP ("OD"),

    // Navegacion

    HOME     ("[H"),
    END      ("[F"),
    INSERT   ("[2~"),
    DELETE   ("[3~"),
    PAGE_UP  ("[5~"),
    PAGE_DOWN("[6~"),

    // Teclas de funcion (xterm/VT220)

    F1 ("[11~"),
    F2 ("[12~"),
    F3 ("[13~"),
    F4 ("[14~"),
    F5 ("[15~"),
    F6 ("[17~"),
    F7 ("[18~"),
    F8 ("[19~"),
    F9 ("[20~"),
    F10("[21~"),
    F11("[23~"),
    F12("[24~"),

    // Teclas de funcion (VT100 application-keypad, F1-F4)

    F1_VT100("OP"),
    F2_VT100("OQ"),
    F3_VT100("OR"),
    F4_VT100("OS"),

    // Combinaciones Ctrl (A-Z completo)

    CTRL_A(""),   // inicio de linea (readline)
    CTRL_B(""),   // retroceder un caracter (readline)
    CTRL_C(""),   // senal de interrupcion (SIGINT)
    CTRL_D(""),   // fin de archivo / cerrar conexion
    CTRL_E(""),   // fin de linea (readline)
    CTRL_F(""),   // avanzar un caracter (readline)
    CTRL_G(""),   // campana (BEL)
    CTRL_H(""),       // retroceso (BS) — equivalente a BACKSPACE_BS
    CTRL_I("	"),       // tabulador — equivalente a TAB
    CTRL_J("\n"),       // avance de linea (LF)
    CTRL_K(""),   // borrar hasta fin de linea (readline)
    CTRL_L(""),   // form-feed; refresca la pantalla en muchos shells
    CTRL_M("\r"),       // retorno de carro (CR) — equivalente a ENTER
    CTRL_N(""),   // siguiente elemento del historial (readline)
    CTRL_O(""),   // ejecutar y avanzar (readline)
    CTRL_P(""),   // elemento anterior del historial (readline)
    CTRL_Q(""),   // reanudar flujo de salida (XON)
    CTRL_R(""),   // busqueda inversa en historial (readline)
    CTRL_S(""),   // detener flujo de salida (XOFF)
    CTRL_T(""),   // transponer caracteres (readline)
    CTRL_U(""),   // borrar desde inicio de linea (readline)
    CTRL_V(""),   // insertar siguiente caracter en crudo (readline)
    CTRL_W(""),   // borrar palabra anterior (readline)
    CTRL_X(""),   // prefijo de atajos en algunos editores
    CTRL_Y(""),   // pegar texto borrado (readline)
    CTRL_Z(""),   // senal de suspension (SIGTSTP)

    // Teclado numerico (modo application)

    KP_0("Op"),     KP_1("Oq"),     KP_2("Or"),
    KP_3("Os"),     KP_4("Ot"),     KP_5("Ou"),
    KP_6("Ov"),     KP_7("Ow"),     KP_8("Ox"),
    KP_9("Oy"),     KP_MINUS("Om"), KP_COMMA("Ol"),
    KP_PERIOD("On"), KP_ENTER("OM");

    // Implementacion

    private final String secuencia;

    TerminalKey(String secuencia) {
        this.secuencia = secuencia;
    }

    /** La secuencia de bytes que se envia al host para esta tecla. */
    public String sequence() {
        return secuencia;
    }

    /**
     * Retorna los bytes de la secuencia codificados en el charset indicado
     * (tipicamente ISO-8859-1 o UTF-8).
     */
    public byte[] bytes(String charset) {
        try {
            return secuencia.getBytes(charset);
        } catch (Exception e) {
            return secuencia.getBytes();
        }
    }

    // Metodo utilitario estatico

    /**
     * Genera la secuencia de control para cualquier combinacion Ctrl+letra
     * en tiempo de ejecucion, sin necesidad de usar una constante del enum.
     *
     * <p>Ejemplo de uso:</p>
     * <pre>
     *   session.sendRaw(TerminalKey.ctrl('X'));  // envia Ctrl+X (0x18)
     *   session.sendRaw(TerminalKey.ctrl('c'));  // envia Ctrl+C (0x03)
     * </pre>
     *
     * @param letra letra de la A a la Z (mayuscula o minuscula)
     * @return array de un byte con el codigo de control correspondiente
     * @throws IllegalArgumentException si el caracter no esta entre A-Z / a-z
     */
    public static byte[] ctrl(char letra) {
        char upper = Character.toUpperCase(letra);
        if (upper < 'A' || upper > 'Z') {
            throw new IllegalArgumentException(
                    "Ctrl solo acepta letras A-Z, recibido: '" + letra + "'");
        }
        return new byte[]{(byte) (upper - 0x40)};
    }
}
