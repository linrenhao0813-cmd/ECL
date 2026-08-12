package com.ecl.util;

import com.ecl.download.provider.DownloadProviderRegistry;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DownloadSourceUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadSourceUtil.class);
    private static final DownloadProviderRegistry PROVIDERS = new DownloadProviderRegistry();
    private DownloadSourceUtil() {
    }

    public static List<String> candidates(String originalUrl) {
        try {
            return PROVIDERS.candidates(new URI(originalUrl)).stream().map(URI::toString).toList();
        } catch (URISyntaxException e) {
            LOGGER.warn("Invalid download URL; mirror resolution skipped: {}", originalUrl, e);
            return List.of(originalUrl);
        }
    }

    public static boolean isMirror(String originalUrl, String candidateUrl) {
        return !originalUrl.equals(candidateUrl);
    }

    public static String sourceName(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null) {
                return url;
            }
            if (host.contains("fastmcmirror")) {
                return "FastMinecraftMirror";
            }
            if (host.contains("bmclapi")) {
                return "BMCLAPI";
            }
            return "官方源";
        } catch (URISyntaxException e) {
            return url;
        }
    }

}
