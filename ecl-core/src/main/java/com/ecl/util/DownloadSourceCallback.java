package com.ecl.util;

import java.io.IOException;

/** Internal mirror-selection callback used by HTTP feature components. */
interface DownloadSourceCallback {
    void onSource(String originalUrl, String candidateUrl, boolean mirror, String sourceName);

    void onFailure(String candidateUrl, IOException error);
}
