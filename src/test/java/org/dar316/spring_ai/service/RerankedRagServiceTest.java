package org.dar316.spring_ai.service;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.dar316.spring_ai.service.github.GitHubRepositorySyncLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

public class RerankedRagServiceTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private VectorStore vectorStore;
    private AiQuerySummarizer querySummarizer;
    private RerankedRagService service;

    @BeforeEach
    public void setup() {
        vectorStore = mock(VectorStore.class);
        querySummarizer = mock(AiQuerySummarizer.class);
        var syncLock = mock(GitHubRepositorySyncLock.class);

        // Point all external clients to the WireMock server
        service = new RerankedRagService(
                vectorStore,
                RestClient.builder(), // Ignored by the service, but required by constructor
                querySummarizer,
                syncLock,
                0.60, // fallbackMinRerankScore
                wireMock.baseUrl(),   // OpenRouter base URL
                "dummy-key",
                "dummy-model",
                wireMock.baseUrl(),   // StackOverflow base URL
                "",
                wireMock.baseUrl()    // Wiki base URL
        );

        // Prevent actual LLM calls and control the search query
        when(querySummarizer.summarizeQueryForWiki(anyString()))
                .thenReturn("Spring AI");

        when(querySummarizer.summarizeQueryForStackOverflow(anyString()))
                .thenReturn("Spring AI");

        stubExternalApis();
    }

    @Test
    void whenQdrantScoreIsHigh_theShouldNotTriggerFallback() {
        // 1. Mock Qdrant returning a document
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("qdrant text", Map.of("source", "qdrant"))));

        // 2. Mock OpenRouter returning a high rerank score for the Qdrant doc
        stubOpenRouterRerank(0.95, 0.90, 0.85);

        List<Document> results = service.findAndRerankDocuments(
                "test query", "Spring AI", "4.0.7", 10, 5
        );

        // 3. Verify results and that external APIs were NOT called
        assertEquals(1, results.size());
        assertEquals(0.95, results.getFirst().getScore());

        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/w/api.php")));
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/2.3/search/advanced")));
    }

    @Test
    void whenQdrantScoreIsLow_thenShouldMergeFallbackResults() {
        // 1. Mock Qdrant returning a document
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("qdrant text", Map.of("source", "qdrant"))));

        // 2. Mock OpenRouter returning a low score for Qdrant (index 0),
        // but high scores for the fallback docs (indices 1 and 2)
        stubOpenRouterRerank(0.45, 0.95, 0.90);
        List<Document> results = service.findAndRerankDocuments(
                "test query", "Spring AI", "4.0.7", 10, 5
        );

        // 3. Verify the merged list is sorted by score descending
        assertEquals(3, results.size());
        assertEquals(0.95, results.getFirst().getScore()); // Fallback doc 1
        assertEquals(0.90, results.get(1).getScore()); // Fallback doc 2
        assertEquals(0.45, results.get(2).getScore()); // Qdrant doc

        // 4. Verify external fallback APIs were called
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/w/api.php")));
        wireMock.verify(1, getRequestedFor(urlPathMatching("/api/rest_v1/page/summary/.+")));
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/2.3/search/advanced")));

        // Explicitly match a numeric question ID
        wireMock.verify(1, getRequestedFor(urlPathMatching("/2.3/questions/\\d+/answers")));
    }

    // --- WireMock Stubs ---

    private void stubExternalApis() {
        // Wikipedia Search
        wireMock.stubFor(get(urlPathEqualTo("/w/api.php"))
                .willReturn(okJson("""
                    {"query":{"search":[{"title":"Spring AI"}]}}
                """)));

        // Wikipedia Summary
        wireMock.stubFor(get(urlPathMatching("/api/rest_v1/page/summary/.+"))
                .willReturn(okJson("""
                    {"title":"Spring AI","extract":"Spring AI is a framework."}
                """)));

        // StackOverflow Search
        wireMock.stubFor(get(urlPathEqualTo("/2.3/search/advanced"))
                .willReturn(okJson("""
                    {"items":[{"question_id":123,"title":"How to use Spring AI?","link":"http://stackoverflow.com/q/123","is_answered":true,"answer_count":1,"score":10}]}
                """)));

        // StackOverflow Answers - Changed from .+ to \d+
        wireMock.stubFor(get(urlPathMatching("/2.3/questions/\\d+/answers"))
                .willReturn(okJson("""
                    {"items":[{"answer_id":456,"is_accepted":true,"score":5,"body":"<p>Use RerankedRagService</p>"}]}
                """)));
    }

    /**
     * Stubs the OpenRouter /rerank endpoint to return different responses
     * based on the number of documents being reranked (identified by the top_n field).
     */
    private void stubOpenRouterRerank(double qdrantScore, double wikiScore, double soScore) {
        // 1. First call: Reranking Qdrant candidates (1 document, top_n = 1)
        String qdrantResponse = """
            {
                "id": "test-id",
                "model": "test-model",
                "provider": "test-provider",
                "results": [
                {"index": 0, "relevance_score": %.2f, "document": {"text": "irrelevant"}}
                ],
                "usage": {"search_units": 1, "total_tokens": 10}
            }
        """.formatted(qdrantScore);

        wireMock.stubFor(post("/rerank")
                .withRequestBody(matchingJsonPath("$.top_n", equalTo("1")))
                .willReturn(okJson(qdrantResponse)));

        // 2. Second call: Reranking fallback candidates (2 documents, top_n = 2)
        String fallbackResponse = """
            {
                "id": "test-id",
                "model": "test-model",
                "provider": "test-provider",
                "results": [
                {"index": 0, "relevance_score": %.2f, "document": {"text": "irrelevant"}},
                {"index": 1, "relevance_score": %.2f, "document": {"text": "irrelevant"}}
                ],
                "usage": {"search_units": 1, "total_tokens": 10}
            }
        """.formatted(wikiScore, soScore);

        wireMock.stubFor(post("/rerank")
                .withRequestBody(matchingJsonPath("$.top_n", equalTo("2")))
                .willReturn(okJson(fallbackResponse)));
    }
}
