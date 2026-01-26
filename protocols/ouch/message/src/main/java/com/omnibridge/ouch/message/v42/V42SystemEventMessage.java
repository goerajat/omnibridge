package com.omnibridge.ouch.message.v42;

import com.omnibridge.ouch.message.OuchVersion;
import com.omnibridge.ouch.message.SystemEventMessage;

/**
 * OUCH 4.2 System Event message - extends base SystemEventMessage with V4.2 specifics.
 */
public class V42SystemEventMessage extends SystemEventMessage {

    @Override
    public OuchVersion getVersion() {
        return OuchVersion.V42;
    }

    @Override
    public String toString() {
        return String.format("V42SystemEvent{event=%c (%s), timestamp=%d}",
                getEventCode(), getEventDescription(), getTimestamp());
    }
}
