package com.ecl.modrinth.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ModFileDto(
        String url,
        String filename,
        Map<String, String> hashes,
        boolean primary,
        long size,
        @JsonProperty("file_type") String fileType
) {
}
