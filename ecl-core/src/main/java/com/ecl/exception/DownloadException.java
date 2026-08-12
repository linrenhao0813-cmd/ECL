package com.ecl.exception;

/**
 * Thrown on download failures (network, checksum, missing sources).
 */
public class DownloadException extends ECLException {
    public DownloadException(String message) {
        super(message);
    }

    public DownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
