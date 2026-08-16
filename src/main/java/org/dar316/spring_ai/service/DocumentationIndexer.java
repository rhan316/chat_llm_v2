package org.dar316.spring_ai.service;

import io.qdrant.client.QdrantClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class DocumentationIndexer
        extends AbstractDocumentationIndexer {

    public DocumentationIndexer(VectorStore vectorStore, QdrantClient qdrantClient, EmbeddingModel embeddingModel) {
        super(vectorStore, qdrantClient, embeddingModel);
    }

    @Override
    protected List<Document> readDocument(
            Resource resource,
            String source,
            String technology,
            String technologyVersion
    ) {
        Objects.requireNonNull(resource, "resource must not be null");

        String safeFilename = sanitizeFilename(source);
        String extension = getExtension(safeFilename);

        Map<String, Object> metadata = new HashMap<>();

        metadata.put("source", safeFilename);
        metadata.put("technology", technology);
        metadata.put("technology_version", technologyVersion);
        metadata.put("document_type", extension);
        metadata.put("language", "en");

        return switch (extension) {
            case "md", "markdown" -> readMarkdown(
                    resource,
                    metadata
            );
            case "txt" -> readText(
                    resource,
                    metadata
            );
            default -> throw new IllegalArgumentException(
                    "Unsupported file type: " + extension
            );
        };
    }

    private List<Document> readMarkdown(
            Resource resource,
            Map<String, Object> metadata
    ) {
        MarkdownDocumentReaderConfig.Builder configBuilder =
                MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(true)
                        .withIncludeBlockquote(true);

        metadata.forEach(configBuilder::withAdditionalMetadata);

        MarkdownDocumentReader reader =
                new MarkdownDocumentReader(
                        resource,
                        configBuilder.build()
                );

        return reader.get();
    }

    private List<Document> readText(
            Resource resource,
            Map<String, Object> metadata
    ) {
        TextReader reader = new TextReader(resource);
        reader.getCustomMetadata().putAll(metadata);

        return reader.get();
    }
}