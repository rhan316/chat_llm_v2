package org.dar316.spring_ai.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(

        @NotBlank(message = "conversationId cannot be blank")
        @Size(max = 100, message = "conversationId cannot exceed 100 characters")
        String conversationId,

        @NotBlank(message = "query cannot be blank")
        @Size(max = 4_000, message = "query cannot exceed 4_000 characters")
        String query,

        @Size(max = 100, message = "technology cannot exceed 100 characters")
        String technology,

        @Size(max = 100, message = "technologyVersion cannot exceed 100 characters")
        String technologyVersion
) {
}
