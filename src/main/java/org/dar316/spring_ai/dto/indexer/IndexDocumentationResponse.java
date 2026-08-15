package org.dar316.spring_ai.dto.indexer;

public record IndexDocumentationResponse(
        String source,
        String technology,
        String technologyVersion,
        int chunks
) {
}
