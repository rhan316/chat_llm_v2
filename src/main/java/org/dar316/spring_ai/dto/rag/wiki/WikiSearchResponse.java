package org.dar316.spring_ai.dto.rag.wiki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WikiSearchResponse(Query query) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Query(List<SearchResult> search) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResult(String title) {}

}
