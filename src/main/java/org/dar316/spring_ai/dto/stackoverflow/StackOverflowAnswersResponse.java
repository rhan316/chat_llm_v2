package org.dar316.spring_ai.dto.rag.stackoverflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Odpowiedź endpointu /2.3/questions/{ids}/answers Stack Exchange API.
 * Wymaga filter=withbody, żeby pole "body" (treść HTML odpowiedzi) było obecne.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StackOverflowAnswersResponse(
        List<Item> items
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            @JsonProperty("answer_id") long answerId,
            @JsonProperty("is_accepted") boolean isAccepted,
            int score,
            String body
    ) {}
}
