package com.omnibridge.fix.message;

/**
 * FIX protocol version enumeration.
 *
 * <p>Supports FIX 4.x versions and FIX 5.0+ versions with their key differences:</p>
 * <ul>
 *   <li><b>FIX 4.x:</b> BeginString contains the version (e.g., "FIX.4.4"), no ApplVerID</li>
 *   <li><b>FIX 5.0+:</b> BeginString is always "FIXT.1.1", ApplVerID specifies app version</li>
 * </ul>
 *
 * <p>In FIX 5.0+, the transport layer (FIXT.1.1) is separated from the application layer.
 * The Logon message must include DefaultApplVerID (tag 1137) to establish the default
 * application message version for the session.</p>
 */
public enum FixVersion {

    /**
     * FIX 4.2 - Legacy version with no transport/application split.
     */
    FIX42("FIX.4.2", "FIX.4.2", false, null),

    /**
     * FIX 4.4 - Common production version with no transport/application split.
     */
    FIX44("FIX.4.4", "FIX.4.4", false, null),

    /**
     * FIX 5.0 - Uses FIXT.1.1 transport with FIX 5.0 application messages.
     */
    FIX50("FIX.5.0", "FIXT.1.1", true, ApplVerID.FIX50),

    /**
     * FIX 5.0 SP1 - Uses FIXT.1.1 transport with FIX 5.0 SP1 application messages.
     */
    FIX50SP1("FIX.5.0SP1", "FIXT.1.1", true, ApplVerID.FIX50SP1),

    /**
     * FIX 5.0 SP2 - Uses FIXT.1.1 transport with FIX 5.0 SP2 application messages.
     */
    FIX50SP2("FIX.5.0SP2", "FIXT.1.1", true, ApplVerID.FIX50SP2);

    private final String versionString;
    private final String beginString;
    private final boolean usesFixt;
    private final ApplVerID defaultApplVerID;

    FixVersion(String versionString, String beginString, boolean usesFixt, ApplVerID defaultApplVerID) {
        this.versionString = versionString;
        this.beginString = beginString;
        this.usesFixt = usesFixt;
        this.defaultApplVerID = defaultApplVerID;
    }

    /**
     * Get the version string (e.g., "FIX.4.4" or "FIX.5.0").
     * This is the logical version name, not necessarily what goes on the wire.
     *
     * @return the version string
     */
    public String getVersionString() {
        return versionString;
    }

    /**
     * Get the BeginString value (tag 8) to use on the wire.
     * For FIX 4.x, this is the version (e.g., "FIX.4.4").
     * For FIX 5.0+, this is always "FIXT.1.1".
     *
     * @return the BeginString wire value
     */
    public String getBeginString() {
        return beginString;
    }

    /**
     * Check if this version uses the FIXT.1.1 transport layer.
     * Only FIX 5.0+ versions use FIXT.
     *
     * @return true if this version uses FIXT.1.1
     */
    public boolean usesFixt() {
        return usesFixt;
    }

    /**
     * Get the default ApplVerID for this version.
     * For FIX 4.x, this returns null (no ApplVerID needed).
     * For FIX 5.0+, this returns the appropriate ApplVerID.
     *
     * @return the default ApplVerID, or null for FIX 4.x
     */
    public ApplVerID getDefaultApplVerID() {
        return defaultApplVerID;
    }

    /**
     * Check if this version requires ApplVerID in messages.
     * Only FIX 5.0+ requires ApplVerID.
     *
     * @return true if ApplVerID is required
     */
    public boolean requiresApplVerID() {
        return usesFixt;
    }

    /**
     * Check if this is a FIX 5.0+ version.
     *
     * @return true if FIX 5.0 or later
     */
    public boolean isFix50OrLater() {
        return usesFixt;
    }

    /**
     * Parse FixVersion from string.
     *
     * <p>Accepts various formats:</p>
     * <ul>
     *   <li>"FIX.4.2", "FIX42", "4.2" -&gt; FIX42</li>
     *   <li>"FIX.4.4", "FIX44", "4.4" -&gt; FIX44</li>
     *   <li>"FIX.5.0", "FIX50", "5.0" -&gt; FIX50</li>
     *   <li>"FIX.5.0SP1", "FIX50SP1", "5.0SP1" -&gt; FIX50SP1</li>
     *   <li>"FIX.5.0SP2", "FIX50SP2", "5.0SP2" -&gt; FIX50SP2</li>
     * </ul>
     *
     * @param version the version string
     * @return the FixVersion enum value
     * @throws IllegalArgumentException if version is not recognized
     */
    public static FixVersion fromString(String version) {
        if (version == null) {
            throw new IllegalArgumentException("Version cannot be null");
        }

        String normalized = version.trim().toUpperCase().replace(".", "").replace("-", "");

        // Try exact match first
        for (FixVersion fv : values()) {
            String fvNormalized = fv.versionString.toUpperCase().replace(".", "");
            if (fvNormalized.equals(normalized) || fv.name().equals(normalized)) {
                return fv;
            }
        }

        // Try partial matches
        if (normalized.contains("42") || normalized.equals("42")) {
            return FIX42;
        }
        if (normalized.contains("44") || normalized.equals("44")) {
            return FIX44;
        }
        if (normalized.contains("50SP2") || normalized.equals("50SP2")) {
            return FIX50SP2;
        }
        if (normalized.contains("50SP1") || normalized.equals("50SP1")) {
            return FIX50SP1;
        }
        if (normalized.contains("50") || normalized.equals("50")) {
            return FIX50;
        }

        throw new IllegalArgumentException("Unknown FIX version: " + version +
                ". Supported versions: FIX.4.2, FIX.4.4, FIX.5.0, FIX.5.0SP1, FIX.5.0SP2");
    }

    /**
     * Get the default FIX version (FIX 4.4 for backward compatibility).
     *
     * @return FIX44
     */
    public static FixVersion getDefault() {
        return FIX44;
    }

    /**
     * Determine the FixVersion from a BeginString and optional ApplVerID.
     *
     * @param beginString the BeginString from tag 8
     * @param applVerID the ApplVerID from tag 1128 (may be null)
     * @return the corresponding FixVersion
     * @throws IllegalArgumentException if the combination is not recognized
     */
    public static FixVersion fromBeginStringAndApplVerID(String beginString, ApplVerID applVerID) {
        if (beginString == null) {
            throw new IllegalArgumentException("BeginString cannot be null");
        }

        // FIX 4.x versions - BeginString contains version
        if ("FIX.4.2".equals(beginString)) {
            return FIX42;
        }
        if ("FIX.4.4".equals(beginString)) {
            return FIX44;
        }

        // FIX 5.0+ - FIXT.1.1 transport, use ApplVerID
        if ("FIXT.1.1".equals(beginString)) {
            if (applVerID == null) {
                // Default to FIX50 if no ApplVerID specified
                return FIX50;
            }
            return switch (applVerID) {
                case FIX50 -> FIX50;
                case FIX50SP1 -> FIX50SP1;
                case FIX50SP2 -> FIX50SP2;
                default -> throw new IllegalArgumentException(
                        "Invalid ApplVerID for FIXT.1.1: " + applVerID);
            };
        }

        throw new IllegalArgumentException("Unknown BeginString: " + beginString);
    }

    @Override
    public String toString() {
        return versionString;
    }
}
