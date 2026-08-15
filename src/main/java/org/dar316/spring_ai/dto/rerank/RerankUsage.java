package org.dar316.spring_ai.dto.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RerankUsage(
        @JsonProperty("search_units")
        Integer searchUnits,

        @JsonProperty("total_tokens")
        Integer totalTokens
) {}
