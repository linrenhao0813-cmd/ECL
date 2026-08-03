package com.ecl.modrinth.service;

import com.ecl.modrinth.model.ModCompatibility;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class DefaultModVersionSelector implements ModVersionSelector {
    private static final Set<String> INSTALLABLE_STATUSES = Set.of("listed");
    private static final Comparator<ModVersion> PREFERENCE =
            Comparator.comparingInt(DefaultModVersionSelector::releaseRank)
                    .thenComparing(ModVersion::featured, Comparator.reverseOrder())
                    .thenComparing(version -> version.publishedAt() == null ? Instant.EPOCH : version.publishedAt(),
                            Comparator.reverseOrder());

    @Override
    public Optional<ModVersion> selectBestVersion(List<ModVersion> versions, ModCompatibility compatibility,
                                                  ReleaseChannel channel) {
        if (versions == null || versions.isEmpty() || compatibility == null) {
            return Optional.empty();
        }
        ReleaseChannel effectiveChannel = channel == null ? ReleaseChannel.RELEASE_ONLY : channel;
        String expectedLoader = compatibility.loader().apiName();
        return versions.stream()
                .filter(version -> version != null
                        && version.gameVersions().contains(compatibility.minecraftVersion())
                        && version.loaders().stream().anyMatch(expectedLoader::equalsIgnoreCase)
                        && isInstallableStatus(version.status())
                        && allowsType(effectiveChannel, version.versionType())
                        && selectInstallFile(version).isPresent())
                .min(PREFERENCE);
    }

    @Override
    public Optional<ModFile> selectInstallFile(ModVersion version) {
        if (version == null || version.files() == null) {
            return Optional.empty();
        }
        List<ModFile> usable = version.files().stream()
                .filter(file -> file != null
                        && file.url() != null
                        && file.fileName() != null
                        && !file.fileName().isBlank()
                        && !isDevelopmentFile(file.fileName()))
                .toList();
        return usable.stream().filter(ModFile::primary).findFirst()
                .or(() -> usable.stream().findFirst());
    }

    private static boolean isInstallableStatus(String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        return INSTALLABLE_STATUSES.contains(status.toLowerCase(Locale.ROOT));
    }

    private static boolean allowsType(ReleaseChannel channel, String versionType) {
        return channel.allows(versionType);
    }

    private static int releaseRank(ModVersion version) {
        return switch (version.versionType() == null
                ? "" : version.versionType().toLowerCase(Locale.ROOT)) {
            case "release" -> 0;
            case "beta" -> 1;
            case "alpha" -> 2;
            default -> 3;
        };
    }

    private static boolean isDevelopmentFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        String base = lower.endsWith(".jar") ? lower.substring(0, lower.length() - 4) : lower;
        return base.endsWith("-sources")
                || base.endsWith("-source")
                || base.endsWith("-dev")
                || base.endsWith("-deobf")
                || base.endsWith("-javadoc");
    }
}
