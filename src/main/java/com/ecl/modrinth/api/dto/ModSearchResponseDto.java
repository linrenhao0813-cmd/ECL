package com.ecl.modrinth.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ModSearchResponseDto(
        List<ModProjectDto> hits,
        int offset,
        int limit,
        @JsonProperty("total_hits") int totalHits
) {
}
