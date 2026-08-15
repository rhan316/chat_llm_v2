package org.dar316.spring_ai.dto.rag.stackoverflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Odpowiedź endpointu /2.3/search/advanced Stack Exchange API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StackOverflowSearchResponse(
        List<Item> items
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            @JsonProperty("question_id") long questionId,
            String title,
            String link,
            @JsonProperty("is_answered") boolean isAnswered,
            @JsonProperty("answer_count") int answerCount,
            @JsonProperty("score") int score
    ) {}
}
