package com.fixengine.engine.session;

import com.fixengine.message.FixMessage;

/**
 * Listener for FIX application messages.
 * Admin messages (logon, logout, heartbeat, etc.) are handled internally by the engine.
 */
public interface MessageListener {

    /**
     * Called when an application message is received.
     *
     * @param session the session that received the message
     * @param message the received FIX message
     */
    void onMessage(FixSession session, FixMessage message);

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
