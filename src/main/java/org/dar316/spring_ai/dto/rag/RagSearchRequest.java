package org.dar316.spring_ai.dto.rag;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record RagSearchRequest(

        @NotBlank(message = "Query cannot be empty")
        @Size(
                max = 4000,
                message = "Query cannot exceed 4000 characters"
        )
        String query,

        @NotBlank(message = "Technology cannot be empty")
        @Size(
                max = 100,
                message = "Technology cannot exceed 100 characters"
        )
        String technology,

        @NotBlank(message = "Technology version cannot be empty")
        @Size(
                max = 100,
                message = "Technology version cannot exceed 100 characters"
        )
        String technologyVersion,

        @NotNull(message = "topKInitial is required")
        @Min(value = 1, message = "topKInitial must be at least 1")
        @Max(value = 30, message = "topKInitial cannot exceed 30")
        Integer topKInitial,

        @NotNull(message = "topKReranked is required")
        @Min(value = 1, message = "topKReranked must be at least 1")
        @Max(value = 10, message = "topKReranked cannot exceed 10")
        Integer topKReranked
) {

        @AssertTrue(
                message = "topKReranked cannot be greater than topKInitial"
        )
        @JsonIgnore
        public boolean isRerankLimitValid() {
                return topKReranked <= topKInitial;
        }
}