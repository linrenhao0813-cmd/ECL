package com.ecl.modrinth.ui;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Unwraps asynchronous mod-operation failures consistently. */
final class ModFailureMessages {
    private ModFailureMessages() {
    }

    static String planFailureReason(Throwable error) {
        Throwable cause = unwrap(error);
        return cause == null || cause.getMessage() == null || cause.getMessage().isBlank()
                ? "操作失败，请查看日志获取详细信息" : cause.getMessage();
    }

    static String errorMessage(Throwable error) {
        Throwable cause = unwrap(error);
        return cause == null || cause.getMessage() == null || cause.getMessage().isBlank()
                ? "未知错误" : cause.getMessage();
    }

    static boolean isCancellation(Throwable error) {
        return unwrap(error) instanceof CancellationException;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
