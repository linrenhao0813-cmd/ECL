package com.ecl.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DownloadSourceUtil {
    private DownloadSourceUtil() {
    }

    public static List<String> candidates(String originalUrl) {
        Set<String> urls = new LinkedHashSet<>();
        urls.add(originalUrl);

        try {
            URI uri = new URI(originalUrl);
            String host = uri.getHost();
            String path = uri.getRawPath();
            String query = uri.getRawQuery();
            if (host == null || path == null) {
                return new ArrayList<>(urls);
            }

            switch (host) {
                case "piston-meta.mojang.com" -> urls.add(build("bmclapi2.bangbang93.com", path, query));
                case "launchermeta.mojang.com" -> {
                    urls.add(build("launchermeta.fastmcmirror.org", path, query));
                    urls.add(build("bmclapi2.bangbang93.com", path, query));
                }
                case "launcher.mojang.com" -> urls.add(build("launcher.fastmcmirror.org", path, query));
                case "libraries.minecraft.net" -> {
                    urls.add(build("bmclapi2.bangbang93.com", "/maven" + path, query));
                    urls.add(build("libraries.fastmcmirror.org", path, query));
                }
                case "resources.download.minecraft.net" -> {
                    urls.add(build("bmclapi2.bangbang93.com", "/assets" + path, query));
                    urls.add(build("resources.fastmcmirror.org", path, query));
                }
                default -> {
                    // Keep unknown hosts on the official URL only.
                }
            }
        } catch (URISyntaxException ignored) {
        }

        return new ArrayList<>(urls);
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

    private static String build(String host, String path, String query) {
        return "https://" + host + path + (query == null || query.isBlank() ? "" : "?" + query);
    }
}
