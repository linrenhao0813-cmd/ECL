package com.ecl.game;

import com.ecl.util.JsonUtil;
import com.google.gson.JsonObject;

/**
 * Location of a version's asset index, used to resolve which resource files the game needs.
 *
 * @param id        short identifier (e.g. {@code 1.21} or {@code pre-1.7.10})
 * @param url       remote location of the index JSON
 * @param sha1      expected digest of the index file, may be blank
 * @param totalSize aggregate size of every indexed asset, or -1 when unknown
 */
public record AssetIndex(String id, String url, String sha1, long totalSize) {

    public static AssetIndex parse(JsonObject object) {
        if (object == null) {
            return null;
        }
        return new AssetIndex(
                JsonUtil.getString(object, "id", ""),
                JsonUtil.getString(object, "url", ""),
                JsonUtil.getString(object, "sha1", ""),
                JsonUtil.getLong(object, "totalSize", -1L));
    }

    public boolean hasId() {
        return id != null && !id.isBlank();
    }

    public boolean hasChecksum() {
        return sha1 != null && !sha1.isBlank();
    }
}