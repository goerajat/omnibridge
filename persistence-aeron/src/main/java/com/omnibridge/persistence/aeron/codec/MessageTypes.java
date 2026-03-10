package com.omnibridge.persistence.aeron.codec;

/**
 * Template ID constants and schema metadata for the Aeron persistence SBE protocol.
 */
public final class MessageTypes {

    public static final int SCHEMA_ID = 100;
    public static final int SCHEMA_VERSION = 1;

    public static final int LOG_ENTRY = 1;
    public static final int REPLAY_REQUEST = 2;
    public static final int REPLAY_ENTRY = 3;
    public static final int REPLAY_COMPLETE = 4;
    public static final int STREAM_INFO_REQUEST = 5;
    public static final int STREAM_INFO_RESPONSE = 6;
    public static final int HEARTBEAT = 7;
    public static final int ACK = 8;
    public static final int CATCH_UP_REQUEST = 9;

    // Aeron stream IDs
    public static final int DATA_STREAM_ID = 1;
    public static final int CONTROL_STREAM_ID = 10;
    public static final int REPLAY_STREAM_ID = 20;

    // ReplayComplete status codes
    public static final byte STATUS_SUCCESS = 0;
    public static final byte STATUS_PARTIAL = 1;
    public static final byte STATUS_ERROR = 2;

    // Ack status codes
    public static final byte ACK_OK = 0;
    public static final byte ACK_ERROR = 1;

    // Direction values for ReplayRequest
    public static final byte DIRECTION_BOTH = 0;
    public static final byte DIRECTION_INBOUND = 1;
    public static final byte DIRECTION_OUTBOUND = 2;

    private MessageTypes() {
    }
}
