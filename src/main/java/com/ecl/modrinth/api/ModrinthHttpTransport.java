package com.ecl.modrinth.api;

import com.ecl.util.HttpUtil;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
interface ModrinthHttpTransport {
    CompletableFuture<HttpUtil.Response> send(
            String method,
            URI uri,
            String body,
            Map<String, String> headers,
            Duration timeout
    );
}
