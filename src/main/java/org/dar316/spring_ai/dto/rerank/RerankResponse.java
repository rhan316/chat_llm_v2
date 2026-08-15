package org.dar316.spring_ai.dto.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RerankResponse(
        String id,
        String model,
        String provider,
        List<RerankResult> results,
        RerankUsage usage
) {}
