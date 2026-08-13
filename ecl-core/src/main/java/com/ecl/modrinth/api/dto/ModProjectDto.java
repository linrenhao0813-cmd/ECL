package com.ecl.modrinth.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ModProjectDto(
        @JsonAlias({"id", "project_id"}) String projectId,
        String slug,
        String title,
        String author,
        String description,
        String body,
        long downloads,
        @JsonAlias({"follows", "followers"}) long follows,
        @JsonProperty("icon_url") String iconUrl,
        @JsonAlias({"date_modified", "updated"}) String updated,
        List<String> categories,
        List<String> versions,
        @JsonProperty("display_categories") List<String> displayCategories,
        @JsonProperty("client_side") String clientSide,
        @JsonProperty("server_side") String serverSide,
        JsonNode license,
        Map<String, String> links,
        @JsonProperty("source_url") String sourceUrl,
        @JsonProperty("issues_url") String issuesUrl
) {
}
