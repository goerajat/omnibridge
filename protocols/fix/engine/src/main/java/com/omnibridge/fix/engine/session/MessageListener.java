package com.omnibridge.fix.engine.session;

import com.omnibridge.fix.message.IncomingFixMessage;

/**
 * Listener for FIX application messages.
 * Admin messages (logon, logout, heartbeat, etc.) are handled internally by the engine.
 *
 * <p>The {@link #onMessage(FixSession, IncomingFixMessage)} method is called with an
 * IncomingFixMessage. When pooling is enabled, the message is automatically released
 * back to the pool after the callback completes, so do NOT hold references to the
 * message or its field values after the callback returns.</p>
 *
 * <p>If you need to retain any values from a pooled message, copy them to Strings
 * before the callback returns.</p>
 */
public interface MessageListener {

    /**
     * Called when an application message is received.
     *
     * <p>IMPORTANT: When message pooling is enabled, the message and any CharSequence
     * values obtained from it are only valid during this callback. After the callback
     * returns, the message may be released back to the pool. If you need to retain
     * any values, copy them to Strings before returning.</p>
     *
     * @param session the session that received the message
     * @param message the received incoming FIX message
     */
    void onMessage(FixSession session, IncomingFixMessage message);

    /**
     * Called when a message is rejected by the counterparty.
     *
     * @param session the session
     * @param refSeqNum the sequence number of the rejected message
     * @param refMsgType the message type of the rejected message
     * @param rejectReason the rejection reason
     * @param text additional text (may be null)
     */
    default void onReject(FixSession session, int refSeqNum, String refMsgType,
                          int rejectReason, String text) {
    }

    /**
     * Called when a business message is rejected.
     *
     * @param session the session
     * @param refSeqNum the sequence number of the rejected message
     * @param businessRejectReason the rejection reason
     * @param text additional text (may be null)
     */
    default void onBusinessReject(FixSession session, int refSeqNum,
                                  int businessRejectReason, String text) {
    }
}
