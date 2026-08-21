package com.ecl.util;

import java.io.File;

/** Internal progress contract used by the resumable downloader. */
interface DownloadProgressCallback {
    void onStart(long total);

    void onProgress(long downloaded, long total);

    void onComplete(File file);
}
