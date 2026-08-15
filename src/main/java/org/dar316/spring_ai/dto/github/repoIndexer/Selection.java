package org.dar316.spring_ai.dto.github.repoIndexer;

import org.dar316.spring_ai.dto.github.client.GitHubTreeEntry;
import org.dar316.spring_ai.service.github.GitHubClient;

import java.util.List;

public record Selection(
        List<GitHubTreeEntry> files,
        int skippedFiles
) {
}
