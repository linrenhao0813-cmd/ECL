package com.ecl.modrinth.download;

import com.ecl.modrinth.api.HashMismatchException;
import com.ecl.util.HttpUtil;
import com.ecl.util.NetworkUriPolicy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class ModFileDownloadService {
    private static final long MAX_MOD_FILE_BYTES = 2L * 1024 * 1024 * 1024;
    private final ExecutorService executor;
    private final HashVerifier hashVerifier;
    private final DownloadUriResolver uriResolver;

    public ModFileDownloadService(ExecutorService executor, HashVerifier hashVerifier) {
        this(executor, hashVerifier, uri -> uri);
    }

    public ModFileDownloadService(ExecutorService executor, HashVerifier hashVerifier,
                                  DownloadUriResolver uriResolver) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.hashVerifier = Objects.requireNonNull(hashVerifier, "hashVerifier");
        this.uriResolver = Objects.requireNonNull(uriResolver, "uriResolver");
    }

    public CompletableFuture<List<DownloadedModFile>> downloadAll(
            Collection<ModDownloadRequest> requests,
            Consumer<ModDownloadProgress> progressListener
    ) {
        List<ModDownloadRequest> work = requests == null ? List.of() : List.copyOf(requests);
        long overallTotal = work.stream().mapToLong(request -> Math.max(0, request.expectedSize())).sum();
        AtomicLong overallDownloaded = new AtomicLong();
        List<CompletableFuture<DownloadedModFile>> futures = new ArrayList<>(work.size());
        for (ModDownloadRequest request : work) {
            futures.add(submitDownload(
                    request, overallDownloaded, overallTotal, progressListener));
        }
        CompletableFuture<List<DownloadedModFile>> result = CompletableFuture.allOf(
                        futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
        result.whenComplete((ignored, error) -> {
            // A failed sibling does not mean queued downloads were cancelled by the caller. Let
            // them finish so their progress/cleanup remains deterministic; an explicit batch
            // cancellation still propagates to every child.
            if (result.isCancelled()) {
                futures.forEach(future -> future.cancel(true));
            }
        });
        return result;
    }

    private CompletableFuture<DownloadedModFile> submitDownload(
            ModDownloadRequest request,
            AtomicLong overallDownloaded,
            long overallTotal,
            Consumer<ModDownloadProgress> progressListener
    ) {
        CompletableFuture<DownloadedModFile> future = new CompletableFuture<>();
        AtomicReference<Future<?>> taskReference = new AtomicReference<>();
        Future<?> task = executor.submit(() -> {
            try {
                future.complete(downloadWithHashRetry(
                        request, overallDownloaded, overallTotal, progressListener));
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        taskReference.set(task);
        future.whenComplete((ignored, error) -> {
            if (future.isCancelled()) {
                Future<?> submitted = taskReference.get();
                if (submitted != null) {
                    submitted.cancel(true);
                }
            }
        });
        return future;
    }

    private DownloadedModFile downloadWithHashRetry(
            ModDownloadRequest request,
            AtomicLong overallDownloaded,
            long overallTotal,
            Consumer<ModDownloadProgress> listener
    ) {
        URI downloadUri;
        try {
            downloadUri = uriResolver.resolve(request.uri());
        } catch (IOException error) {
            throw new java.util.concurrent.CompletionException(error);
        }
        if (downloadUri == null || downloadUri.getScheme() == null) {
            throw new java.util.concurrent.CompletionException(
                    new IOException("无法解析模组下载地址: " + request.fileName()));
        }
        try {
            downloadUri = NetworkUriPolicy.requireArtifactDownload(
                    downloadUri, "模组下载地址");
        } catch (IOException unsafe) {
            throw new java.util.concurrent.CompletionException(
                    new IOException("拒绝不安全的模组下载地址: " + request.fileName(), unsafe));
        }
        if (!HashVerifier.hasUsableExpectedHash(request.expectedHashes())) {
            throw new java.util.concurrent.CompletionException(
                    new IOException("模组文件缺少可验证的 SHA-512 或 SHA-1: " + request.fileName()));
        }
        if (request.expectedSize() <= 0 || request.expectedSize() > MAX_MOD_FILE_BYTES) {
            throw new java.util.concurrent.CompletionException(
                    new IOException("模组文件大小声明无效: " + request.fileName()));
        }
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            throwIfInterrupted(request);
            AtomicLong previousFileBytes = new AtomicLong();
            try {
                Files.createDirectories(request.temporaryFile().getParent());
                Files.deleteIfExists(request.temporaryFile());
                long startedAt = System.nanoTime();
                HttpUtil.downloadFileWithProgress(
                        downloadUri.toString(),
                        request.temporaryFile().toFile(),
                        new HttpUtil.ProgressCallback() {
                            @Override
                            public void onStart(long total) {
                                notifyProgress(listener, request, 0, total, overallDownloaded.get(),
                                        overallTotal, 0);
                            }

            @Override
            public void onProgress(long downloaded, long total) {
                                long delta = Math.max(0, downloaded - previousFileBytes.getAndSet(downloaded));
                                long aggregate = overallDownloaded.addAndGet(delta);
                                double seconds = Math.max(0.001,
                                        Duration.ofNanos(System.nanoTime() - startedAt).toNanos() / 1_000_000_000.0);
                                notifyProgress(listener, request, downloaded, total, aggregate,
                                        overallTotal, downloaded / seconds);
                            }

                            @Override
                            public void onComplete(java.io.File file) {
                            }
                        }, null, request.expectedSize());
                throwIfInterrupted(request);
                long size = Files.size(request.temporaryFile());
                if (size != request.expectedSize()) {
                    throw new IOException("模组文件大小与元数据不一致: " + request.fileName());
                }
                HashVerifier.HashResult hashes =
                        hashVerifier.verify(request.temporaryFile(), request.expectedHashes());
                throwIfInterrupted(request);
                return new DownloadedModFile(request, request.temporaryFile(), hashes, size);
            } catch (HashMismatchException e) {
                failure = e;
                subtractPartial(overallDownloaded, previousFileBytes.get());
                deleteQuietly(request.temporaryFile());
            } catch (IOException e) {
                subtractPartial(overallDownloaded, previousFileBytes.get());
                deleteQuietly(request.temporaryFile());
                if (Thread.currentThread().isInterrupted()
                        || e instanceof java.nio.channels.ClosedByInterruptException
                        || e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    CancellationException cancellation =
                            new CancellationException("Mod download cancelled: " + request.fileName());
                    cancellation.initCause(e);
                    throw cancellation;
                }
                throw new java.util.concurrent.CompletionException(e);
            }
        }
        throw failure == null
                ? new HashMismatchException("Hash verification failed: " + request.fileName())
                : failure;
    }

    private static void throwIfInterrupted(ModDownloadRequest request) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Mod download cancelled: " + request.fileName());
        }
    }

    private static void subtractPartial(AtomicLong overallDownloaded, long downloadedBytes) {
        overallDownloaded.updateAndGet(value -> Math.max(0, value - downloadedBytes));
    }

    private static void deleteQuietly(java.nio.file.Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best-effort cleanup of a stale partial or temporary file.
        }
    }

    private static void notifyProgress(
            Consumer<ModDownloadProgress> listener,
            ModDownloadRequest request,
            long downloaded,
            long total,
            long overallDownloaded,
            long overallTotal,
            double speed
    ) {
        if (listener != null) {
            listener.accept(new ModDownloadProgress(
                    request.fileName(), downloaded, total,
                    overallDownloaded, overallTotal, speed));
        }
    }
}
