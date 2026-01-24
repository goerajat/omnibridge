package com.fixengine.ouch.message.v50;

import com.fixengine.ouch.message.OuchMessageType;

/**
 * OUCH 5.0 Cancel Order message - request to cancel an order.
 *
 * <p>Message format:</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('X')
 * 1       4       UserRefNum (unsigned int)
 * 5       4       Quantity (intended remaining, 0 to cancel all)
 * Total: 9 bytes
 * </pre>
 */
public class V50CancelOrderMessage extends V50OuchMessage {

    public static final int MSG_TYPE_OFFSET = 0;
    public static final int USER_REF_NUM_OFFSET = 1;
    public static final int QUANTITY_OFFSET = 5;

    public static final int BASE_MESSAGE_LENGTH = 9;

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.CANCEL_ORDER;
    }

    @Override
    public int getBaseMessageLength() {
        return BASE_MESSAGE_LENGTH;
    }

    @Override
    public int getAppendageCountOffset() {
        return -1; // Cancel order doesn't support appendages
    }

    // =====================================================
    // Field getters (for receiving/reading)
    // =====================================================

    @Override
    public long getUserRefNum() {
        return getUnsignedInt(USER_REF_NUM_OFFSET);
    }

    public int getQuantity() {
        return getInt(QUANTITY_OFFSET);
    }

    public long getQuantityUnsigned() {
        return getUnsignedInt(QUANTITY_OFFSET);
    }

    public boolean isFullCancel() {
        return getQuantity() == 0;
    }

    // =====================================================
    // Field setters (for sending/writing)
    // =====================================================

    @Override
    public V50CancelOrderMessage setUserRefNum(long userRefNum) {
        putInt(USER_REF_NUM_OFFSET, (int) userRefNum);
        return this;
    }

    public V50CancelOrderMessage setQuantity(int quantity) {
        putInt(QUANTITY_OFFSET, quantity);
        return this;
    }

    public V50CancelOrderMessage cancelAll() {
        return setQuantity(0);
    }

    @Override
    public String toString() {
        return String.format("V50CancelOrder{userRef=%d, qty=%d, fullCancel=%b}",
                getUserRefNum(), getQuantity(), isFullCancel());
    }
}
