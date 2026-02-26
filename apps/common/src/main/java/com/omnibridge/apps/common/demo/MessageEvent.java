package com.omnibridge.apps.common.demo;

import java.util.Map;

/**
 * Represents a FIX message event (sent or received) associated with an order.
 */
public class MessageEvent {

    private final long timestamp;
    private final String direction;
    private final String msgType;
    private final String msgTypeName;
    private final Map<String, String> fields;
    private final String rawMessage;

    public MessageEvent(long timestamp, String direction, String msgType,
                        String msgTypeName, Map<String, String> fields, String rawMessage) {
        this.timestamp = timestamp;
        this.direction = direction;
        this.msgType = msgType;
        this.msgTypeName = msgTypeName;
        this.fields = fields;
        this.rawMessage = rawMessage;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getDirection() {
        return direction;
    }

    public String getMsgType() {
        return msgType;
    }

    public String getMsgTypeName() {
        return msgTypeName;
    }

    public Map<String, String> getFields() {
        return fields;
    }

    public String getRawMessage() {
        return rawMessage;
    }
}
