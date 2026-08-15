package org.dar316.spring_ai.dto.github.client;

public record GitHubTreeEntry(
        String path,
        String mode,
        String type,
        Long size,
        String sha
) {
}
