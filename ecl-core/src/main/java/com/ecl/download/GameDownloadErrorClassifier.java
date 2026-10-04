package com.ecl.download;

import java.util.Locale;

/** Maps common game-download failures to fixed user-facing copy. */
final class GameDownloadErrorClassifier {
    private GameDownloadErrorClassifier() {
    }

    static String classify(Exception failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if (isNetworkFailure(failure) || lower.contains("connect") || lower.contains("timeout")
                || lower.contains("timed out") || lower.contains("unknownhost")
                || lower.contains("网络") || lower.contains("连接")) {
            return "网络连接失败，请检查网络连接后重试。";
        }
        if (lower.contains("sha-1") || lower.contains("sha1") || lower.contains("hash")
                || lower.contains("checksum") || lower.contains("size does not match")
                || lower.contains("校验") || lower.contains("哈希")) {
            return "下载文件校验失败，请重试下载。";
        }
        if (lower.contains("mirror") || lower.contains("镜像") || lower.contains("下载源")
                || lower.contains("source")) {
            return "下载源不可用，请稍后重试。";
        }
        return "下载失败: " + message;
    }

    private static boolean isNetworkFailure(Exception failure) {
        return failure instanceof java.net.ConnectException
                || failure instanceof java.net.UnknownHostException
                || failure instanceof java.net.SocketTimeoutException
                || failure instanceof java.net.http.HttpTimeoutException
                || failure instanceof javax.net.ssl.SSLException;
    }
}
