package com.ecl.modrinth.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ModVersionDto(
        String id,
        @JsonProperty("project_id") String projectId,
        String name,
        @JsonProperty("version_number") String versionNumber,
        @JsonProperty("version_type") String versionType,
        boolean featured,
        String status,
        @JsonProperty("game_versions") List<String> gameVersions,
        List<String> loaders,
        @JsonProperty("date_published") String datePublished,
        String changelog,
        List<ModFileDto> files,
        List<ModDependencyDto> dependencies
) {
}
