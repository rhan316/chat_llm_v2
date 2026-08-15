package org.dar316.spring_ai.service;


import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class DocSearchService {

    private final VectorStore vectorStore;

    public DocSearchService(VectorStore vectorStore) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore must not be null");
    }

    public List<Document> search(
            String question,
            String technology,
            String technologyVersion,
            int topK,
            double similarityThreshold
    ) {

        String normalizedQuestion = requireNonBlank(
                question,
                "question"
        );

        String normalizedTechnology = requireNonBlank(
                technology,
                "technology"
        );

        String normalizedTechnologyVersion = requireNonBlank(
                technologyVersion,
                "technologyVersion"
        );

        if (topK < 1) {
            throw new IllegalArgumentException(
                    "topK must be greater than zero"
            );
        }

        if (similarityThreshold < 0.0
                || similarityThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "similarityThreshold must be between 0.0 and 1.0"
            );
        }

        var feb = new FilterExpressionBuilder();

        var filterExpression = feb
                .and(
                        feb.eq(
                                "technology",
                                normalizedTechnology
                        ),
                        feb.eq(
                                "technology_version",
                                normalizedTechnologyVersion
                        )
                )
                .build();

        var request = SearchRequest.builder()
                .query(normalizedQuestion)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(filterExpression)
                .build();

        return vectorStore.similaritySearch(request);
    }

    private String requireNonBlank(String value, String filedName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(filedName + " is blank");
        }

        return value.strip();
    }
}
