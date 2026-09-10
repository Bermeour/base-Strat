package com.arkhos.stratus.session;

import com.arkhos.stratus.terminal.ScreenSnapshot;

/**
 * Event callbacks for a {@link StratusSession}.
 * Implementations must be thread-safe: callbacks arrive from the reader thread.
 */
public interface SessionListener {

    /**
     * Called after every burst of VT100/ANSI data has been parsed and the screen
     * buffer has settled. The snapshot is an immutable copy safe to inspect from
     * any thread.
     */
    void onScreenUpdated(ScreenSnapshot screen);

    /** Called once the TELNET connection is established. */
    default void onConnected() {}

    /** Called when the connection is closed (cleanly or due to error). */
    default void onDisconnected() {}

    /** Called when an I/O error occurs in the reader thread. */
    default void onError(Exception e) {}
}
