package com.fixengine.ouch.message.v50;

import com.fixengine.ouch.message.OuchMessage;
import com.fixengine.ouch.message.OuchVersion;
import com.fixengine.ouch.message.v50.appendage.Appendage;
import com.fixengine.ouch.message.v50.appendage.AppendageReader;
import com.fixengine.ouch.message.v50.appendage.AppendageType;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for OUCH 5.0 messages with appendage support.
 *
 * <p>OUCH 5.0 introduces variable-length messages with optional appendages.
 * Key differences from OUCH 4.2:</p>
 * <ul>
 *   <li>Order identification uses 4-byte UserRefNum instead of 14-char Order Token</li>
 *   <li>Messages can include optional appendages (peg, reserve, discretion, etc.)</li>
 *   <li>Message length is variable based on appendages included</li>
 * </ul>
 */
public abstract class V50OuchMessage extends OuchMessage {

    protected List<Appendage> appendages;
    protected int appendageCount;
    protected int appendagesLength;
    protected final AppendageReader appendageReader = new AppendageReader();

    @Override
    public OuchVersion getVersion() {
        return OuchVersion.V50;
    }

    /**
     * Check if this version supports appendages.
     */
    public boolean supportsAppendages() {
        return true;
    }

    /**
     * Get the base message length (without appendages).
     */
    public abstract int getBaseMessageLength();

    /**
     * Get the offset where appendage count is stored.
     * Returns -1 if this message type doesn't support appendages.
     */
    public abstract int getAppendageCountOffset();

    @Override
    public int getMessageLength() {
        return getBaseMessageLength() + appendagesLength;
    }

    /**
     * Get the UserRefNum (V50 order identifier).
     */
    public abstract long getUserRefNum();

    /**
     * Set the UserRefNum.
     */
    public abstract V50OuchMessage setUserRefNum(long userRefNum);

    // =====================================================
    // Appendage management
    // =====================================================

    /**
     * Get the number of appendages.
     */
    public int getAppendageCount() {
        if (getAppendageCountOffset() < 0) {
            return 0;
        }
        return getUnsignedByte(getAppendageCountOffset());
    }

    /**
     * Get all appendages.
     */
    public List<Appendage> getAppendages() {
        if (appendages == null) {
            parseAppendages();
        }
        return appendages;
    }

    /**
     * Get a specific appendage by type.
     *
     * @param type the appendage type
     * @return the appendage, or null if not present
     */
    @SuppressWarnings("unchecked")
    public <T extends Appendage> T getAppendage(AppendageType type) {
        for (Appendage appendage : getAppendages()) {
            if (appendage.getType() == type) {
                return (T) appendage;
            }
        }
        return null;
    }

    /**
     * Check if a specific appendage type is present.
     */
    public boolean hasAppendage(AppendageType type) {
        return getAppendage(type) != null;
    }

    /**
     * Parse appendages from the buffer.
     */
    protected void parseAppendages() {
        appendages = new ArrayList<>();
        int count = getAppendageCount();
        if (count > 0 && getAppendageCountOffset() >= 0) {
            int appendageStart = offset + getAppendageCountOffset() + 1;
            appendages = appendageReader.readAll(buffer, appendageStart, count);
            appendagesLength = appendageReader.getTotalLength(buffer, appendageStart, count);
        } else {
            appendagesLength = 0;
        }
    }

    /**
     * Add an appendage to this message.
     * Note: Must be called during message construction before complete().
     */
    public V50OuchMessage addAppendage(Appendage appendage) {
        if (appendages == null) {
            appendages = new ArrayList<>();
        }
        appendages.add(appendage);
        appendageCount++;
        appendagesLength += appendage.getTotalLength();
        return this;
    }

    /**
     * Clear all appendages.
     */
    public V50OuchMessage clearAppendages() {
        if (appendages != null) {
            appendages.clear();
        }
        appendageCount = 0;
        appendagesLength = 0;
        return this;
    }

    @Override
    public OuchMessage wrapForReading(DirectBuffer buffer, int offset, int length) {
        super.wrapForReading(buffer, offset, length);
        appendages = null; // Reset cached appendages - will parse on demand
        appendageCount = 0;
        appendagesLength = 0;
        return this;
    }

    @Override
    public OuchMessage wrapForWriting(MutableDirectBuffer buffer, int offset, int maxLength, int claimIndex) {
        super.wrapForWriting(buffer, offset, maxLength, claimIndex);
        if (appendages != null) {
            appendages.clear();
        }
        appendageCount = 0;
        appendagesLength = 0;
        return this;
    }

    @Override
    public int complete() {
        // Write appendage count
        if (getAppendageCountOffset() >= 0) {
            putByte(getAppendageCountOffset(), (byte) appendageCount);
        }
        return getBaseMessageLength() + appendagesLength;
    }

    @Override
    public void reset() {
        super.reset();
        if (appendages != null) {
            appendages.clear();
        }
        appendageCount = 0;
        appendagesLength = 0;
    }
}
