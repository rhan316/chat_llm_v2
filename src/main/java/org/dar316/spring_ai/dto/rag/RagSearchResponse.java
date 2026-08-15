package org.dar316.spring_ai.dto.rag;

import java.util.List;

public record RagSearchResponse(
        String query,
        List<RagHit> results
) {
}
