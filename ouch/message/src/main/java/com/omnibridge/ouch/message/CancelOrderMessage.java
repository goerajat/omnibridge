package com.omnibridge.ouch.message;

/**
 * Cancel Order message - request to cancel an order.
 *
 * <p>OUCH Cancel Order message format (based on OUCH 4.2):</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('X')
 * 1       14      Order Token (alpha)
 * 15      4       Shares (intended remaining size, 0 to cancel all)
 * Total: 19 bytes
 * </pre>
 */
public class CancelOrderMessage extends OuchMessage {

    public static final int MSG_TYPE_OFFSET = 0;
    public static final int ORDER_TOKEN_OFFSET = 1;
    public static final int ORDER_TOKEN_LENGTH = 14;
    public static final int SHARES_OFFSET = 15;

    public static final int MESSAGE_LENGTH = 19;

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.CANCEL_ORDER;
    }

    @Override
    public int getMessageLength() {
        return MESSAGE_LENGTH;
    }

    // =====================================================
    // Field getters (for receiving/reading)
    // =====================================================

    /**
     * Get the order token.
     */
    public String getOrderToken() {
        return getAlpha(ORDER_TOKEN_OFFSET, ORDER_TOKEN_LENGTH);
    }

    /**
     * Get the intended remaining shares.
     * 0 means cancel the entire order.
     */
    public int getShares() {
        return getInt(SHARES_OFFSET);
    }

    /**
     * Get the shares as unsigned value.
     */
    public long getSharesUnsigned() {
        return getUnsignedInt(SHARES_OFFSET);
    }

    /**
     * Check if this is a full cancel (shares == 0).
     */
    public boolean isFullCancel() {
        return getShares() == 0;
    }

    // =====================================================
    // Field setters (for sending/writing)
    // =====================================================

    public CancelOrderMessage setOrderToken(String token) {
        putAlpha(ORDER_TOKEN_OFFSET, token, ORDER_TOKEN_LENGTH);
        return this;
    }

    /**
     * Set the intended remaining shares.
     * Use 0 to cancel the entire order.
     */
    public CancelOrderMessage setShares(int shares) {
        putInt(SHARES_OFFSET, shares);
        return this;
    }

    /**
     * Cancel the entire order (set shares to 0).
     */
    public CancelOrderMessage cancelAll() {
        return setShares(0);
    }

    @Override
    public String toString() {
        return String.format("CancelOrder{token=%s, shares=%d, fullCancel=%b}",
                getOrderToken(), getShares(), isFullCancel());
    }
}
