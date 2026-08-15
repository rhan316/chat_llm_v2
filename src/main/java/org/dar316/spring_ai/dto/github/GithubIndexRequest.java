package org.dar316.spring_ai.dto.github;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;


public record GithubIndexRequest(

        @NotBlank(message = "owner cannot be blank")
        @Pattern(
                regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,99}",
                message = "owner has an invalid format"
        )
        String owner,

        @NotBlank(message = "ref cannot be blank")
        @Size(max = 255, message = "ref cannot exceed 255 characters")
        String repository,

        @NotBlank(message = "ref cannot be blank")
        @Size(max = 255, message = "ref cannot exceed 255 characters")
        String ref,

        @NotBlank(message = "technology cannot be blank")
        @Size(max = 100, message = "technology cannot exceed 100 characters")
        String technology,

        @NotBlank(message = "technologyVersion cannot be blank")
        @Size(
                max = 100,
                message = "technologyVersion cannot exceed 100 characters"
        )
        String technologyVersion
) {
}
