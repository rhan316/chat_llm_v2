package org.dar316.spring_ai.dto.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RerankRequest(
        String model,
        String query,
        List<String> documents,

        @JsonProperty("top_n")
        int topN
) {}
