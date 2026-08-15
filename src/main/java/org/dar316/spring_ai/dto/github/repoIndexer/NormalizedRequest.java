package org.dar316.spring_ai.dto.github.repoIndexer;

public record NormalizedRequest(
        String owner,
        String repositoryName,
        String repository,
        String ref,
        String technology,
        String technologyVersion
) {
}
