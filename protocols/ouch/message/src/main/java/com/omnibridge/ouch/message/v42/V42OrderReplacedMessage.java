package com.omnibridge.ouch.message.v42;

import com.omnibridge.ouch.message.OrderReplacedMessage;
import com.omnibridge.ouch.message.OuchVersion;

/**
 * OUCH 4.2 Order Replaced message - extends base OrderReplacedMessage with V4.2 specifics.
 */
public class V42OrderReplacedMessage extends OrderReplacedMessage {

    @Override
    public OuchVersion getVersion() {
        return OuchVersion.V42;
    }

    @Override
    public String toString() {
        return String.format("V42OrderReplaced{newToken=%s, prevToken=%s, side=%c, shares=%d, symbol=%s, price=%.4f}",
                getReplacementOrderToken(), getPreviousOrderToken(), getSideCode(),
                getShares(), getSymbol(), getPriceAsDouble());
    }
}
