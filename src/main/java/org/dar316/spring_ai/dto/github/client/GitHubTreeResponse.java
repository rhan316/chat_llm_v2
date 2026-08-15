package org.dar316.spring_ai.dto.github.client;

import java.util.List;

public record GitHubTreeResponse(
        boolean truncated,
        List<GitHubTreeEntry> tree
) {
}
