package com.arkhos.stratus.session;

import com.arkhos.stratus.terminal.ScreenSnapshot;

/**
 * Callbacks de eventos para una {@link StratusSession}.
 *
 * <p>Las implementaciones deben ser hilo-seguras: los callbacks llegan desde
 * el hilo lector en segundo plano, no desde el hilo que llama a la sesión.</p>
 */
public interface SessionListener {

    /**
     * Llamado tras cada ráfaga de datos VT100/ANSI que haya sido parseada
     * y reflejada en el buffer de pantalla. El snapshot es una copia inmutable
     * segura para inspeccionar desde cualquier hilo.
     */
    void onScreenUpdated(ScreenSnapshot screen);

    /** Llamado una vez establecida la conexión TELNET. */
    default void onConnected() {}

    /** Llamado cuando la conexión se cierra (normalmente o por error). */
    default void onDisconnected() {}

    /** Llamado cuando ocurre un error de E/S en el hilo lector. */
    default void onError(Exception e) {}
}
