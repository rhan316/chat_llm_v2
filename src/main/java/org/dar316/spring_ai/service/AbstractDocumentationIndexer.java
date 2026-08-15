package org.dar316.spring_ai.service;

import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public abstract class AbstractDocumentationIndexer {

    protected static final Pattern SECTION_SEPARATOR =
            Pattern.compile("(?m)^\\h*\\+\\+\\+RAG_SECTION\\+\\+\\+\\h*$");

    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of("txt", "md", "markdown");

    protected final VectorStore vectorStore;
    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    protected AbstractDocumentationIndexer(VectorStore vectorStore) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore must not be null");
    }

    public int index(
            MultipartFile file,
            String technology,
            String technologyVersion
    ) {
        validate(file, technology, technologyVersion);

        String originalFilename = Objects.requireNonNull(
                file.getOriginalFilename(), "Filename must not be null");

        /*
         * MultipartFile filename may contain path fragments.
         * Keep only the filename part for metadata and filtering.
         */
        String source = sanitizeFilename(originalFilename);

        return indexResource(
                file.getResource(),
                source,
                technology,
                technologyVersion
        );
    }

    /*
     * Template Method.

     * This method defines the common indexing workflow.
     * Subclasses provide only the document-reading implementation.
     */
    public final int indexResource(
            Resource resource,
            String source,
            String technology,
            String technologyVersion
    ) {
        Objects.requireNonNull(resource, "resource must not be null");
        String safeSource = sanitizeFilename(source);
        String normalizedTechnology = requireNonBlank(technology, "technology");
        String normalizedTechnologyVersion = requireNonBlank(technologyVersion, "technologyVersion");

        String documentVersion = UUID.randomUUID().toString();
        String indexedAt = Instant.now().toString();

        /*
         * Abstract step. The subclass decides how Markdown or TXT
         * content is converted into Spring AI Documents.
         */
        List<Document> sourceDocuments = Objects.requireNonNull(
                readDocument(
                        resource,
                        safeSource,
                        normalizedTechnology,
                        normalizedTechnologyVersion
                ),
                "readDocument must not return null"
        );

        if (sourceDocuments.isEmpty()) {
            throw new IllegalStateException("No document found for " + safeSource);
        }

        /*
         * A single source Document can contain multiple explicit
         * RAG sections separated by +++RAG_SECTION+++.
         */
        List<Document> sectionDocuments =
                splitBySectionSeparator(sourceDocuments);

        if (sectionDocuments.isEmpty()) {
            throw new IllegalStateException("No non-empty RAG sections were found in source: " + safeSource);
        }

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(600)
                .withMinChunkSizeChars(250)
                .withMinChunkLengthToEmbed(50)
                .withMaxNumChunks(20_000)
                .withKeepSeparator(true)
                .build();

        List<Document> chunks = splitter.apply(sectionDocuments);
        List<Document> enrichedChunks = new ArrayList<>(chunks.size());

        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            Document chunk = Objects.requireNonNull(chunks.get(chunkIndex), "chunk must not be null");

            Map<String, Object> metadata =
                    new HashMap<>(chunk.getMetadata());

            metadata.put("source", safeSource);
            metadata.put("technology", normalizedTechnology);
            metadata.put("technology_version", normalizedTechnologyVersion);
            metadata.put("document_version", documentVersion);
            metadata.put("chunk_index", chunkIndex);
            metadata.put("chunk_count", chunks.size());
            metadata.put("indexed_at", indexedAt);

            enrichedChunks.add(
                    new Document(
                            chunk.getText(),
                            metadata
                    )
            );
        }

        /*
         * Delete old chunks only after reading and processing the new
         * document has completed successfully.
         */
        store(
                enrichedChunks,
                safeSource,
                normalizedTechnology,
                normalizedTechnologyVersion,
                documentVersion
        );

        log.debug(
                "Indexed {} chunks for source '{}' and technology '{}'",
                enrichedChunks.size(),
                safeSource,
                technology
        );

        return enrichedChunks.size();
    }

    /*
     * Abstract step - must be implemented by a subclass.
     */
    protected abstract List<Document> readDocument(
            Resource resource,
            String source,
            String technology,
            String technologyVersion
    );

    protected void store(
            List<Document> enrichedChunks,
            String source,
            String technology,
            String technologyVersion,
            String documentVersion
    ) {
        Objects.requireNonNull(
                enrichedChunks,
                "enrichedChunks must not be null"
        );

        if (enrichedChunks.isEmpty()) {
            throw new IllegalArgumentException("enrichedChunks must not be empty");
        }

        String safeSource = sanitizeFilename(source);

        String normalizedTechnology = requireNonBlank(technology, "technology");
        String normalizedTechnologyVersion = requireNonBlank(technologyVersion, "technologyVersion");
        String normalizedDocumentVersion = requireNonBlank(documentVersion, "documentVersion");

        vectorStore.add(enrichedChunks);

        try {
            var feb = new FilterExpressionBuilder();

            vectorStore.delete(
                    feb.and(
                            feb.and(
                                    feb.eq("source", safeSource),
                                    feb.eq("technology", normalizedTechnology)
                            ),
                            feb.and(
                                    feb.eq("technology_version", normalizedTechnologyVersion),
                                    feb.ne("document_version", normalizedDocumentVersion)
                            )
                    ).build()
            );
        } catch (RuntimeException e) {
            log.error(
                    "New chunks were stored, but old chunks could not be removed for source `{}`",
                    safeSource,
                    e
            );
            throw e;
        }

    }

    // HELPERS

    protected void validate(
            MultipartFile file,
            String technology,
            String technologyVersion
    ) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file must not be null or empty");
        }

        requireNonBlank(technology, "technology");
        requireNonBlank(technologyVersion, "technologyVersion");

        String filename = file.getOriginalFilename();

        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException(
                    "filename must not be null or blank"
            );
        }

        String safeFilename = sanitizeFilename(filename);
        String extension = getExtension(safeFilename);

        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "Supported file types: txt, md, markdown"
            );
        }
    }

    protected List<Document> splitBySectionSeparator(List<Document> documents) {
        Objects.requireNonNull(documents, "documents must not be null");

        List<Document> sections = new ArrayList<>();

        for (int documentIndex = 0; documentIndex < documents.size(); documentIndex++) {

            Document document = Objects.requireNonNull(
                    documents.get(documentIndex),
                    "documents must not contain null values"
            );

            String text = Objects.requireNonNull(
                    document.getText(),
                    "document text must not be null"
            );

            List<String> sectionTexts = SECTION_SEPARATOR
                    .splitAsStream(text)
                    .map(String::strip)
                    .filter(section -> !section.isBlank())
                    .toList();

            for (int sectionIndex = 0; sectionIndex < sectionTexts.size(); sectionIndex++) {

                Map<String, Object> metadata =
                        new HashMap<>(document.getMetadata());

                metadata.put(
                        "source_document_index",
                        documentIndex
                );
                metadata.put(
                        "section_index",
                        sectionIndex
                );
                metadata.put(
                        "section_count",
                        sectionTexts.size()
                );

                metadata.put(
                        "section_id",
                        documentIndex + ":" + sectionIndex
                );

                sections.add(
                        new Document(
                                sectionTexts.get(sectionIndex),
                                metadata
                        )
                );
            }
        }

        return sections;
    }

    protected String sanitizeFilename(String filename) {
        if (filename == null) {
            throw new IllegalArgumentException(
                    "filename must not be null"
            );
        }

        String normalized = filename.replace("\\", "/");
        int lastSlash = normalized.lastIndexOf('/');

        if (lastSlash >= 0) {
            normalized = normalized.substring(lastSlash + 1).strip();
        }

        if (normalized.isBlank()
                || normalized.equals(".")
                || normalized.equals("..")) {
            throw new IllegalArgumentException(
                    "invalid filename: " + filename
            );
        }

        return normalized;
    }

    protected String getExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }

        int separator = filename.lastIndexOf('.');

        if (separator < 0 || separator == filename.length() - 1) {
            return "";
        }

        return filename
                .substring(separator + 1)
                .toLowerCase(Locale.ROOT);
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }

        return value.strip();
    }
}