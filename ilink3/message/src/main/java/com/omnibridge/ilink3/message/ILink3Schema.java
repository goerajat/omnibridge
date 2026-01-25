package com.omnibridge.ilink3.message;

import com.omnibridge.sbe.message.SbeMessageType;
import com.omnibridge.sbe.message.SbeSchema;

/**
 * CME iLink 3 schema definition.
 * <p>
 * Schema ID: 8
 * Schema Version: 9
 */
public class ILink3Schema implements SbeSchema {

    /** Singleton instance */
    public static final ILink3Schema INSTANCE = new ILink3Schema();

    private ILink3Schema() {
    }

    @Override
    public int getSchemaId() {
        return ILink3Message.SCHEMA_ID;
    }

    @Override
    public int getVersion() {
        return ILink3Message.SCHEMA_VERSION;
    }

    @Override
    public String getName() {
        return "CME iLink 3";
    }

    @Override
    public String getDescription() {
        return "CME iLink 3 v" + getVersion() + " - Order Entry Protocol for CME Globex Markets";
    }

    @Override
    public SbeMessageType getMessageType(int templateId) {
        return ILink3MessageType.fromTemplateId(templateId);
    }

    @Override
    public boolean supportsTemplateId(int templateId) {
        return ILink3MessageType.fromTemplateId(templateId) != ILink3MessageType.UNKNOWN;
    }
}
