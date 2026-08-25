package com.ecl.download;

import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.MinecraftRuleUtil;
import com.ecl.util.NetworkUriPolicy;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;

/** Shared validation and platform helpers used by the active game download workflow. */
final class InstallHelpers {
    private InstallHelpers() {
    }

    static boolean needsDownload(File target, String expectedSha1, boolean verifyExisting) {
        if (!target.isFile()) {
            return true;
        }
        return !hasSha1(expectedSha1)
                || verifyExisting && !FileUtil.verifySha1(target, expectedSha1);
    }

    static void verifyDownloadedFile(File target, String expectedSha1) throws IOException {
        String required = requireSha1(expectedSha1, target.getName());
        if (FileUtil.verifySha1(target, required)) {
            return;
        }
        if (!target.delete()) {
            target.deleteOnExit();
        }
        throw new IOException(target.getName() + " 的 SHA-1 校验失败");
    }

    static boolean hasSha1(String sha1) {
        return sha1 != null && sha1.matches("(?i)[0-9a-f]{40}");
    }

    static String requireSha1(String sha1, String label) throws IOException {
        if (!hasSha1(sha1)) {
            throw new IOException((label == null ? "Downloaded file" : label)
                    + " is missing a valid SHA-1 digest");
        }
        return sha1.toLowerCase(Locale.ROOT);
    }

    static String resolveRemoteSha1(String artifactUrl, String label) throws IOException {
        URI uri;
        try {
            uri = NetworkUriPolicy.requireHttpsOrLoopbackHttp(
                    URI.create(artifactUrl), label + " URL");
        } catch (IllegalArgumentException invalid) {
            throw new IOException(label + " URL is invalid", invalid);
        }
        String body = HttpUtil.get(uri + ".sha1").trim();
        String digest = body.isBlank() ? "" : body.split("\\s+", 2)[0];
        return requireSha1(digest, label);
    }

    static String nativeClassifierKey(JsonObject library, String osName, String archBits) {
        if (library.has("natives")) {
            JsonObject natives = library.getAsJsonObject("natives");
            if (natives.has(osName)) {
                return natives.get(osName).getAsString().replace("${arch}", archBits);
            }
        }
        return "natives-" + osName;
    }

    static String nativeClassifierKey(JsonObject library, JsonObject classifiers,
                                      String osName, String archBits,
                                      String nativeClassifier) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        boolean arm = nativeClassifier != null && nativeClassifier.endsWith("-arm64");
        if (arm) {
            candidates.addAll(Arrays.asList(MinecraftRuleUtil.nativeKeys(nativeClassifier)));
        }
        candidates.add(nativeClassifierKey(library, osName, archBits));
        candidates.addAll(Arrays.asList(MinecraftRuleUtil.nativeKeys(nativeClassifier)));
        return candidates.stream().filter(classifiers::has).findFirst().orElse(null);
    }
}
