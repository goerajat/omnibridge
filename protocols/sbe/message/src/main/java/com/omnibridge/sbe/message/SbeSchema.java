package com.omnibridge.sbe.message;

/**
 * Represents an SBE schema definition.
 * <p>
 * Each SBE-based protocol has a unique schema that defines:
 * <ul>
 *   <li>Schema ID - Unique identifier for the schema</li>
 *   <li>Schema Version - Version number of the schema</li>
 *   <li>Message Types - Set of messages defined by the schema</li>
 *   <li>Byte Order - Usually little-endian for SBE</li>
 * </ul>
 * <p>
 * Known schemas:
 * <ul>
 *   <li>CME iLink 3 - Schema ID 8</li>
 *   <li>Euronext Optiq - Schema ID 0</li>
 * </ul>
 * <p>
 * Protocol implementations should create concrete implementations of this
 * interface to define their schema metadata.
 */
public interface SbeSchema {

    /**
     * Gets the unique identifier for this schema.
     *
     * @return the schema ID
     */
    int getSchemaId();

    /**
     * Gets the version of this schema.
     *
     * @return the schema version
     */
    int getVersion();

    /**
     * Gets a human-readable name for this schema.
     *
     * @return the schema name
     */
    String getName();

    /**
     * Gets a description of this schema.
     *
     * @return the schema description
     */
    default String getDescription() {
        return getName() + " v" + getVersion();
    }

    /**
     * Looks up a message type by its template ID.
     *
     * @param templateId the template ID
     * @return the message type, or null if not found
     */
    SbeMessageType getMessageType(int templateId);

    /**
     * Checks if this schema supports a given template ID.
     *
     * @param templateId the template ID
     * @return true if supported
     */
    default boolean supportsTemplateId(int templateId) {
        return getMessageType(templateId) != null;
    }
}
