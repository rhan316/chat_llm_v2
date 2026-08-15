package org.dar316.spring_ai.dto.rerank;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RerankResult(
        int index,

        @JsonProperty("relevance_score")
        @JsonAlias({"score", "relevanceScore"})
        double relevanceScore,

        RerankDocument document
) {}
