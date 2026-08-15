package org.dar316.spring_ai.dto.github.client;

public record GitHubCommitResponse(
        String sha,
        GitHubCommitDetails commit
) {
}
