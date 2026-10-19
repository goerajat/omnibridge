package com.omnibridge.fix.message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApplVerID (1128) and DefaultApplVerID (1137) codes from the FIX specification;
 * a FIXT counterparty rejects or misreads any other value.
 */
class ApplVerIDTest {

    @Test
    void wireValuesMatchTheFixSpecification() {
        assertEquals("2", ApplVerID.FIX40.getValue());
        assertEquals("3", ApplVerID.FIX41.getValue());
        assertEquals("4", ApplVerID.FIX42.getValue());
        assertEquals("5", ApplVerID.FIX43.getValue());
        assertEquals("6", ApplVerID.FIX44.getValue());
        assertEquals("7", ApplVerID.FIX50.getValue());
        assertEquals("8", ApplVerID.FIX50SP1.getValue());
        assertEquals("9", ApplVerID.FIX50SP2.getValue());
    }

    @Test
    void wireValuesParseBack() {
        for (ApplVerID v : ApplVerID.values()) {
            assertEquals(v, ApplVerID.fromValue(v.getValue()));
        }
    }
}
