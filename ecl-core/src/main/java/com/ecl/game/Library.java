package com.ecl.game;

import com.ecl.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A library dependency declared in a version JSON. Supports the two Minecraft layouts: an
 * {@code artifact} (the plain jar) and/or a set of {@code classifiers} (native libraries for
 * specific platforms), both guarded by an optional {@code rules} list.
 */
public final class Library {

    private final String name;
    private final DownloadObject artifact;
    private final Map<String, DownloadObject> classifiers;
    private final Map<String, String> natives;
    private final JsonObject raw;

    private Library(String name, DownloadObject artifact,
                    Map<String, DownloadObject> classifiers,
                    Map<String, String> natives,
                    JsonObject raw) {
        this.name = name;
        this.artifact = artifact;
        this.classifiers = classifiers;
        this.natives = natives;
        this.raw = raw;
    }

    /** Parse a library entry; returns {@code null} for entries without any download target. */
    public static Library parse(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject library = element.getAsJsonObject();

        DownloadObject artifact = null;
        Map<String, DownloadObject> classifiers = Map.of();
        JsonObject downloads = library.has("downloads") && library.get("downloads").isJsonObject()
                ? library.getAsJsonObject("downloads") : null;
        if (downloads != null) {
            if (downloads.has("artifact") && downloads.get("artifact").isJsonObject()) {
                artifact = DownloadObject.parse(downloads.getAsJsonObject("artifact"));
            }
            if (downloads.has("classifiers") && downloads.get("classifiers").isJsonObject()) {
                JsonObject classifierObjects = downloads.getAsJsonObject("classifiers");
                Map<String, DownloadObject> parsed = new LinkedHashMap<>();
                for (String classifier : classifierObjects.keySet()) {
                    JsonElement classifierObject = classifierObjects.get(classifier);
                    if (classifierObject != null && classifierObject.isJsonObject()) {
                        parsed.put(classifier, DownloadObject.parse(classifierObject.getAsJsonObject()));
                    }
                }
                if (!parsed.isEmpty()) {
                    classifiers = Collections.unmodifiableMap(parsed);
                }
            }
        }

        if (artifact == null && classifiers.isEmpty()) {
            // A library with neither artifact nor classifiers cannot be downloaded.
            return null;
        }

        Map<String, String> natives = Map.of();
        if (library.has("natives") && library.get("natives").isJsonObject()) {
            JsonObject nativeMap = library.getAsJsonObject("natives");
            Map<String, String> parsed = new LinkedHashMap<>();
            for (String osName : nativeMap.keySet()) {
                String template = JsonUtil.getString(nativeMap, osName, "");
                if (!template.isBlank()) {
                    parsed.put(osName, template);
                }
            }
            if (!parsed.isEmpty()) {
                natives = Collections.unmodifiableMap(parsed);
            }
        }

        return new Library(
                JsonUtil.getString(library, "name", ""),
                artifact,
                classifiers,
                natives,
                library);
    }

    /** Maven-style coordinate of the library, may be empty. */
    public String name() {
        return name;
    }

    /** The plain jar artifact, or null when the metadata only supplies classifiers. */
    public DownloadObject artifact() {
        return artifact;
    }

    /** Platform classifiers (native libraries), keyed by classifier name; never null. */
    public Map<String, DownloadObject> classifiers() {
        return classifiers;
    }

    /** OS-to-classifier-template map (e.g. {@code windows -> natives-${arch}}); never null. */
    public Map<String, String> natives() {
        return natives;
    }

    /** True when the entry declares platform-specific classifier downloads. */
    public boolean hasNatives() {
        return !natives.isEmpty() && !classifiers.isEmpty();
    }

    /** The raw library object; kept for rule evaluation by the launch pipeline. */
    public JsonObject raw() {
        return raw;
    }

    @Override
    public String toString() {
        return name.isBlank() ? "(unnamed library)" : name;
    }
}