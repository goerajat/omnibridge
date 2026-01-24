package com.fixengine.ouch.message.v42;

import com.fixengine.ouch.message.CancelOrderMessage;
import com.fixengine.ouch.message.OuchVersion;

/**
 * OUCH 4.2 Cancel Order message - extends base CancelOrderMessage with V4.2 specifics.
 */
public class V42CancelOrderMessage extends CancelOrderMessage {

    @Override
    public OuchVersion getVersion() {
        return OuchVersion.V42;
    }

    @Override
    public String toString() {
        return String.format("V42CancelOrder{token=%s, shares=%d, fullCancel=%b}",
                getOrderToken(), getShares(), isFullCancel());
    }
}
