package org.dar316.spring_ai.dto.rag;

public record RagHit(
        String text,
        String source,
        String technology,
        String technologyVersion,
        Integer chunkIndex,
        Double vectorScore,
        Double rerankScore
) {
}
