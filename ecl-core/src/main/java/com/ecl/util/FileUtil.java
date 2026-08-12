package com.ecl.util;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileUtil.class);
    private static final int SHA1_BUFFER_SIZE = 64 * 1024;
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    public static String sha1(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not available", e);
        }

        try (FileChannel channel = FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(SHA1_BUFFER_SIZE);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }

        byte[] hash = digest.digest();
        char[] chars = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            int b = hash[i] & 0xFF;
            chars[i * 2] = HEX_DIGITS[b >>> 4];
            chars[i * 2 + 1] = HEX_DIGITS[b & 0x0F];
        }
        return new String(chars);
    }

    public static boolean verifySha1(File file, String expected) {
        try {
            return sha1(file).equalsIgnoreCase(expected);
        } catch (IOException e) {
            LOGGER.warn("Failed to verify SHA-1 for {}", file.getAbsolutePath(), e);
            return false;
        }
    }

    public static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static String getNativeClassifier() {
        String arch = System.getProperty("os.arch").toLowerCase();
        String osArch = arch.contains("64") ? "x86_64" : "x86";
        return PlatformUtil.current().minecraftName() + "-" + osArch;
    }
}
