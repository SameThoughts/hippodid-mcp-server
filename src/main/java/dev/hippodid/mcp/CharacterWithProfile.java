package dev.hippodid.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Local DTO for deserializing the full {@code GET /v1/characters/{id}} response,
 * including profile fields that the starter SDK's {@code CharacterInfo} drops.
 *
 * <p>The starter SDK uses {@code @JsonIgnoreProperties(ignoreUnknown = true)} on its
 * internal {@code CharacterResponse}, silently discarding profile, aliases, and
 * categories. This local DTO captures the profile data needed for auto-recall
 * instructions injection.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record CharacterWithProfile(
        String id,
        String name,
        String description,
        ProfileDto profile) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProfileDto(
            String systemPrompt,
            String personality,
            String background,
            List<String> rules,
            Map<String, String> customFields) {}
}
