package com.omnibridge.fix.message;

/**
 * Exception thrown when a duplicate FIX tag is detected during message encoding.
 *
 * <p>FIX protocol requires that most tags appear only once in a message (with some
 * exceptions for repeating groups). This exception is thrown when attempting to
 * add a tag that has already been added to a {@link PooledFixMessage}.</p>
 */
public class DuplicateTagException extends RuntimeException {

    private final int tag;

    /**
     * Create a new DuplicateTagException for the specified tag.
     *
     * @param tag the duplicate tag number
     */
    public DuplicateTagException(int tag) {
        super("Duplicate tag: " + tag);
        this.tag = tag;
    }

    /**
     * Create a new DuplicateTagException with a custom message.
     *
     * @param tag the duplicate tag number
     * @param message additional context about the duplicate
     */
    public DuplicateTagException(int tag, String message) {
        super("Duplicate tag " + tag + ": " + message);
        this.tag = tag;
    }

    /**
     * Get the tag number that was duplicated.
     *
     * @return the duplicate tag number
     */
    public int getTag() {
        return tag;
    }
}
