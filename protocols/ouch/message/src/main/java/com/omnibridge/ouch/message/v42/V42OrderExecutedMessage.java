package com.omnibridge.ouch.message.v42;

import com.omnibridge.ouch.message.OrderExecutedMessage;
import com.omnibridge.ouch.message.OuchVersion;

/**
 * OUCH 4.2 Order Executed message - extends base OrderExecutedMessage with V4.2 specifics.
 */
public class V42OrderExecutedMessage extends OrderExecutedMessage {

    @Override
    public OuchVersion getVersion() {
        return OuchVersion.V42;
    }

    @Override
    public String toString() {
        return String.format("V42OrderExecuted{token=%s, shares=%d, price=%.4f, liquidity=%c, match=%d}",
                getOrderToken(), getExecutedShares(), getExecutionPriceAsDouble(),
                getLiquidityFlag(), getMatchNumber());
    }
}
