package com.omnibridge.ouch.message.v42;

import com.omnibridge.ouch.message.OuchVersion;
import com.omnibridge.ouch.message.ReplaceOrderMessage;

/**
 * OUCH 4.2 Replace Order message - extends base ReplaceOrderMessage with V4.2 specifics.
 */
public class V42ReplaceOrderMessage extends ReplaceOrderMessage {

    @Override
    public OuchVersion getVersion() {
        return OuchVersion.V42;
    }

    @Override
    public String toString() {
        return String.format("V42ReplaceOrder{existingToken=%s, newToken=%s, shares=%d, price=%.4f}",
                getExistingOrderToken(), getReplacementOrderToken(), getShares(), getPriceAsDouble());
    }
}
