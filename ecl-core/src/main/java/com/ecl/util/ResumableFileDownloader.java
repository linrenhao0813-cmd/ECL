package com.ecl.util;

import com.ecl.ECLConfig;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mirror-aware file downloader with resumable partial files and byte limits. */
final class ResumableFileDownloader {
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final Pattern UNSATISFIABLE_RANGE = Pattern.compile("bytes \\*/(\\d+)");

    private ResumableFileDownloader() {
    }

    static void download(String url, File target, DownloadProgressCallback progress,
                         DownloadSourceCallback source, long maxBytes) throws IOException {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("Download byte limit must not be negative");
        }
        ensureParentDirectory(target);
        File partial = new File(target.getAbsolutePath() + ".part");
        File metadataFile = new File(target.getAbsolutePath() + ".part.meta");

        IOException lastError = null;
        for (String candidate : DownloadSourceUtil.candidates(url)) {
            DownloadRateLimiter.checkInterrupted();
            boolean mirror = DownloadSourceUtil.isMirror(url, candidate);
            DownloadSourceCallbacks.notifySource(source, url, candidate, mirror);
            try {
                downloadCandidate(candidate, mirror, target, partial, metadataFile,
                        progress, maxBytes);
                return;
            } catch (DownloadLimitExceededException failure) {
                Files.deleteIfExists(partial.toPath());
                Files.deleteIfExists(metadataFile.toPath());
                DownloadSourceCallbacks.notifyFailure(source, candidate, failure);
                throw failure;
            } catch (IOException failure) {
                lastError = failure;
                DownloadSourceCallbacks.notifyFailure(source, candidate, failure);
                if (Thread.currentThread().isInterrupted()) {
                    throw failure;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                lastError = new IOException("Download interrupted", interrupted);
            }
        }
        throw lastError == null
                ? new IOException("No download source available: " + url)
                : lastError;
    }

    private static void downloadCandidate(
            String candidate, boolean mirror, File target, File partial, File metadataFile,
            DownloadProgressCallback progress, long maxBytes)
            throws IOException, InterruptedException {
        PartialDownloadMetadata metadata = readMetadata(metadataFile);
        boolean sameSource = metadata != null && candidate.equals(metadata.source())
                && !metadata.validator().isBlank();
        if (!sameSource) {
            Files.deleteIfExists(partial.toPath());
            Files.deleteIfExists(metadataFile.toPath());
            metadata = null;
        }
        long existingBytes = sameSource && partial.isFile() ? partial.length() : 0;
        if (existingBytes > maxBytes) {
            throw new DownloadLimitExceededException("Partial download exceeds byte limit");
        }

        URI requestUri;
        try {
            requestUri = NetworkUriPolicy.requireHttpsOrLoopbackHttp(
                    URI.create(candidate), "download URL");
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid download URL: " + candidate, invalid);
        }
        int redirects = 0;
        while (true) {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(requestUri)
                    .timeout(Duration.ofMillis(timeoutFor(mirror)))
                    .header("User-Agent", "ECL/1.0")
                    .GET();
            if (existingBytes > 0) {
                requestBuilder.header("Range", "bytes=" + existingBytes + "-");
                String validator = metadata == null ? "" : metadata.validator();
                if (!validator.isBlank()) {
                    requestBuilder.header("If-Range", validator);
                }
            }

            HttpResponse<InputStream> response = HttpClientProvider.defaultClient().send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            int statusCode = response.statusCode();
            if (statusCode >= 300 && statusCode < 400) {
                String location = response.headers().firstValue("Location").orElse("");
                response.body().close();
                if (location.isBlank() || ++redirects > 5) {
                    throw new IOException("Too many or invalid download redirects: " + candidate);
                }
                try {
                    requestUri = NetworkUriPolicy.requireHttpsOrLoopbackHttp(
                            requestUri.resolve(location), "download redirect");
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Invalid download redirect: " + location, invalid);
                }
                continue;
            }
            if (statusCode == 416 && existingBytes > 0) {
                long totalSize = unsatisfiedRangeTotal(response);
                response.body().close();
                if (totalSize < 0) {
                    throw new IOException("HTTP 416 without a valid Content-Range for " + candidate);
                }
                if (existingBytes == totalSize) {
                    if (totalSize > maxBytes) {
                        throw new DownloadLimitExceededException(
                                "Download exceeds byte limit: " + totalSize + " > " + maxBytes);
                    }
                    promote(partial.toPath(), target.toPath());
                    Files.deleteIfExists(metadataFile.toPath());
                    if (progress != null) {
                        progress.onStart(totalSize);
                        progress.onProgress(totalSize, totalSize);
                        progress.onComplete(target);
                    }
                    return;
                }
                if (totalSize >= 0 && existingBytes > totalSize) {
                    Files.deleteIfExists(partial.toPath());
                    Files.deleteIfExists(metadataFile.toPath());
                    existingBytes = 0;
                    metadata = null;
                    continue;
                }
            }
            if (statusCode < 200 || statusCode >= 300) {
                String errorBody = HttpRequestExecutor.readStream(response.body());
                if (errorBody.isBlank()) {
                    throw new IOException("HTTP " + statusCode + " for " + candidate);
                }
                throw new IOException("HTTP " + statusCode + " for " + candidate + ": "
                        + TextUtil.abbreviate(errorBody, 240));
            }

            long responseLength = response.headers().firstValueAsLong("Content-Length")
                    .orElse(-1);
            boolean resumed = statusCode == 206 && existingBytes > 0;
            long totalLength;
            if (resumed) {
                totalLength = validatedRangeTotal(
                        response, existingBytes, responseLength, metadata);
                if (totalLength < 0) {
                    response.body().close();
                    Files.deleteIfExists(partial.toPath());
                    Files.deleteIfExists(metadataFile.toPath());
                    existingBytes = 0;
                    metadata = null;
                    continue;
                }
            } else {
                if (statusCode == 206) {
                    response.body().close();
                    throw new IOException(
                            "Unexpected partial response without a resume request: " + candidate);
                }
                existingBytes = 0;
                totalLength = responseLength;
            }
            if (totalLength > maxBytes) {
                response.body().close();
                throw new DownloadLimitExceededException(
                        "Download exceeds byte limit: " + totalLength + " > " + maxBytes);
            }

            PartialDownloadMetadata current = new PartialDownloadMetadata(candidate,
                    response.headers().firstValue("ETag").orElse(""),
                    response.headers().firstValue("Last-Modified").orElse(""));
            writeMetadata(metadataFile, current);
            if (progress != null) {
                progress.onStart(totalLength);
            }
            try (InputStream input = response.body();
                 OutputStream output = resumed
                         ? Files.newOutputStream(partial.toPath(), StandardOpenOption.APPEND)
                         : Files.newOutputStream(partial.toPath(), StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                copyToFile(input, output, existingBytes, totalLength, progress, maxBytes);
            }
            if (totalLength >= 0 && partial.length() != totalLength) {
                throw new IOException("Downloaded size does not match HTTP response: expected "
                        + totalLength + ", got " + partial.length());
            }
            promote(partial.toPath(), target.toPath());
            Files.deleteIfExists(metadataFile.toPath());
            if (progress != null) {
                progress.onComplete(target);
            }
            return;
        }
    }

    private static long validatedRangeTotal(HttpResponse<?> response, long existingBytes,
                                            long responseLength,
                                            PartialDownloadMetadata metadata) {
        String header = response.headers().firstValue("Content-Range").orElse("");
        Matcher matcher = CONTENT_RANGE.matcher(header);
        if (!matcher.matches()) {
            return -1;
        }
        try {
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            long total = Long.parseLong(matcher.group(3));
            long rangeLength = Math.addExact(Math.subtractExact(end, start), 1L);
            boolean validatorMatches = metadata != null && metadata.matches(response);
            return start == existingBytes && end >= start && total > end && validatorMatches
                    && (responseLength < 0 || responseLength == rangeLength) ? total : -1;
        } catch (ArithmeticException | NumberFormatException invalid) {
            return -1;
        }
    }

    private static long unsatisfiedRangeTotal(HttpResponse<?> response) {
        String header = response.headers().firstValue("Content-Range").orElse("");
        Matcher matcher = UNSATISFIABLE_RANGE.matcher(header);
        if (!matcher.matches()) {
            return -1;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static PartialDownloadMetadata readMetadata(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            JsonObject json = JsonFileStore.read(file);
            return new PartialDownloadMetadata(
                    JsonUtil.getString(json, "source", ""),
                    JsonUtil.getString(json, "etag", ""),
                    JsonUtil.getString(json, "lastModified", ""));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static void writeMetadata(File file, PartialDownloadMetadata metadata)
            throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("source", metadata.source());
        json.addProperty("etag", metadata.etag());
        json.addProperty("lastModified", metadata.lastModified());
        Path target = file.toPath().toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Partial metadata has no parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".part-meta-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GsonProvider.pretty().toJson(json, writer);
            }
            promote(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void copyToFile(
            InputStream input, OutputStream output, long initialBytes, long contentLength,
            DownloadProgressCallback progress, long maxBytes) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long totalRead = initialBytes;
        long lastReportedAt = 0;
        long lastReportedBytes = initialBytes;
        int read;
        while ((read = input.read(buffer)) != -1) {
            DownloadRateLimiter.checkInterrupted();
            if (read > maxBytes - totalRead) {
                throw new DownloadLimitExceededException(
                        "Download exceeded byte limit while streaming");
            }
            DownloadRateLimiter.acquire(read);
            output.write(buffer, 0, read);
            totalRead += read;
            if (progress != null) {
                long now = System.nanoTime();
                boolean shouldReport = now - lastReportedAt >= 100_000_000L
                        || totalRead == contentLength
                        || totalRead >= lastReportedBytes * 2;
                if (shouldReport) {
                    progress.onProgress(totalRead, contentLength);
                    lastReportedAt = now;
                    lastReportedBytes = totalRead;
                }
            }
        }
        if (progress != null && totalRead != lastReportedBytes) {
            progress.onProgress(totalRead, contentLength);
        }
    }

    private static void ensureParentDirectory(File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Failed to create directory: " + parent);
        }
    }

    private static void promote(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int timeoutFor(boolean mirror) {
        return mirror ? ECLConfig.MIRROR_SOURCE_TIMEOUT_MS
                : ECLConfig.OFFICIAL_SOURCE_TIMEOUT_MS;
    }

    private static final class DownloadLimitExceededException extends IOException {
        private DownloadLimitExceededException(String message) {
            super(message);
        }
    }
}
