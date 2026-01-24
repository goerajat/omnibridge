package com.fixengine.ouch.message.v42;

import com.fixengine.ouch.message.OrderRejectedMessage;
import com.fixengine.ouch.message.OuchVersion;

/**
 * OUCH 4.2 Order Rejected message - extends base OrderRejectedMessage with V4.2 specifics.
 */
public class V42OrderRejectedMessage extends OrderRejectedMessage {

    @Override
    public OuchVersion getVersion() {
        return OuchVersion.V42;
    }

    @Override
    public String toString() {
        return String.format("V42OrderRejected{token=%s, reason=%c (%s)}",
                getOrderToken(), getRejectReason(), getRejectReasonDescription());
    }
}
