package org.dar316.spring_ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DocSearchServiceTest {

    @Mock
    private VectorStore  vectorStore;
    private DocSearchService docSearchService;

    @BeforeEach
    public void setUp() {
        docSearchService = new DocSearchService(vectorStore);
    }

    @Test
    void whenValidInputs_thenReturnsDocumentsAndBuildsCorrectRequest() {
        // Arrange
        String question = "  How does it work?  ";
        String tech = "  Spring  ";
        String version = "  3.0  ";
        int topK = 5;
        double threshold = 0.8;

        List<Document> mockDocs = List.of(
                new Document("Doc 1"),
                new Document("Doc 2")
        );

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(mockDocs);

        // Act
        List<Document> results = docSearchService.search(
                question,
                tech,
                version,
                topK,
                threshold
        );

        // Assert
        assertEquals(2, results.size());

        ArgumentCaptor<SearchRequest> requestCaptor =
                ArgumentCaptor.forClass(SearchRequest.class);

        verify(vectorStore, times(1))
                .similaritySearch(requestCaptor.capture());

        SearchRequest capturedRequest = requestCaptor.getValue();

        assertEquals("How does it work?", capturedRequest.getQuery());
        assertEquals(topK, capturedRequest.getTopK());
        assertEquals(
                threshold,
                capturedRequest.getSimilarityThreshold()
        );
        assertNotNull(capturedRequest.getFilterExpression());
    }

}
