package org.dar316.spring_ai.dto.github;

public record GitHubIndexResponse(
        String repository,
        String requestedRef,
        String commitSha,
        int indexedFiles,
        int skippedFiles,
        int chunks
) {
}
