package com.fixengine.ouch.message.v42;

import com.fixengine.ouch.message.OrderAcceptedMessage;
import com.fixengine.ouch.message.OuchVersion;

/**
 * OUCH 4.2 Order Accepted message - extends base OrderAcceptedMessage with V4.2 specifics.
 */
public class V42OrderAcceptedMessage extends OrderAcceptedMessage {

    @Override
    public OuchVersion getVersion() {
        return OuchVersion.V42;
    }

    @Override
    public String toString() {
        return String.format("V42OrderAccepted{token=%s, side=%c, shares=%d, symbol=%s, price=%.4f, orderRef=%d, state=%c}",
                getOrderToken(), getSideCode(), getShares(), getSymbol(),
                getPriceAsDouble(), getOrderReferenceNumber(), getOrderState());
    }
}
