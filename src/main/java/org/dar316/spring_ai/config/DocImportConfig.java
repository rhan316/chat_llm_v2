package org.dar316.spring_ai.config;

import org.dar316.spring_ai.service.RerankedRagService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DocImportConfig {

/*
    @Bean
    CommandLineRunner importDocumentation(DocumentationIndexer indexer) {
        return args -> {
            int chunks = indexer.index(
                    Path.of("docs/junit.txt"),
                    "junit",
                    "6.1.2"
            );


            System.out.println("Saved chunks: " + chunks);
        };
    }

    @Bean
    CommandLineRunner testSearch(DocSearchService searchService) {
        return args -> {
            var results = searchService.search(
                    "How to create reusable container in testcontainers?"
            );

            for (var doc : results) {
                System.out.println("------------");
                System.out.println(doc.getText());
                System.out.println(doc.getMetadata());
                System.out.println("Score: "  + doc.getScore());
            }
        };
    }

    @Bean
    CommandLineRunner testReranking(RerankedRagService ragService) {
        return args -> {
            var results = ragService.findAndRerankDocuments(
                    "How do JUnit tags work?",
                    12,
                    5
            );

            for (var doc : results) {
                System.out.println("------------");

                System.out.println(
                        "Chunk: "
                                + doc.getMetadata()
                                .get("chunk_index")
                );

                System.out.println(
                        "Vector score: "
                                + doc.getMetadata()
                                .get("vector_score")
                );

                System.out.println(
                        "Rerank score: "
                                + doc.getScore()
                );

                System.out.println(doc.getText());
            }
        };
    }

*/
}
