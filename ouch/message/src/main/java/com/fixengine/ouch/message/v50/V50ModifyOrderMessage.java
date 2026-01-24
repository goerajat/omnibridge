package com.fixengine.ouch.message.v50;

import com.fixengine.ouch.message.OuchMessageType;

/**
 * OUCH 5.0 Modify Order message - modify an existing order in place.
 *
 * <p>Unlike Replace, Modify keeps the same order priority and only changes
 * specific fields. This is new in OUCH 5.0.</p>
 *
 * <p>Message format:</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('M')
 * 1       4       UserRefNum (unsigned int)
 * 5       4       Quantity (new total quantity)
 * 9       1       Display (Y/N/A/Z)
 * 10      4       Minimum Quantity (unsigned int)
 * Total: 14 bytes
 * </pre>
 */
public class V50ModifyOrderMessage extends V50OuchMessage {

    public static final int MSG_TYPE_OFFSET = 0;
    public static final int USER_REF_NUM_OFFSET = 1;
    public static final int QUANTITY_OFFSET = 5;
    public static final int DISPLAY_OFFSET = 9;
    public static final int MIN_QUANTITY_OFFSET = 10;

    public static final int BASE_MESSAGE_LENGTH = 14;

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.MODIFY_ORDER;
    }

    @Override
    public int getBaseMessageLength() {
        return BASE_MESSAGE_LENGTH;
    }

    @Override
    public int getAppendageCountOffset() {
        return -1; // Modify order doesn't support appendages
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

    public char getDisplay() {
        return getChar(DISPLAY_OFFSET);
    }

    public int getMinimumQuantity() {
        return getInt(MIN_QUANTITY_OFFSET);
    }

    // =====================================================
    // Field setters (for sending/writing)
    // =====================================================

    @Override
    public V50ModifyOrderMessage setUserRefNum(long userRefNum) {
        putInt(USER_REF_NUM_OFFSET, (int) userRefNum);
        return this;
    }

    public V50ModifyOrderMessage setQuantity(int quantity) {
        putInt(QUANTITY_OFFSET, quantity);
        return this;
    }

    public V50ModifyOrderMessage setDisplay(char display) {
        putChar(DISPLAY_OFFSET, display);
        return this;
    }

    public V50ModifyOrderMessage setMinimumQuantity(int minQty) {
        putInt(MIN_QUANTITY_OFFSET, minQty);
        return this;
    }

    @Override
    public String toString() {
        return String.format("V50ModifyOrder{userRef=%d, qty=%d, display=%c, minQty=%d}",
                getUserRefNum(), getQuantity(), getDisplay(), getMinimumQuantity());
    }
}
