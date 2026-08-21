package com.ecl.util;

import java.io.IOException;

/** Shared notification helpers for mirror-aware requests and downloads. */
final class DownloadSourceCallbacks {
    private DownloadSourceCallbacks() {
    }

    static void notifySource(DownloadSourceCallback callback, String originalUrl,
                             String candidateUrl, boolean mirror) {
        if (callback != null) {
            callback.onSource(originalUrl, candidateUrl, mirror,
                    DownloadSourceUtil.sourceName(candidateUrl));
        }
    }

    static void notifyFailure(DownloadSourceCallback callback, String candidateUrl,
                              IOException error) {
        if (callback != null) {
            callback.onFailure(candidateUrl, error);
        }
    }
}
