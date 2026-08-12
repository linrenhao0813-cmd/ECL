package com.ecl.modrinth.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ModDependencyDto(
        @JsonProperty("version_id") String versionId,
        @JsonProperty("project_id") String projectId,
        @JsonProperty("file_name") String fileName,
        @JsonProperty("dependency_type") String dependencyType
) {
}
