package com.fixengine.ouch.message;

/**
 * OUCH protocol version enumeration.
 *
 * <p>Supports both OUCH 4.2 and OUCH 5.0 protocols with their key differences:</p>
 * <ul>
 *   <li><b>OUCH 4.2:</b> Fixed-length messages, 14-character Order Token identifier</li>
 *   <li><b>OUCH 5.0:</b> Variable-length messages with appendages, 4-byte UserRefNum identifier</li>
 * </ul>
 */
public enum OuchVersion {

    /**
     * OUCH 4.2 - Fixed-length messages with 14-character Order Token.
     */
    V42("4.2", false),

    /**
     * OUCH 5.0 - Variable-length messages with appendages and 4-byte UserRefNum.
     */
    V50("5.0", true);

    private final String versionString;
    private final boolean supportsAppendages;

    OuchVersion(String versionString, boolean supportsAppendages) {
        this.versionString = versionString;
        this.supportsAppendages = supportsAppendages;
    }

    /**
     * Get the version string (e.g., "4.2" or "5.0").
     */
    public String getVersionString() {
        return versionString;
    }

    /**
     * Check if this version supports message appendages.
     * Only OUCH 5.0 supports appendages.
     */
    public boolean supportsAppendages() {
        return supportsAppendages;
    }

    /**
     * Check if this version uses UserRefNum (4-byte unsigned int) for order identification.
     * OUCH 5.0 uses UserRefNum, OUCH 4.2 uses Order Token.
     */
    public boolean usesUserRefNum() {
        return this == V50;
    }

    /**
     * Check if this version uses Order Token (14-char alpha) for order identification.
     * OUCH 4.2 uses Order Token, OUCH 5.0 uses UserRefNum.
     */
    public boolean usesOrderToken() {
        return this == V42;
    }

    /**
     * Get the order identifier field length.
     * V42: 14 bytes (Order Token), V50: 4 bytes (UserRefNum)
     */
    public int getOrderIdFieldLength() {
        return this == V42 ? 14 : 4;
    }

    /**
     * Parse version from string.
     *
     * @param version the version string ("4.2", "5.0", "V42", "V50")
     * @return the OuchVersion
     * @throws IllegalArgumentException if version is not recognized
     */
    public static OuchVersion fromString(String version) {
        if (version == null) {
            throw new IllegalArgumentException("Version cannot be null");
        }

        String normalized = version.trim().toUpperCase();
        switch (normalized) {
            case "4.2":
            case "V42":
            case "OUCH42":
            case "OUCH4.2":
                return V42;
            case "5.0":
            case "V50":
            case "OUCH50":
            case "OUCH5.0":
                return V50;
            default:
                throw new IllegalArgumentException("Unknown OUCH version: " + version +
                    ". Supported versions: 4.2, 5.0, V42, V50");
        }
    }

    /**
     * Get the default OUCH version (4.2 for backward compatibility).
     */
    public static OuchVersion getDefault() {
        return V42;
    }

    @Override
    public String toString() {
        return "OUCH " + versionString;
    }
}
