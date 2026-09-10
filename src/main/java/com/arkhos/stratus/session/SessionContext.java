package com.arkhos.stratus.session;

import com.arkhos.stratus.terminal.ScreenSnapshot;

import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Estado compartido de la sesión.
 *
 * <p>Agrupa las referencias mutables que necesitan tanto {@link StratusActions}
 * como {@link StratusWaiter} sin que ninguna de las dos dependa de la fachada.
 * Clase de uso interno del paquete; no forma parte de la API pública.</p>
 */
final class SessionContext {

    /** Indica si la conexión está activa. */
    final AtomicBoolean connected = new AtomicBoolean(false);

    /** Última instantánea de pantalla recibida del hilo lector. */
    final AtomicReference<ScreenSnapshot> lastSnapshot =
            new AtomicReference<ScreenSnapshot>();

    /** Monitor usado para sincronizar las esperas sobre cambios de pantalla. */
    final Object screenLock = new Object();

    /** Stream de salida hacia el host; se asigna tras {@code connect()}. */
    volatile OutputStream outputStream;

    /** Charset configurado para codificar/decodificar texto. */
    final String charset;

    SessionContext(String charset) {
        this.charset = charset;
    }
}
