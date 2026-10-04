package com.ecl.util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** An exclusive cross-process lock whose marker file is removed when the lease closes. */
public final class FileLockLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private FileLockLease(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Returns {@code null} when another process or thread currently owns the lock. */
    public static FileLockLease tryAcquire(Path lockFile) throws IOException {
        Path absolute = lockFile.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        FileChannel channel = FileChannel.open(absolute,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            return new FileLockLease(channel, lock);
        } catch (OverlappingFileLockException busy) {
            channel.close();
            return null;
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } catch (IOException error) {
            failure = error;
        }
        try {
            channel.close();
        } catch (IOException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        // Keep the marker file in place. It is the stable rendezvous point for other processes;
        // deleting it after releasing the OS lock permits a concurrent caller to create a new
        // inode and acquire a second logical lease.
        if (failure != null) {
            throw failure;
        }
    }
}
