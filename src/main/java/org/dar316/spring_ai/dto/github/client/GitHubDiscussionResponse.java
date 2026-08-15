package org.dar316.spring_ai.dto.github.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubDiscussionResponse(
        int number,
        String title,
        String body,
        List<GitHubContent> comments,
        Integer answerCount,
        Reactions reactions
) {
    public record GitHubContent(String body) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reactions(int totalCount) {}
}
