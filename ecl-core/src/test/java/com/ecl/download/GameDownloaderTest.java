package com.ecl.download;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameDownloaderTest {

    @Test
    void asynchronousDownloadFutureFailsWhenPreparationFails() {
        try (GameDownloader downloader = new GameDownloader(1)) {
            Future<?> future = downloader.downloadVersionAsync("../unsafe", "not-a-url");

            ExecutionException failure = assertThrows(ExecutionException.class, future::get);
            assertTrue(failure.getCause() != null);
        }
    }
}
