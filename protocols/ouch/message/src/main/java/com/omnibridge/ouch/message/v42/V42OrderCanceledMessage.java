package com.omnibridge.ouch.message.v42;

import com.omnibridge.ouch.message.OrderCanceledMessage;
import com.omnibridge.ouch.message.OuchVersion;

/**
 * OUCH 4.2 Order Canceled message - extends base OrderCanceledMessage with V4.2 specifics.
 */
public class V42OrderCanceledMessage extends OrderCanceledMessage {

    @Override
    public OuchVersion getVersion() {
        return OuchVersion.V42;
    }

    @Override
    public String toString() {
        return String.format("V42OrderCanceled{token=%s, decrement=%d, reason=%c (%s)}",
                getOrderToken(), getDecrementShares(), getReason(), getReasonDescription());
    }
}
