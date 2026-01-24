package com.fixengine.ouch.message.v50;

import com.fixengine.ouch.message.OuchMessageType;

/**
 * OUCH 5.0 Mass Cancel message - cancel multiple orders at once.
 *
 * <p>This message allows cancellation of orders based on criteria.
 * New in OUCH 5.0.</p>
 *
 * <p>Message format:</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('Y')
 * 1       4       MassCancelId (unsigned int, client's cancel ID)
 * 5       8       Symbol (alpha, blank for all symbols)
 * 13      1       Side (B/S/blank for both)
 * 14      4       LowerUserRef (unsigned int, 0 for no lower bound)
 * 18      4       UpperUserRef (unsigned int, 0xFFFFFFFF for no upper bound)
 * Total: 22 bytes
 * </pre>
 */
public class V50MassCancelMessage extends V50OuchMessage {

    public static final int MSG_TYPE_OFFSET = 0;
    public static final int MASS_CANCEL_ID_OFFSET = 1;
    public static final int SYMBOL_OFFSET = 5;
    public static final int SYMBOL_LENGTH = 8;
    public static final int SIDE_OFFSET = 13;
    public static final int LOWER_USER_REF_OFFSET = 14;
    public static final int UPPER_USER_REF_OFFSET = 18;

    public static final int BASE_MESSAGE_LENGTH = 22;

    // Side values for mass cancel
    public static final char SIDE_BUY = 'B';
    public static final char SIDE_SELL = 'S';
    public static final char SIDE_BOTH = ' ';

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.CANCEL_BY_ORDER_ID;
    }

    @Override
    public int getBaseMessageLength() {
        return BASE_MESSAGE_LENGTH;
    }

    @Override
    public int getAppendageCountOffset() {
        return -1; // Mass cancel doesn't support appendages
    }

    // =====================================================
    // Field getters (for receiving/reading)
    // =====================================================

    public long getMassCancelId() {
        return getUnsignedInt(MASS_CANCEL_ID_OFFSET);
    }

    @Override
    public long getUserRefNum() {
        return getMassCancelId();
    }

    public String getSymbol() {
        return getAlpha(SYMBOL_OFFSET, SYMBOL_LENGTH);
    }

    public boolean isAllSymbols() {
        return getSymbol().isBlank();
    }

    public char getSide() {
        return getChar(SIDE_OFFSET);
    }

    public boolean isBothSides() {
        return getSide() == SIDE_BOTH;
    }

    public long getLowerUserRef() {
        return getUnsignedInt(LOWER_USER_REF_OFFSET);
    }

    public long getUpperUserRef() {
        return getUnsignedInt(UPPER_USER_REF_OFFSET);
    }

    // =====================================================
    // Field setters (for sending/writing)
    // =====================================================

    public V50MassCancelMessage setMassCancelId(long massCancelId) {
        putInt(MASS_CANCEL_ID_OFFSET, (int) massCancelId);
        return this;
    }

    @Override
    public V50MassCancelMessage setUserRefNum(long userRefNum) {
        return setMassCancelId(userRefNum);
    }

    public V50MassCancelMessage setSymbol(String symbol) {
        putAlpha(SYMBOL_OFFSET, symbol, SYMBOL_LENGTH);
        return this;
    }

    public V50MassCancelMessage setAllSymbols() {
        return setSymbol("        ");
    }

    public V50MassCancelMessage setSide(char side) {
        putChar(SIDE_OFFSET, side);
        return this;
    }

    public V50MassCancelMessage setBothSides() {
        return setSide(SIDE_BOTH);
    }

    public V50MassCancelMessage setLowerUserRef(long lowerUserRef) {
        putInt(LOWER_USER_REF_OFFSET, (int) lowerUserRef);
        return this;
    }

    public V50MassCancelMessage setUpperUserRef(long upperUserRef) {
        putInt(UPPER_USER_REF_OFFSET, (int) upperUserRef);
        return this;
    }

    public V50MassCancelMessage setUserRefRange(long lower, long upper) {
        setLowerUserRef(lower);
        setUpperUserRef(upper);
        return this;
    }

    public V50MassCancelMessage cancelAllOrders() {
        setAllSymbols();
        setBothSides();
        setLowerUserRef(0);
        setUpperUserRef(0xFFFFFFFFL);
        return this;
    }

    @Override
    public String toString() {
        return String.format("V50MassCancel{id=%d, symbol=%s, side=%c, range=[%d,%d]}",
                getMassCancelId(), isAllSymbols() ? "*" : getSymbol(),
                getSide(), getLowerUserRef(), getUpperUserRef());
    }
}
