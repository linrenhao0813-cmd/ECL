package com.ecl.download.provider;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Built-in public mirror mappings, evaluated only after the official URL. */
public final class MirrorDownloadProvider implements DownloadProvider {
    @Override
    public String id() {
        return "mirror";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean supports(URI original) {
        if (original == null || original.getHost() == null) return false;
        return switch (original.getHost()) {
            case "piston-meta.mojang.com", "launchermeta.mojang.com", "launcher.mojang.com",
                    "libraries.minecraft.net", "resources.download.minecraft.net" -> true;
            default -> false;
        };
    }

    @Override
    public List<URI> resolve(URI original) {
        List<URI> mirrors = new ArrayList<>();
        String path = original.getRawPath();
        String query = original.getRawQuery();
        switch (original.getHost()) {
            case "piston-meta.mojang.com" -> mirrors.add(build("bmclapi2.bangbang93.com", path, query));
            case "launchermeta.mojang.com" -> {
                mirrors.add(build("launchermeta.fastmcmirror.org", path, query));
                mirrors.add(build("bmclapi2.bangbang93.com", path, query));
            }
            case "launcher.mojang.com" -> mirrors.add(build("launcher.fastmcmirror.org", path, query));
            case "libraries.minecraft.net" -> {
                mirrors.add(build("bmclapi2.bangbang93.com", "/maven" + path, query));
                mirrors.add(build("libraries.fastmcmirror.org", path, query));
            }
            case "resources.download.minecraft.net" -> {
                mirrors.add(build("bmclapi2.bangbang93.com", "/assets" + path, query));
                mirrors.add(build("resources.fastmcmirror.org", path, query));
            }
            default -> {
            }
        }
        return List.copyOf(mirrors);
    }

    private static URI build(String host, String path, String query) {
        return URI.create("https://" + host + path
                + (query == null || query.isBlank() ? "" : "?" + query));
    }
}
