package org.dar316.spring_ai.dto.indexer;

import jakarta.validation.constraints.NotBlank;

public record DocumentationIndexRequest(
        @NotBlank String technology,
        @NotBlank String technologyVersion
) {
}
