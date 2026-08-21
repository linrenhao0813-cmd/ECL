package com.ecl.util;

import java.net.http.HttpResponse;

/** Validator metadata persisted next to a partial download. */
record PartialDownloadMetadata(String source, String etag, String lastModified) {
    String validator() {
        return etag == null || etag.isBlank()
                ? lastModified == null ? "" : lastModified
                : etag;
    }

    boolean matches(HttpResponse<?> response) {
        if (etag != null && !etag.isBlank()) {
            return etag.equals(response.headers().firstValue("ETag").orElse(""));
        }
        return lastModified != null && !lastModified.isBlank()
                && lastModified.equals(response.headers()
                .firstValue("Last-Modified").orElse(""));
    }
}
