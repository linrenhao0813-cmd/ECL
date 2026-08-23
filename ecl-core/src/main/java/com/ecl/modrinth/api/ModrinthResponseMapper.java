package com.ecl.modrinth.api;

import com.ecl.modrinth.api.dto.ModDependencyDto;
import com.ecl.modrinth.api.dto.ModFileDto;
import com.ecl.modrinth.api.dto.ModProjectDto;
import com.ecl.modrinth.api.dto.ModVersionDto;
import com.ecl.modrinth.model.DependencyType;
import com.ecl.modrinth.model.ModDependency;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModVersion;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** Maps Modrinth transport DTOs into provider-neutral domain models. */
final class ModrinthResponseMapper {
    private static final Set<String> LOADER_CATEGORIES = Set.of(
            "fabric", "quilt", "forge", "neoforge");

    List<ModProject> projects(List<ModProjectDto> values) {
        return safeList(values).stream().map(this::project).toList();
    }

    ModProject project(ModProjectDto dto) {
        if (dto == null) {
            throw new ModrinthApiException("Modrinth returned an empty project", 200, false);
        }
        Set<String> categories = new LinkedHashSet<>(safeList(dto.categories()));
        categories.addAll(safeList(dto.displayCategories()));
        List<String> loaders = categories.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(LOADER_CATEGORIES::contains)
                .distinct()
                .toList();
        String license = licenseName(dto.license());
        Map<String, String> links = dto.links() == null ? Map.of() : dto.links();
        String slug = dto.slug() == null ? "" : dto.slug();
        return new ModProject(
                nullToEmpty(dto.projectId()), slug, nullToEmpty(dto.title()),
                nullToEmpty(dto.author()), nullToEmpty(dto.description()), nullToEmpty(dto.body()),
                dto.downloads(), dto.follows(), parseUri(dto.iconUrl()), parseInstant(dto.updated()),
                categories, safeList(dto.versions()), loaders, license,
                nullToEmpty(dto.clientSide()), nullToEmpty(dto.serverSide()),
                slug.isBlank() ? null : URI.create("https://modrinth.com/mod/"
                        + encodePathSegment(slug)),
                parseUri(firstNonBlank(dto.sourceUrl(), links.get("source_url"))),
                parseUri(firstNonBlank(dto.issuesUrl(), links.get("issues_url"))));
    }

    List<ModVersion> versions(List<ModVersionDto> values) {
        return safeList(values).stream().map(this::version).toList();
    }

    ModVersion version(ModVersionDto dto) {
        List<ModFile> files = safeList(dto.files()).stream().map(this::file).toList();
        List<ModDependency> dependencies = safeList(dto.dependencies()).stream()
                .map(this::dependency).toList();
        return new ModVersion(
                nullToEmpty(dto.id()), nullToEmpty(dto.projectId()), nullToEmpty(dto.name()),
                nullToEmpty(dto.versionNumber()), nullToEmpty(dto.versionType()), dto.featured(),
                nullToEmpty(dto.status()), safeList(dto.gameVersions()), safeList(dto.loaders()),
                parseInstant(dto.datePublished()), nullToEmpty(dto.changelog()), files, dependencies);
    }

    Map<String, ModVersion> versionsByHash(Map<String, ModVersionDto> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, ModVersion> result = new LinkedHashMap<>();
        values.forEach((hash, version) -> result.put(hash, version(version)));
        return Map.copyOf(result);
    }

    private ModFile file(ModFileDto dto) {
        return new ModFile(parseUri(dto.url()), nullToEmpty(dto.filename()), dto.hashes(),
                dto.primary(), dto.size(), nullToEmpty(dto.fileType()));
    }

    private ModDependency dependency(ModDependencyDto dto) {
        return new ModDependency(nullToEmpty(dto.versionId()), nullToEmpty(dto.projectId()),
                nullToEmpty(dto.fileName()), DependencyType.fromApiValue(dto.dependencyType()));
    }

    private static String licenseName(JsonNode license) {
        if (license == null || license.isNull()) {
            return "";
        }
        if (license.isTextual()) {
            return license.asText("");
        }
        return firstNonBlank(license.path("name").asText(""), license.path("id").asText(""));
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String encodePathSegment(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static URI parseUri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : nullToEmpty(second);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
