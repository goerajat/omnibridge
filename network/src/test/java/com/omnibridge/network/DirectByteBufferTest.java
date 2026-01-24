package com.omnibridge.network;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DirectByteBuffer.
 */
class DirectByteBufferTest {

    private static final int BUFFER_SIZE = 1024;
    private DirectByteBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new DirectByteBuffer(BUFFER_SIZE);
    }

    // ==================== Basic Properties ====================

    @Test
    void capacity_returnsCorrectValue() {
        assertEquals(BUFFER_SIZE, buffer.capacity());
    }

    @Test
    void initialPosition_isZero() {
        assertEquals(0, buffer.position());
    }

    @Test
    void initialLimit_equalsCapacity() {
        assertEquals(BUFFER_SIZE, buffer.limit());
    }

    @Test
    void byteBuffer_returnsSameMemory() {
        ByteBuffer bb = buffer.byteBuffer();
        assertNotNull(bb);
        assertEquals(BUFFER_SIZE, bb.capacity());
        assertTrue(bb.isDirect());
    }

    @Test
    void unsafeBuffer_returnsSameMemory() {
        UnsafeBuffer ub = buffer.unsafeBuffer();
        assertNotNull(ub);
        assertEquals(BUFFER_SIZE, ub.capacity());
    }

    // ==================== Position/Limit Management ====================

    @Test
    void position_canBeSet() {
        buffer.position(100);
        assertEquals(100, buffer.position());
        assertEquals(100, buffer.byteBuffer().position());
    }

    @Test
    void limit_canBeSet() {
        buffer.limit(500);
        assertEquals(500, buffer.limit());
        assertEquals(500, buffer.byteBuffer().limit());
    }

    @Test
    void remaining_calculatedCorrectly() {
        buffer.position(100);
        buffer.limit(500);
        assertEquals(400, buffer.remaining());
    }

    @Test
    void hasRemaining_returnsTrueWhenDataAvailable() {
        buffer.position(0);
        buffer.limit(100);
        assertTrue(buffer.hasRemaining());
    }

    @Test
    void hasRemaining_returnsFalseWhenEmpty() {
        buffer.position(100);
        buffer.limit(100);
        assertFalse(buffer.hasRemaining());
    }

    @Test
    void flip_setsLimitToPositionAndPositionToZero() {
        buffer.position(50);
        buffer.flip();
        assertEquals(0, buffer.position());
        assertEquals(50, buffer.limit());
    }

    @Test
    void clear_resetsPositionAndLimit() {
        buffer.position(50);
        buffer.limit(100);
        buffer.clear();
        assertEquals(0, buffer.position());
        assertEquals(BUFFER_SIZE, buffer.limit());
    }

    @Test
    void compact_movesRemainingDataToStart() {
        // Write some data
        buffer.put((byte) 1);
        buffer.put((byte) 2);
        buffer.put((byte) 3);
        buffer.put((byte) 4);
        buffer.flip();

        // Consume first two bytes
        buffer.get();
        buffer.get();

        // Compact - should move bytes 3, 4 to start
        buffer.compact();

        // Position should be 2 (remaining bytes), limit should be capacity
        assertEquals(2, buffer.position());
        assertEquals(BUFFER_SIZE, buffer.limit());

        // Verify data was moved
        assertEquals(3, buffer.getByte(0));
        assertEquals(4, buffer.getByte(1));
    }

    @Test
    void rewind_resetsPositionOnly() {
        buffer.position(50);
        buffer.limit(100);
        buffer.rewind();
        assertEquals(0, buffer.position());
        assertEquals(100, buffer.limit());
    }

    // ==================== Relative Operations (position-based) ====================

    @Test
    void put_byte_advancesPosition() {
        buffer.put((byte) 42);
        assertEquals(1, buffer.position());
        assertEquals(42, buffer.getByte(0));
    }

    @Test
    void get_byte_advancesPosition() {
        buffer.putByte(0, (byte) 42);
        buffer.position(0);
        byte value = buffer.get();
        assertEquals(42, value);
        assertEquals(1, buffer.position());
    }

    @Test
    void putInt_relative_advancesPosition() {
        buffer.putInt(12345678);
        assertEquals(4, buffer.position());
    }

    @Test
    void getInt_relative_advancesPosition() {
        // Use relative put to match relative get (both use ByteBuffer byte order)
        buffer.putInt(12345678);
        buffer.flip();
        int value = buffer.getInt();
        assertEquals(12345678, value);
        assertEquals(4, buffer.position());
    }

    @Test
    void putLong_relative_advancesPosition() {
        buffer.putLong(123456789012345L);
        assertEquals(8, buffer.position());
    }

    @Test
    void getLong_relative_advancesPosition() {
        // Use relative put to match relative get (both use ByteBuffer byte order)
        buffer.putLong(123456789012345L);
        buffer.flip();
        long value = buffer.getLong();
        assertEquals(123456789012345L, value);
        assertEquals(8, buffer.position());
    }

    @Test
    void put_byteArray_advancesPosition() {
        byte[] data = {1, 2, 3, 4, 5};
        buffer.put(data);
        assertEquals(5, buffer.position());

        // Verify data
        for (int i = 0; i < data.length; i++) {
            assertEquals(data[i], buffer.getByte(i));
        }
    }

    @Test
    void get_byteArray_advancesPosition() {
        byte[] src = {1, 2, 3, 4, 5};
        buffer.putBytes(0, src);
        buffer.position(0);

        byte[] dst = new byte[5];
        buffer.get(dst);

        assertArrayEquals(src, dst);
        assertEquals(5, buffer.position());
    }

    // ==================== Absolute Operations (index-based, no position change) ====================

    @Test
    void putByte_absolute_doesNotChangePosition() {
        buffer.position(10);
        buffer.putByte(50, (byte) 42);
        assertEquals(10, buffer.position());
        assertEquals(42, buffer.getByte(50));
    }

    @Test
    void getByte_absolute_doesNotChangePosition() {
        buffer.putByte(50, (byte) 42);
        buffer.position(10);
        byte value = buffer.getByte(50);
        assertEquals(42, value);
        assertEquals(10, buffer.position());
    }

    @Test
    void putInt_absolute_doesNotChangePosition() {
        buffer.position(10);
        buffer.putInt(50, 12345678);
        assertEquals(10, buffer.position());
        assertEquals(12345678, buffer.getInt(50));
    }

    @Test
    void putLong_absolute_doesNotChangePosition() {
        buffer.position(10);
        buffer.putLong(50, 123456789012345L);
        assertEquals(10, buffer.position());
        assertEquals(123456789012345L, buffer.getLong(50));
    }

    @Test
    void putBytes_absolute_doesNotChangePosition() {
        byte[] data = {1, 2, 3, 4, 5};
        buffer.position(10);
        buffer.putBytes(50, data);
        assertEquals(10, buffer.position());

        byte[] read = new byte[5];
        buffer.getBytes(50, read);
        assertArrayEquals(data, read);
    }

    // ==================== Memory Sharing ====================

    @Test
    void byteBuffer_and_unsafeBuffer_shareSameMemory() {
        // Write via UnsafeBuffer (uses native byte order)
        buffer.putInt(0, 12345678);

        // Read via UnsafeBuffer (same byte order)
        assertEquals(12345678, buffer.getInt(0));

        // Write via ByteBuffer relative methods
        ByteBuffer bb = buffer.byteBuffer();
        bb.position(8);
        bb.putLong(987654321L);

        // Read via ByteBuffer relative methods (same byte order)
        bb.position(8);
        assertEquals(987654321L, bb.getLong());

        // Test that bytes are actually shared
        buffer.putByte(100, (byte) 42);
        assertEquals(42, bb.get(100));
    }

    @Test
    void positionChanges_reflectedInByteBuffer() {
        buffer.position(100);
        assertEquals(100, buffer.byteBuffer().position());

        buffer.byteBuffer().position(200);
        assertEquals(200, buffer.position());
    }

    @Test
    void limitChanges_reflectedInByteBuffer() {
        buffer.limit(500);
        assertEquals(500, buffer.byteBuffer().limit());

        buffer.byteBuffer().limit(300);
        assertEquals(300, buffer.limit());
    }

    // ==================== DirectBuffer Interface ====================

    @Test
    void implementsDirectBuffer() {
        assertTrue(buffer instanceof DirectBuffer);
    }

    @Test
    void implementsMutableDirectBuffer() {
        assertTrue(buffer instanceof MutableDirectBuffer);
    }

    @Test
    void putBytes_fromDirectBuffer() {
        UnsafeBuffer src = new UnsafeBuffer(new byte[]{1, 2, 3, 4, 5});
        buffer.putBytes(10, src, 0, 5);

        byte[] dst = new byte[5];
        buffer.getBytes(10, dst);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, dst);
    }

    @Test
    void getBytes_toMutableDirectBuffer() {
        buffer.putBytes(0, new byte[]{1, 2, 3, 4, 5});

        UnsafeBuffer dst = new UnsafeBuffer(new byte[5]);
        buffer.getBytes(0, dst, 0, 5);

        byte[] result = new byte[5];
        dst.getBytes(0, result);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, result);
    }

    // ==================== String Operations ====================

    @Test
    void putStringWithoutLengthAscii_writesString() {
        String text = "Hello";
        int bytesWritten = buffer.putStringWithoutLengthAscii(0, text);
        assertEquals(5, bytesWritten);

        String read = buffer.getStringWithoutLengthAscii(0, 5);
        assertEquals("Hello", read);
    }

    @Test
    void putNaturalIntAscii_writesIntAsString() {
        int value = 12345;
        int bytesWritten = buffer.putNaturalIntAscii(0, value);
        assertEquals(5, bytesWritten);

        String read = buffer.getStringWithoutLengthAscii(0, 5);
        assertEquals("12345", read);
    }

    @Test
    void parseNaturalIntAscii_readsIntFromString() {
        buffer.putStringWithoutLengthAscii(0, "98765");
        int value = buffer.parseNaturalIntAscii(0, 5);
        assertEquals(98765, value);
    }

    // ==================== Wrap Existing ByteBuffer ====================

    @Test
    void wrapExistingByteBuffer() {
        ByteBuffer existing = ByteBuffer.allocateDirect(512);
        // Use relative put (uses ByteBuffer's byte order)
        existing.putInt(42);

        DirectByteBuffer wrapped = new DirectByteBuffer(existing);

        assertEquals(512, wrapped.capacity());

        // Read using ByteBuffer (same byte order)
        assertEquals(42, wrapped.byteBuffer().getInt(0));

        // Test byte-level access (no byte order issues)
        wrapped.putByte(100, (byte) 77);
        assertEquals(77, existing.get(100));
    }

    // ==================== ByteOrder ====================

    @Test
    void putInt_withByteOrder() {
        buffer.putInt(0, 0x12345678, ByteOrder.BIG_ENDIAN);
        buffer.putInt(4, 0x12345678, ByteOrder.LITTLE_ENDIAN);

        // Big endian: bytes are 12 34 56 78
        assertEquals(0x12, buffer.getByte(0) & 0xFF);
        assertEquals(0x34, buffer.getByte(1) & 0xFF);

        // Little endian: bytes are 78 56 34 12
        assertEquals(0x78, buffer.getByte(4) & 0xFF);
        assertEquals(0x56, buffer.getByte(5) & 0xFF);
    }

    @Test
    void getInt_withByteOrder() {
        buffer.putByte(0, (byte) 0x12);
        buffer.putByte(1, (byte) 0x34);
        buffer.putByte(2, (byte) 0x56);
        buffer.putByte(3, (byte) 0x78);

        assertEquals(0x12345678, buffer.getInt(0, ByteOrder.BIG_ENDIAN));
        assertEquals(0x78563412, buffer.getInt(0, ByteOrder.LITTLE_ENDIAN));
    }

    // ==================== Float/Double ====================

    @Test
    void putFloat_andGetFloat() {
        float value = 3.14159f;
        buffer.putFloat(0, value);
        assertEquals(value, buffer.getFloat(0), 0.00001f);
    }

    @Test
    void putDouble_andGetDouble() {
        double value = 3.141592653589793;
        buffer.putDouble(0, value);
        assertEquals(value, buffer.getDouble(0), 0.0000000000001);
    }

    // ==================== Short/Char ====================

    @Test
    void putShort_andGetShort() {
        short value = 12345;
        buffer.putShort(0, value);
        assertEquals(value, buffer.getShort(0));
    }

    @Test
    void putChar_andGetChar() {
        char value = 'A';
        buffer.putChar(0, value);
        assertEquals(value, buffer.getChar(0));
    }

    // ==================== Mark/Reset ====================

    @Test
    void mark_andReset() {
        buffer.position(10);
        buffer.mark();
        buffer.position(50);
        buffer.resetToMark();
        assertEquals(10, buffer.position());
    }

    // ==================== toString ====================

    @Test
    void toString_includesStateInfo() {
        buffer.position(10);
        buffer.limit(500);
        String str = buffer.toString();
        assertTrue(str.contains("capacity=" + BUFFER_SIZE));
        assertTrue(str.contains("position=10"));
        assertTrue(str.contains("limit=500"));
    }
}
