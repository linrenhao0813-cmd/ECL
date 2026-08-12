package com.ecl.game;

import com.ecl.util.JsonUtil;
import com.google.gson.JsonObject;

/**
 * A downloadable file described by the Minecraft version metadata:
 * its URL, the path it occupies under the launcher's libraries/assets directory,
 * and the SHA-1 used to verify it after download.
 *
 * @param path     relative path under the library/assets root as given by the metadata
 * @param fileName file name derived from {@code path}; empty when the path is absent
 * @param url      remote download location
 * @param sha1     expected SHA-1 digest, may be blank for objects that do not carry one
 * @param size     declared byte size, or -1 when unknown
 */
public record DownloadObject(String path, String fileName, String url, String sha1, long size) {

    /** Parse the {@code art} object found under {@code downloads.artifact/…} or {@code client/server}. */
    public static DownloadObject parse(JsonObject object) {
        if (object == null) {
            return null;
        }
        String path = JsonUtil.getString(object, "path", "");
        String name = path.isBlank() ? JsonUtil.getString(object, "id", "") : fileNameOf(path);
        return new DownloadObject(
                path,
                name,
                JsonUtil.getString(object, "url", ""),
                JsonUtil.getString(object, "sha1", ""),
                JsonUtil.getLong(object, "size", -1L));
    }

    private static String fileNameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** True when the metadata promises a checksum to verify against. */
    public boolean hasChecksum() {
        return sha1 != null && !sha1.isBlank();
    }
}