package org.dar316.spring_ai.dto.github.client;

public record ResolvedRevision(
        String commitSha,
        String treeSha
) {
}
