package com.ecl.launch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoundedLogBufferTest {
    @Test
    void stringAppendWrapsAcrossEndOfBackingArray() {
        BoundedLogBuffer buffer = new BoundedLogBuffer(5);
        buffer.append("12345");
        buffer.append("abc");

        buffer.append("XYZ");

        assertEquals("bcXYZ", buffer.toString());
        assertEquals(5, buffer.size());
    }
}
