package org.dar316.spring_ai.controller;

import jakarta.validation.Valid;
import org.dar316.spring_ai.dto.github.GitHubIndexResponse;
import org.dar316.spring_ai.dto.github.GithubIndexRequest;
import org.dar316.spring_ai.service.github.GitHubRepositoryIndexer;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/github")
public class GitHubController {

    private final GitHubRepositoryIndexer indexer;

    public GitHubController(GitHubRepositoryIndexer indexer) {
        this.indexer = Objects.requireNonNull(indexer,  "indexer must not be null");
    }

    @PostMapping(
            value = "/index",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public GitHubIndexResponse index(@Valid @RequestBody GithubIndexRequest request) {
        return indexer.index(request);
    }
}
