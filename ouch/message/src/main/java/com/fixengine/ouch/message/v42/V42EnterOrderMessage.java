package com.fixengine.ouch.message.v42;

import com.fixengine.ouch.message.EnterOrderMessage;
import com.fixengine.ouch.message.OuchVersion;

/**
 * OUCH 4.2 Enter Order message - extends base EnterOrderMessage with V4.2 specifics.
 */
public class V42EnterOrderMessage extends EnterOrderMessage {

    @Override
    public OuchVersion getVersion() {
        return OuchVersion.V42;
    }

    @Override
    public String toString() {
        return String.format("V42EnterOrder{token=%s, side=%c, shares=%d, symbol=%s, price=%.4f, tif=%d}",
                getOrderToken(), getSideCode(), getShares(), getSymbol(),
                getPriceAsDouble(), getTimeInForce());
    }
}
