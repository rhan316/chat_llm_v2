package org.dar316.spring_ai.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.dar316.spring_ai.dto.indexer.IndexDocumentationResponse;
import org.dar316.spring_ai.service.DocumentationIndexer;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Objects;

@RestController
@Validated
@RequestMapping("/api/doc")
public class DocumentationController {

    private final DocumentationIndexer documentationIndexer;

    public DocumentationController(
            DocumentationIndexer documentationIndexer
    ) {
        this.documentationIndexer = Objects.requireNonNull(
                documentationIndexer,
                "documentationIndexer must not be null"
        );
    }

    @PostMapping(
            value = "/index",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public IndexDocumentationResponse index(
            @RequestPart("file")
            @NotNull(message = "File is required")
            MultipartFile file,

            @RequestParam("technology")
            @NotBlank(message = "Technology cannot be blank")
            @Size(
                    max = 100,
                    message = "Technology cannot exceed 100 characters"
            )
            String technology,

            @RequestParam("technologyVersion")
            @NotBlank(message = "Technology version cannot be blank")
            @Size(
                    max = 100,
                    message = "Technology version cannot exceed 100 characters"
            )
            String technologyVersion
    ) {
        String filename = requireOriginalFilename(file);
        String normalizedTechnology = normalizeRequired(
                technology,
                "technology"
        );
        String normalizedTechnologyVersion = normalizeRequired(
                technologyVersion,
                "technologyVersion"
        );

        int chunks = documentationIndexer.index(
                file,
                normalizedTechnology,
                normalizedTechnologyVersion
        );

        return new IndexDocumentationResponse(
                filename,
                normalizedTechnology,
                normalizedTechnologyVersion,
                chunks
        );
    }

    private String requireOriginalFilename(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                    "file must not be null or empty"
            );
        }

        String filename = file.getOriginalFilename();

        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException(
                    "filename must not be null or blank"
            );
        }

        /*
         * This value is used only as a display value in the response.
         * It must not be used as a filesystem path.
         */
        return filename.strip();
    }

    private String normalizeRequired(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be null or blank"
            );
        }

        return value.strip();
    }
}