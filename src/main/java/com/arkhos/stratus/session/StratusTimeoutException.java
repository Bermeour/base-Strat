package com.arkhos.stratus.session;

/**
 * Se lanza cuando una operación de espera no encuentra la condición esperada
 * antes de que se agote el tiempo máximo configurado.
 *
 * <p>Es una excepción no comprobada ({@link RuntimeException}) para que el
 * encadenamiento de llamadas no obligue al llamador a envolver cada paso
 * en un bloque try-catch cuando el timeout es un error fatal en el flujo.</p>
 */
public final class StratusTimeoutException extends RuntimeException {

    public StratusTimeoutException(String mensaje) {
        super(mensaje);
    }

    public StratusTimeoutException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
