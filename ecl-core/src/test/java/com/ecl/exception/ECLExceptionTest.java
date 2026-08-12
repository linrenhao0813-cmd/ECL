package com.ecl.exception;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ECLExceptionTest {
    @Test
    void exposesStableSupportCodeAndImmutableContext() {
        ECLException exception = new ECLException(ErrorCode.DOWNLOAD_CHECKSUM, "bad hash", null,
                Map.of("file", "client.jar"));

        assertEquals("ECL-DL-3002", exception.supportCode());
        assertEquals("client.jar", exception.context().get("file"));
        assertThrows(UnsupportedOperationException.class,
                () -> exception.context().put("token", "secret"));
    }
}
