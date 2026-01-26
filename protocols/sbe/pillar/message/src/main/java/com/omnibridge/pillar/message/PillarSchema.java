package com.omnibridge.pillar.message;

import com.omnibridge.sbe.message.SbeMessageType;
import com.omnibridge.sbe.message.SbeSchema;

/**
 * NYSE Pillar protocol schema metadata.
 */
public final class PillarSchema implements SbeSchema {

    /** Singleton instance */
    public static final PillarSchema INSTANCE = new PillarSchema();

    private PillarSchema() {}

    @Override
    public int getSchemaId() {
        return PillarMessage.SCHEMA_ID;
    }

    @Override
    public int getVersion() {
        return PillarMessage.SCHEMA_VERSION;
    }

    @Override
    public String getName() {
        return "NYSE Pillar";
    }

    @Override
    public String getDescription() {
        return "NYSE Pillar Binary Gateway Protocol v" + getVersion();
    }

    @Override
    public SbeMessageType getMessageType(int templateId) {
        return PillarMessageType.fromTemplateId(templateId);
    }

    @Override
    public boolean supportsTemplateId(int templateId) {
        return PillarMessageType.fromTemplateId(templateId) != PillarMessageType.UNKNOWN;
    }

    /**
     * Gets the message header size.
     */
    public int getMessageHeaderSize() {
        return PillarMessage.MSG_HEADER_SIZE;
    }

    /**
     * Gets the SeqMsg header size for sequenced messages.
     */
    public int getSeqMsgHeaderSize() {
        return PillarMessage.SEQ_MSG_HEADER_SIZE;
    }

    /**
     * Gets the price scale factor.
     */
    public long getPriceScale() {
        return PillarMessage.PRICE_SCALE;
    }
}
