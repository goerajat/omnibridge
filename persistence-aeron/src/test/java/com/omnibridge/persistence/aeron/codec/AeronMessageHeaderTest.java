package com.omnibridge.persistence.aeron.codec;

import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AeronMessageHeaderTest {

    private final MutableDirectBuffer buffer = new ExpandableDirectByteBuffer(64);

    @Test
    void shouldWriteAndReadHeader() {
        AeronMessageHeader.write(buffer, 0, 21, 1, 100, 1);

        assertEquals(21, AeronMessageHeader.readBlockLength(buffer, 0));
        assertEquals(1, AeronMessageHeader.readTemplateId(buffer, 0));
        assertEquals(100, AeronMessageHeader.readSchemaId(buffer, 0));
        assertEquals(1, AeronMessageHeader.readVersion(buffer, 0));
    }

    @Test
    void shouldWriteAndReadAtOffset() {
        int offset = 16;
        AeronMessageHeader.write(buffer, offset, 41, 2, 100, 1);

        assertEquals(41, AeronMessageHeader.readBlockLength(buffer, offset));
        assertEquals(2, AeronMessageHeader.readTemplateId(buffer, offset));
        assertEquals(100, AeronMessageHeader.readSchemaId(buffer, offset));
        assertEquals(1, AeronMessageHeader.readVersion(buffer, offset));
    }

    @Test
    void shouldHandleMaxValues() {
        AeronMessageHeader.write(buffer, 0, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF);

        assertEquals(0xFFFF, AeronMessageHeader.readBlockLength(buffer, 0));
        assertEquals(0xFFFF, AeronMessageHeader.readTemplateId(buffer, 0));
        assertEquals(0xFFFF, AeronMessageHeader.readSchemaId(buffer, 0));
        assertEquals(0xFFFF, AeronMessageHeader.readVersion(buffer, 0));
    }

    @Test
    void shouldHandleZeroValues() {
        AeronMessageHeader.write(buffer, 0, 0, 0, 0, 0);

        assertEquals(0, AeronMessageHeader.readBlockLength(buffer, 0));
        assertEquals(0, AeronMessageHeader.readTemplateId(buffer, 0));
        assertEquals(0, AeronMessageHeader.readSchemaId(buffer, 0));
        assertEquals(0, AeronMessageHeader.readVersion(buffer, 0));
    }
}
