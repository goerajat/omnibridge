package com.omnibridge.optiq.message;

import com.omnibridge.sbe.message.SbeMessageType;
import com.omnibridge.sbe.message.SbeSchema;

/**
 * Euronext Optiq schema definition.
 * <p>
 * Schema ID: 0
 * Schema Version: 1
 */
public class OptiqSchema implements SbeSchema {

    /** Singleton instance */
    public static final OptiqSchema INSTANCE = new OptiqSchema();

    private OptiqSchema() {
    }

    @Override
    public int getSchemaId() {
        return OptiqMessage.SCHEMA_ID;
    }

    @Override
    public int getVersion() {
        return OptiqMessage.SCHEMA_VERSION;
    }

    @Override
    public String getName() {
        return "Euronext Optiq";
    }

    @Override
    public String getDescription() {
        return "Euronext Optiq v" + getVersion() + " - Order Entry Gateway Protocol";
    }

    @Override
    public SbeMessageType getMessageType(int templateId) {
        return OptiqMessageType.fromTemplateId(templateId);
    }

    @Override
    public boolean supportsTemplateId(int templateId) {
        return OptiqMessageType.fromTemplateId(templateId) != OptiqMessageType.UNKNOWN;
    }
}
