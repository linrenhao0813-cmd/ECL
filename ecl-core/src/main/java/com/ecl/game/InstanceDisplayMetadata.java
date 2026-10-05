package com.ecl.game;

import com.ecl.util.FileUtil;

import java.io.IOException;

/**
 * Launcher-local display metadata for one instance: an optional custom name, a favourite flag and
 * an optional cover image path. None of it affects launching; it only changes how the instance is
 * presented in the instance manager.
 */
public record InstanceDisplayMetadata(
        String profileId,
        String displayName,
        boolean favorite,
        String coverImage
) {
    public static final int MAX_DISPLAY_NAME_LENGTH = 64;

    public InstanceDisplayMetadata {
        profileId = requireValidProfileId(profileId);
        displayName = normalizeName(displayName);
        coverImage = coverImage == null ? "" : coverImage.trim();
    }

    public static InstanceDisplayMetadata empty(String profileId) {
        return new InstanceDisplayMetadata(profileId, "", false, "");
    }

    /** True when nothing is overridden, so the entry can be dropped before writing. */
    public boolean isMeaningful() {
        return !displayName.isEmpty() || favorite || !coverImage.isEmpty();
    }

    /** The name to show, falling back to {@code fallback} when no custom name is set. */
    public String effectiveName(String fallback) {
        return displayName.isEmpty() ? (fallback == null ? "" : fallback) : displayName;
    }

    public InstanceDisplayMetadata withDisplayName(String name) {
        return new InstanceDisplayMetadata(profileId, name, favorite, coverImage);
    }

    public InstanceDisplayMetadata withFavorite(boolean value) {
        return new InstanceDisplayMetadata(profileId, displayName, value, coverImage);
    }

    public InstanceDisplayMetadata withCoverImage(String path) {
        return new InstanceDisplayMetadata(profileId, displayName, favorite, path);
    }

    private static String normalizeName(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() > MAX_DISPLAY_NAME_LENGTH
                ? trimmed.substring(0, MAX_DISPLAY_NAME_LENGTH) : trimmed;
    }

    private static String requireValidProfileId(String id) {
        String value = id == null ? "" : id.trim();
        try {
            FileUtil.requireSafeVersionId(value);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid instance id for display metadata: " + id, invalid);
        }
        return value;
    }
}
