package org.dar316.spring_ai.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.dar316.spring_ai.dto.rag.RagHit;
import org.dar316.spring_ai.dto.rag.RagSearchRequest;
import org.dar316.spring_ai.dto.rag.RagSearchResponse;
import org.dar316.spring_ai.service.RerankedRagService;
import org.dar316.spring_ai.util.ControllerUtils;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RerankedRagService ragService;

    public RagController(RerankedRagService ragService) {
        this.ragService = Objects.requireNonNull(
                ragService,
                "ragService must not be null"
        );
    }

    @PostMapping(
            value = "/search",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public RagSearchResponse search(
            @Valid
            @RequestBody
            RagSearchRequest request,

            @RequestParam(
                    value = "repository",
                    required = false
            )
            @Pattern(
                    regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}",
                    message = "repository must have the format owner/repository"
            )
            String repository,

            @RequestParam(
                    value = "repositoryRef",
                    required = false
            )
            @Size(
                    max = 255,
                    message = "repositoryRef cannot exceed 255 characters"
            )
            String repositoryRef
    ) {
        final String query = request.query().strip();
        final String technology = request.technology().strip();
        final String technologyVersion = request.technologyVersion().strip();

        final List<Document> documents = ragService.findAndRerankDocuments(
                query,
                technology,
                technologyVersion,
                request.topKInitial(),
                request.topKReranked(),
                repository,
                repositoryRef
        );

        final List<RagHit> results = documents.stream()
                .map(ControllerUtils::toHit)
                .toList();

        return new RagSearchResponse(query, results);
    }
}