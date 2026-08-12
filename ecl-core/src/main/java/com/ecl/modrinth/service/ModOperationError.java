package com.ecl.modrinth.service;

/**
 * Stable, user-facing error information kept separate from technical logging.
 */
public record ModOperationError(
        String code,
        String userMessage,
        String technicalMessage,
        Throwable cause,
        boolean retryable
) {
    public ModOperationError {
        code = code == null || code.isBlank() ? "UNKNOWN" : code;
        userMessage = userMessage == null || userMessage.isBlank() ? "模组操作失败" : userMessage;
        technicalMessage = technicalMessage == null ? "" : technicalMessage;
    }
}
