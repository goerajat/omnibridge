package com.omnibridge.ilink3.message.session;

import com.omnibridge.ilink3.message.ILink3Message;
import com.omnibridge.ilink3.message.ILink3MessageType;

/**
 * iLink 3 Establish message (Template ID 503).
 * <p>
 * Sent by the client after successful negotiation to establish the session.
 * <p>
 * Block Layout:
 * <pre>
 * Offset  Length  Field
 * 0       32      HMACSignature
 * 32      3       AccessKeyID
 * 35      5       Reserved
 * 40      8       UUID
 * 48      8       RequestTimestamp
 * 56      4       NextSeqNo
 * 60      3       Session
 * 63      3       Firm
 * 66      2       KeepAliveInterval
 * 68      8       Reserved
 * 76      2       Credentials Length (var data header)
 * 78      var     Credentials
 * </pre>
 */
public class EstablishMessage extends ILink3Message {

    public static final int TEMPLATE_ID = 503;
    public static final int BLOCK_LENGTH = 84;

    // Field offsets
    private static final int HMAC_SIGNATURE_OFFSET = 0;
    private static final int HMAC_SIGNATURE_LENGTH = 32;
    private static final int ACCESS_KEY_ID_OFFSET = 32;
    private static final int ACCESS_KEY_ID_LENGTH = 3;
    private static final int UUID_OFFSET = 40;
    private static final int REQUEST_TIMESTAMP_OFFSET = 48;
    private static final int NEXT_SEQ_NO_OFFSET = 56;
    private static final int SESSION_OFFSET = 60;
    private static final int SESSION_LENGTH = 3;
    private static final int FIRM_OFFSET = 63;
    private static final int FIRM_LENGTH = 3;
    private static final int KEEP_ALIVE_INTERVAL_OFFSET = 66;

    @Override
    public int getTemplateId() {
        return TEMPLATE_ID;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public ILink3MessageType getMessageType() {
        return ILink3MessageType.ESTABLISH;
    }

    // HMAC Signature (32 bytes)
    public EstablishMessage setHmacSignature(byte[] signature) {
        if (signature != null && signature.length >= HMAC_SIGNATURE_LENGTH) {
            putBytes(HMAC_SIGNATURE_OFFSET, signature, 0, HMAC_SIGNATURE_LENGTH);
        }
        return this;
    }

    public byte[] getHmacSignature() {
        byte[] signature = new byte[HMAC_SIGNATURE_LENGTH];
        getBytes(HMAC_SIGNATURE_OFFSET, signature, 0, HMAC_SIGNATURE_LENGTH);
        return signature;
    }

    // Access Key ID (3 bytes)
    public EstablishMessage setAccessKeyId(String accessKeyId) {
        putString(ACCESS_KEY_ID_OFFSET, ACCESS_KEY_ID_LENGTH, accessKeyId);
        return this;
    }

    public String getAccessKeyId() {
        return getString(ACCESS_KEY_ID_OFFSET, ACCESS_KEY_ID_LENGTH);
    }

    // UUID (8 bytes)
    public EstablishMessage setUuid(long uuid) {
        putLong(UUID_OFFSET, uuid);
        return this;
    }

    public long getUuid() {
        return getLong(UUID_OFFSET);
    }

    // Request Timestamp (8 bytes, nanoseconds since epoch)
    public EstablishMessage setRequestTimestamp(long timestamp) {
        putLong(REQUEST_TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    public long getRequestTimestamp() {
        return getLong(REQUEST_TIMESTAMP_OFFSET);
    }

    // Next Sequence Number (4 bytes)
    public EstablishMessage setNextSeqNo(long seqNo) {
        putInt(NEXT_SEQ_NO_OFFSET, (int) seqNo);
        return this;
    }

    public long getNextSeqNo() {
        return getUnsignedInt(NEXT_SEQ_NO_OFFSET);
    }

    // Session (3 bytes)
    public EstablishMessage setSession(String session) {
        putString(SESSION_OFFSET, SESSION_LENGTH, session);
        return this;
    }

    public String getSession() {
        return getString(SESSION_OFFSET, SESSION_LENGTH);
    }

    // Firm (3 bytes)
    public EstablishMessage setFirm(String firm) {
        putString(FIRM_OFFSET, FIRM_LENGTH, firm);
        return this;
    }

    public String getFirm() {
        return getString(FIRM_OFFSET, FIRM_LENGTH);
    }

    // Keep Alive Interval (2 bytes, milliseconds)
    public EstablishMessage setKeepAliveInterval(int interval) {
        putShort(KEEP_ALIVE_INTERVAL_OFFSET, (short) interval);
        return this;
    }

    public int getKeepAliveInterval() {
        return getUnsignedShort(KEEP_ALIVE_INTERVAL_OFFSET);
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "EstablishMessage[not wrapped]";
        }
        return String.format("EstablishMessage[uuid=%d, firm=%s, session=%s, nextSeqNo=%d, keepAlive=%d]",
                getUuid(), getFirm(), getSession(), getNextSeqNo(), getKeepAliveInterval());
    }
}
