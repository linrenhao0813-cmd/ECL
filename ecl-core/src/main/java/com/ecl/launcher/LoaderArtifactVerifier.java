package com.ecl.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Verifies downloaded Loader artifacts against publisher-provided digests. */
final class LoaderArtifactVerifier {
    void verify(Path file, String algorithm, String expected) throws IOException {
        if (expected == null || expected.isBlank()) {
            throw new IOException("加载器安装器缺少发布方校验值");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                Files.deleteIfExists(file);
                throw new IOException("加载器安装器校验失败");
            }
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("不支持安装器校验算法 " + algorithm, error);
        }
    }
}
