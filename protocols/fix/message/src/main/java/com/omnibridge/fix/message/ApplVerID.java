package com.omnibridge.fix.message;

/**
 * FIX Application Version ID enumeration.
 *
 * <p>ApplVerID (tag 1128) specifies the application layer message version in FIX 5.0+.
 * In FIX 5.0, the transport layer (FIXT.1.1) is separated from the application layer,
 * and ApplVerID identifies which application message version is being used.</p>
 *
 * <p>This is sent in:</p>
 * <ul>
 *   <li>Logon message as DefaultApplVerID (tag 1137) - the default version for the session</li>
 *   <li>Application messages as ApplVerID (tag 1128) - when different from the default</li>
 * </ul>
 */
public enum ApplVerID {

    /**
     * FIX 4.0 - ApplVerID value "2"
     */
    FIX40("2", "FIX.4.0"),

    /**
     * FIX 4.1 - ApplVerID value "3"
     */
    FIX41("3", "FIX.4.1"),

    /**
     * FIX 4.2 - ApplVerID value "4"
     */
    FIX42("4", "FIX.4.2"),

    /**
     * FIX 4.3 - ApplVerID value "5"
     */
    FIX43("5", "FIX.4.3"),

    /**
     * FIX 4.4 - ApplVerID value "6"
     */
    FIX44("6", "FIX.4.4"),

    /**
     * FIX 5.0 - ApplVerID value "9"
     */
    FIX50("9", "FIX.5.0"),

    /**
     * FIX 5.0 SP1 - ApplVerID value "10"
     */
    FIX50SP1("10", "FIX.5.0SP1"),

    /**
     * FIX 5.0 SP2 - ApplVerID value "11"
     */
    FIX50SP2("11", "FIX.5.0SP2");

    private final String wireValue;
    private final String displayName;

    ApplVerID(String wireValue, String displayName) {
        this.wireValue = wireValue;
        this.displayName = displayName;
    }

    /**
     * Get the wire format value for this ApplVerID.
     * This is the value sent in tag 1128 or 1137.
     *
     * @return the wire format value (e.g., "9" for FIX 5.0)
     */
    public String getValue() {
        return wireValue;
    }

    /**
     * Get the wire format value (alias for getValue()).
     *
     * @return the wire format value
     */
    public String getWireValue() {
        return wireValue;
    }

    /**
     * Get the display name for this version.
     *
     * @return the display name (e.g., "FIX.5.0")
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Parse ApplVerID from wire format value.
     *
     * @param value the wire format value (e.g., "9", "10", "11")
     * @return the ApplVerID enum value
     * @throws IllegalArgumentException if value is not recognized
     */
    public static ApplVerID fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("ApplVerID value cannot be null");
        }

        for (ApplVerID applVerID : values()) {
            if (applVerID.wireValue.equals(value)) {
                return applVerID;
            }
        }

        throw new IllegalArgumentException("Unknown ApplVerID value: " + value +
                ". Valid values: 2 (FIX 4.0), 3 (FIX 4.1), 4 (FIX 4.2), 5 (FIX 4.3), " +
                "6 (FIX 4.4), 9 (FIX 5.0), 10 (FIX 5.0 SP1), 11 (FIX 5.0 SP2)");
    }

    /**
     * Parse ApplVerID from wire format value as CharSequence (zero-allocation).
     *
     * @param value the wire format value
     * @return the ApplVerID enum value
     * @throws IllegalArgumentException if value is not recognized
     */
    public static ApplVerID fromValue(CharSequence value) {
        if (value == null) {
            throw new IllegalArgumentException("ApplVerID value cannot be null");
        }
        return fromValue(value.toString());
    }

    /**
     * Parse ApplVerID from display name (e.g., "FIX.5.0").
     *
     * @param displayName the display name
     * @return the ApplVerID enum value
     * @throws IllegalArgumentException if display name is not recognized
     */
    public static ApplVerID fromDisplayName(String displayName) {
        if (displayName == null) {
            throw new IllegalArgumentException("ApplVerID display name cannot be null");
        }

        String normalized = displayName.trim().toUpperCase();
        for (ApplVerID applVerID : values()) {
            if (applVerID.displayName.equalsIgnoreCase(normalized) ||
                applVerID.name().equalsIgnoreCase(normalized)) {
                return applVerID;
            }
        }

        throw new IllegalArgumentException("Unknown ApplVerID display name: " + displayName);
    }

    @Override
    public String toString() {
        return displayName + " (ApplVerID=" + wireValue + ")";
    }
}
