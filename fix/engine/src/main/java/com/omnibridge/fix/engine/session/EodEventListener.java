package com.omnibridge.fix.engine.session;

/**
 * Listener interface for End of Day (EOD) events.
 */
public interface EodEventListener {

    /**
     * Called when an EOD event occurs for a session.
     *
     * @param session the session that triggered EOD
     * @param event the EOD event details
     */
    void onEodEvent(FixSession session, EodEvent event);
}
