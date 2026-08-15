package org.dar316.spring_ai.service.github;

import org.dar316.spring_ai.config.GitHubProperties;
import org.dar316.spring_ai.dto.github.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);
    private static final MediaType GITHUB_ACCEPT = MediaType.parseMediaType("application/vnd.github.v3+json");
    private static final String GITHUB_API_VERSION = "2022-11-28";
    private final RestClient restClient;
    private final GitHubProperties properties;

    public GitHubClient(GitHubProperties properties) {
        this.properties = Objects.requireNonNull(
                properties,
                "properties must not be null"
        );

        String baseUrl = requireNonBlank(
                properties.getApi().getBaseUrl()
        );

        /*
         * Do not inject a shared RestClient bean here.
         * It may be configured with the OpenRouter base URL.
         */
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();

        log.info(
                "GitHub API client configured with base URL: {}",
                baseUrl
        );
    }

    public List<GitHubDiscussionResponse> getDiscussions(String owner, String repo) {
        // Fetching discussions requires pagination. For simplicity, we grab the first 30.
        // In a production system, you'd loop through pages until exhausted or max-discussions in hit.
        try {
            return execute(
                    () -> restClient.get()
                            .uri(
                                    "/repos/{owner}/{repo}/discussions?per_page=30",
                                    owner,
                                    repo
                            )
                            .headers(this::applyHeaders)
                            .retrieve()
                            .body(
                                    new org.springframework.core.ParameterizedTypeReference<>() {
                                    }),
                    "discussions"
            );
        } catch (ResponseStatusException e) {
            // GitHub returns 404/422 if discussions are disabled for the repo.
            // We treat this as "no discussions" rather than a hard failure.
            if (e.getStatusCode() == HttpStatus.NOT_FOUND
                    || e.getStatusCode() == HttpStatus.UNPROCESSABLE_CONTENT) {
                log.info("Discussions not available for {}/{}", owner, repo);
                return List.of();
            }
            throw e;
        }
    }

    private String requireNonBlank(
            String value
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "github.api.base-url" + " must not be null or blank"
            );
        }

        return value.strip();
    }

    public ResolvedRevision resolveRevision(String owner, String repository, String ref) {
        var response = execute(
                () -> restClient.get()
                        .uri(
                                "/repos/{owner}/{repository}/commits/{ref}",
                                owner,
                                repository,
                                ref
                        )
                        .headers(this::applyHeaders)
                        .retrieve()
                        .body(GitHubCommitResponse.class),
                "commit"
        );

        if (response.sha() == null
                || response.sha().isBlank()
                || response.commit() == null
                || response.commit().tree() == null
                || response.commit().tree().sha() == null
                || response.commit().tree().sha().isBlank()) {
            throw upstreamInvalidResponse(
                    "GitHub returned an invalid commit response"
            );
        }

        return new ResolvedRevision(
                response.sha().strip(),
                response.commit().tree().sha().strip()
        );
    }

    public List<GitHubTreeEntry> getRecursiveTree(String owner, String repository, String treeSha) {
        var response = execute(
                () -> restClient.get()
                        .uri(
                                "/repos/{owner}/{repository}/git/trees/{treeSha}?recursive=1",
                                owner,
                                repository,
                                treeSha
                        )
                        .headers(this::applyHeaders)
                        .retrieve()
                        .body(GitHubTreeResponse.class),
                "repository tree"
        );

        if (response.truncated()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "GitHub truncated the repository tree. "
                            + "Increase the indexing limits only after reviewing repository size."
            );
        }

        if (response.tree() == null) {
            throw upstreamInvalidResponse(
                    "GitHub returned an invalid repository tree response"
            );
        }

        return List.copyOf(response.tree());
    }

    public Optional<TextBlob> getUtf8TextBlob(String owner, String repository, String blobSha) {
        var response = execute(
                () -> restClient.get()
                        .uri(
                                "/repos/{owner}/{repository}/git/blobs/{blobSha}",
                                owner,
                                repository,
                                blobSha
                        )
                        .headers(this::applyHeaders)
                        .retrieve()
                        .body(GitHubBlobResponse.class),
                "blob"
        );

        if (Objects.isNull(response.content())
                || response.content().isBlank()
                || Objects.isNull(response.encoding())
                || !response.encoding().equalsIgnoreCase("base64")
        ) {
            return Optional.empty();
        }

        byte[] bytes;

        try {
            bytes = Base64.getMimeDecoder().decode(response.content());
        }  catch (IllegalArgumentException e) {
            throw upstreamInvalidResponse("GitHub returned invalid Base64 encoded response");
        }

        if (bytes.length > properties.getIndex().getMaxFileSizeBytes()) {
            return Optional.empty();
        }

        for (byte b : bytes) {
            if (b == 0) return Optional.empty();
        }

        try {
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();

            return Optional.of(new TextBlob(text, bytes.length));
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    private void applyHeaders(HttpHeaders headers) {
        headers.setAccept(List.of(GITHUB_ACCEPT));
        headers.set(HttpHeaders.USER_AGENT, "Spring-AI-RAG");
        headers.set("X-GitHub-Api-Version", GITHUB_API_VERSION);

        String token = properties.getApi().getToken();

        if (Objects.nonNull(token) && !token.isBlank()) {
            headers.setBearerAuth(token.strip());
        }
    }

    private <T> T execute(
            Supplier<T> operation,
            String resourceName
    ) {
        try {
            T result = operation.get();

            if (result == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "GitHub returned an empty response for "
                                + resourceName
                );
            }

            return result;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();

            log.warn(
                    "GitHub API request for {} failed with HTTP {}",
                    resourceName,
                    status
            );

            if (status == HttpStatus.NOT_FOUND.value()) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "GitHub repository, ref, or requested object was not found"
                );
            }

            if (status == HttpStatus.UNAUTHORIZED.value()
                    || status == HttpStatus.FORBIDDEN.value()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "GitHub denied the request or its rate limit was exhausted"
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "GitHub API request failed with HTTP " + status
            );
        } catch (RestClientException exception) {
            log.warn(
                    "GitHub API is unavailable while requesting {}",
                    resourceName,
                    exception
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "GitHub API is unavailable"
            );
        }
    }

    private ResponseStatusException upstreamInvalidResponse(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

}
