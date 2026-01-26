package com.omnibridge.ilink3.message.session;

import com.omnibridge.ilink3.message.ILink3Message;
import com.omnibridge.ilink3.message.ILink3MessageType;

/**
 * iLink 3 Negotiate message (Template ID 500).
 * <p>
 * Sent by the client to initiate a session with the CME Globex system.
 * Contains credentials and session parameters.
 * <p>
 * Block Layout:
 * <pre>
 * Offset  Length  Field
 * 0       4       HMACSignature (first 4 bytes)
 * 4       32      HMACSignature (remaining)
 * 36      3       AccessKeyID
 * 39      5       Reserved
 * 44      8       UUID
 * 52      8       RequestTimestamp
 * 60      3       Session
 * 63      3       Firm
 * 66      6       Reserved
 * 72      2       Credentials Length (var data header)
 * 74      var     Credentials
 * </pre>
 */
public class NegotiateMessage extends ILink3Message {

    public static final int TEMPLATE_ID = 500;
    public static final int BLOCK_LENGTH = 76;

    // Field offsets
    private static final int HMAC_SIGNATURE_OFFSET = 0;
    private static final int HMAC_SIGNATURE_LENGTH = 32;
    private static final int ACCESS_KEY_ID_OFFSET = 36;
    private static final int ACCESS_KEY_ID_LENGTH = 3;
    private static final int UUID_OFFSET = 44;
    private static final int REQUEST_TIMESTAMP_OFFSET = 52;
    private static final int SESSION_OFFSET = 60;
    private static final int SESSION_LENGTH = 3;
    private static final int FIRM_OFFSET = 63;
    private static final int FIRM_LENGTH = 3;

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
        return ILink3MessageType.NEGOTIATE;
    }

    // HMAC Signature (32 bytes)
    public NegotiateMessage setHmacSignature(byte[] signature) {
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
    public NegotiateMessage setAccessKeyId(String accessKeyId) {
        putString(ACCESS_KEY_ID_OFFSET, ACCESS_KEY_ID_LENGTH, accessKeyId);
        return this;
    }

    public String getAccessKeyId() {
        return getString(ACCESS_KEY_ID_OFFSET, ACCESS_KEY_ID_LENGTH);
    }

    // UUID (8 bytes)
    public NegotiateMessage setUuid(long uuid) {
        putLong(UUID_OFFSET, uuid);
        return this;
    }

    public long getUuid() {
        return getLong(UUID_OFFSET);
    }

    // Request Timestamp (8 bytes, nanoseconds since epoch)
    public NegotiateMessage setRequestTimestamp(long timestamp) {
        putLong(REQUEST_TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    public long getRequestTimestamp() {
        return getLong(REQUEST_TIMESTAMP_OFFSET);
    }

    // Session (3 bytes)
    public NegotiateMessage setSession(String session) {
        putString(SESSION_OFFSET, SESSION_LENGTH, session);
        return this;
    }

    public String getSession() {
        return getString(SESSION_OFFSET, SESSION_LENGTH);
    }

    // Firm (3 bytes)
    public NegotiateMessage setFirm(String firm) {
        putString(FIRM_OFFSET, FIRM_LENGTH, firm);
        return this;
    }

    public String getFirm() {
        return getString(FIRM_OFFSET, FIRM_LENGTH);
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "NegotiateMessage[not wrapped]";
        }
        return String.format("NegotiateMessage[uuid=%d, firm=%s, session=%s]",
                getUuid(), getFirm(), getSession());
    }
}
