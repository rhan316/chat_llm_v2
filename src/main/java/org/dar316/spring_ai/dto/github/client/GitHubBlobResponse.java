package org.dar316.spring_ai.dto.github.client;

public record GitHubBlobResponse(
        String encoding,
        String content,
        Long size
) {
}
