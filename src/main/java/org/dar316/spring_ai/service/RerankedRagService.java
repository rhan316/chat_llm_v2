package org.dar316.spring_ai.service;


import io.qdrant.client.QdrantClient;
import org.dar316.spring_ai.dto.huggingface.HuggingFaceModel;
import org.dar316.spring_ai.dto.rag.wiki.WikiSummaryResponse;
import org.dar316.spring_ai.dto.rag.stackoverflow.StackOverflowAnswersResponse;
import org.dar316.spring_ai.dto.rag.stackoverflow.StackOverflowSearchResponse;
import org.dar316.spring_ai.dto.rerank.RerankResponse;
import org.dar316.spring_ai.dto.rerank.RerankRequest;
import org.dar316.spring_ai.service.github.GitHubRepositorySyncLock;
import org.dar316.spring_ai.util.HttpRequestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.dar316.spring_ai.dto.rag.wiki.WikiSearchResponse;
import org.springframework.http.HttpHeaders;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class RerankedRagService {

    private final static Logger log = LoggerFactory.getLogger(RerankedRagService.class);
    private static final Comparator<Document> BY_SCORE_DESC = Comparator.comparingDouble(
            (Document doc) -> doc.getScore() != null ? doc.getScore() : 0).reversed();

    private final AiQuerySummarizer querySummarizer;
    private final QdrantClient qdrantClient;
    private final EmbeddingModel embeddingModel;
    private final RestClient client;
    private final String rerankModel;
    private final String apiKey;
    private final RestClient wikiClient;
    private final RestClient stackOverflowClient;
    private final String stackOverflowApiKey;
    private final double fallbackMinRerankScore;

    private static final Pattern GITHUB_REPOSITORY_PATTERN =
            Pattern.compile(
                    "[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"
            );

    private final GitHubRepositorySyncLock gitHubRepositorySyncLock;

    public RerankedRagService(
            QdrantClient qdrantClient,
            EmbeddingModel embeddingModel,
            RestClient.Builder restClientBuilder,
            AiQuerySummarizer querySummarizer,
            GitHubRepositorySyncLock gitHubRepositorySyncLock,

            @Value("${rag.chat.min-rerank-score:${CHAT_CODING_RAG_MIN_RERANK_SCORE:0.50}}")
            double fallbackMinRerankScore,

            @Value("${mentor.rerank.openrouter.base-url}")
            String baseUrl,

            @Value("${mentor.rerank.openrouter.api-key}")
            String apiKey,

            @Value("${mentor.rerank.openrouter.model}")
            String rerankModel,

            @Value("${stackoverflow.api.base-url:https://api.stackexchange.com}")
            String stackOverflowBaseUrl,

            @Value("${stackoverflow.api.key:}")
            String stackOverflowApiKey,

            @Value("${wiki.api.base-url:https://en.wikipedia.org}")
            String wikiBaseUrl

        ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenRouter API key is required");
        }

        this.querySummarizer = querySummarizer;
        this.gitHubRepositorySyncLock = Objects.requireNonNull(gitHubRepositorySyncLock,  "gitHubRepositorySyncLock");
        this.qdrantClient = Objects.requireNonNull(qdrantClient, "qdrantClient");
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.apiKey = apiKey;
        this.rerankModel = rerankModel;
        this.fallbackMinRerankScore = fallbackMinRerankScore;
        this.stackOverflowApiKey = stackOverflowApiKey;
        this.client = restClientBuilder.clone()
                .baseUrl(baseUrl)
                .build();
        this.wikiClient = restClientBuilder.clone()
                .baseUrl(wikiBaseUrl)
                .requestFactory(HttpRequestUtils.wikiTimeout(15))
                .defaultHeader(HttpHeaders.USER_AGENT,
                        "Spring_AI_Chat_Rag/1.0 bluemike351@gmail.com"
                        )
                .build();
        this.stackOverflowClient = restClientBuilder.clone()
                .baseUrl(stackOverflowBaseUrl)
                .requestFactory(HttpRequestUtils.wikiTimeout(15))
                .requestInterceptor(new GzipDecompressingInterceptor())
                .defaultHeader(HttpHeaders.USER_AGENT,
                        "Spring_AI_Chat_Rag/1.0 bluemike351@gmail.com"
                        )
                .build();
    }

    public List<Document> findAndRerankDocuments(
            String query,
            String technology,
            String technologyVersion,
            int topKInitial,
            int topKReranked
    ) {
        return findAndRerankDocuments(
                query,
                technology,
                technologyVersion,
                topKInitial,
                topKReranked,
                null,
                null
        );
    }

    public List<Document> findAndRerankDocuments(
            String query,
            String technology,
            String technologyVersion,
            int topKInitial,
            int topKReranked,
            String repository,
            String repositoryRef
    ) {
        String normalizedQuery = requireNonBlank(
                query,
                "query"
        );

        String normalizedTechnology = requireNonBlank(
                technology,
                "technology"
        );

        String normalizedTechnologyVersion = requireNonBlank(
                technologyVersion,
                "technologyVersion"
        );

        if (topKInitial < 1 || topKReranked < 1) {
            throw new IllegalArgumentException(
                    "topK values must be greater than zero"
            );
        }

        RepositoryScope repositoryScope =
                normalizeRepositoryScope(
                        repository,
                        repositoryRef
                );

        if (repositoryScope.isScoped()) {
            return gitHubRepositorySyncLock.withReadLock(
                    repositoryScope.repository(),
                    repositoryScope.repositoryRef(),
                    () -> findAndRerankDocumentsLocked(
                            normalizedQuery,
                            normalizedTechnology,
                            normalizedTechnologyVersion,
                            topKInitial,
                            topKReranked,
                            repositoryScope
                    )
            );
        }

        return findAndRerankDocumentsLocked(
                normalizedQuery,
                normalizedTechnology,
                normalizedTechnologyVersion,
                topKInitial,
                topKReranked,
                repositoryScope
        );
    }

    private VectorStore resolveVectorStore(String technology, String technologyVersion) {
        String collectionName = (technology + "_" + technologyVersion)
                .replaceAll("[^a-zA-Z0-9_\\-]", "_")
                .toLowerCase();

        QdrantVectorStore dynamicVectorStore = QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName(collectionName)
                .initializeSchema(false)   // kolekcja powinna już istnieć z etapu indeksowania
                .build();

        try {
            dynamicVectorStore.afterPropertiesSet();
        } catch (Exception e) {
            log.error("Nie udało się zainicjalizować kolekcji do wyszukiwania: {}", collectionName, e);
            throw new IllegalStateException("Vector store initialization failed for collection: " + collectionName, e);
        }

        return dynamicVectorStore;
    }

    private List<Document> findAndRerankDocumentsLocked(
            String query,
            String technology,
            String technologyVersion,
            int topKInitial,
            int topKReranked,
            RepositoryScope repositoryScope
    ) {
        VectorStore scopedVectorStore = resolveVectorStore(
                technology, technologyVersion
        );

        SearchRequest searchRequest = buildSearchRequest(
                query,
                technology,
                technologyVersion,
                topKInitial,
                repositoryScope
        );

        List<Document> candidates =
                scopedVectorStore.similaritySearch(searchRequest);

        if (candidates.isEmpty()) {
            /*
             * A repository-scoped request must not silently return generic
             * Wikipedia or StackOverflow data instead of repository code.
             */
            if (repositoryScope.isScoped()) {
                log.info(
                        "No GitHub RAG documents found for repository '{}' and ref '{}'",
                        repositoryScope.repository(),
                        repositoryScope.repositoryRef()
                );

                return List.of();
            }

            List<Document> fallbackCandidates = fetchFallbackCandidates(query);

            if (fallbackCandidates.isEmpty()) {
                log.info(
                        "No documents found in Qdrant and no external fallback results for query: {}",
                        query
                );

                return List.of();
            }

            List<Document> rerankedFallback =
                    tryRerankCandidates(
                            query,
                            fallbackCandidates,
                            topKReranked
                    );

            if (rerankedFallback.isEmpty()) {
                log.warn(
                        "External fallback sources returned results but reranking failed for query: {}",
                        query
                );

                return List.of();
            }

            return rerankedFallback.stream()
                    .sorted(BY_SCORE_DESC)
                    .limit(topKReranked)
                    .toList();
        }

        /*
            First pass: score the Qdrant candidates. The best score decides
            whether external fallback sources are needed at all.
         */
        int requestedTopN = Math.min(
                topKReranked,
                candidates.size()
        );

        List<Document> rerankedDocuments = rerankCandidates(
                query,
                candidates,
                requestedTopN
        );

        double bestScore = rerankedDocuments.stream()
                .mapToDouble(
                        document -> document.getScore() != null
                                ? document.getScore()
                                : 0.0
                )
                .max()
                .orElse(0.0);

        /*
            Repository-scoped searches are intentionally limited to the repository.
            Generic fallback sources would weaken source isolation.
         */
        if (repositoryScope.isScoped() || bestScore >= fallbackMinRerankScore) {
            return List.copyOf(rerankedDocuments);
        }

        List<Document> fallbackCandidates = fetchFallbackCandidates(query);

        if  (fallbackCandidates.isEmpty()) {
            return List.copyOf(rerankedDocuments);
        }

        List<Document> combined = new ArrayList<>(candidates.size() + fallbackCandidates.size());

        combined.addAll(candidates);
        combined.addAll(fallbackCandidates);

        List<Document> rerankedCombined = tryRerankCandidates(
                query,
                combined,
                topKReranked
        );

        if (rerankedCombined.isEmpty()) {
            log.warn("Combined rerank failed; returning Qdrant-only ranking for query: {}", query);
        }

        return rerankedCombined.stream()
                .sorted(BY_SCORE_DESC)
                .limit(topKReranked)
                .toList();
    }

    private SearchRequest buildSearchRequest(
            String query,
            String technology,
            String technologyVersion,
            int topK,
            RepositoryScope repositoryScope
    ) {
        var feb = new FilterExpressionBuilder();

        var technologyFilter = feb.and(
                feb.eq("technology", technology),
                feb.eq("technology_version", technologyVersion)
        );

        if (!repositoryScope.isScoped()) {
            return SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .filterExpression(
                            technologyFilter.build()
                    )
                    .build();
        }

        var repositoryFilter = feb.and(
                feb.eq("source_type", "github"),
                feb.and(
                        feb.eq(
                                "repository",
                                repositoryScope.repository()
                        ),
                        feb.eq(
                                "repository_ref",
                                repositoryScope.repositoryRef()
                        )
                )
        );

        return SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(
                        feb.and(
                                technologyFilter,
                                repositoryFilter
                        ).build()
                )
                .build();
    }

    private RepositoryScope normalizeRepositoryScope(
            String repository,
            String repositoryRef
    ) {
        String normalizedRepository = normalizeOptional(
                repository
        );

        String normalizedRef = normalizeOptional(
                repositoryRef
        );

        if ((normalizedRepository == null)
                != (normalizedRef == null)) {
            throw new IllegalArgumentException(
                    "repository and repositoryRef must be provided together"
            );
        }

        if (normalizedRepository == null) {
            return RepositoryScope.unscoped();
        }

        if (!GITHUB_REPOSITORY_PATTERN
                .matcher(normalizedRepository)
                .matches()) {
            throw new IllegalArgumentException(
                    "repository must have the format owner/repository"
            );
        }

        if (normalizedRef.length() > 255
                || normalizedRef.startsWith("/")
                || normalizedRef.endsWith("/")
                || normalizedRef.contains("..")
                || normalizedRef.contains("@{")
                || normalizedRef.chars().anyMatch(
                Character::isISOControl
        )) {
            throw new IllegalArgumentException(
                    "repositoryRef has an invalid Git ref format"
            );
        }

        return new RepositoryScope(
                normalizedRepository,
                normalizedRef
        );
    }

    private String normalizeOptional(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.strip();
    }

    private String requireNonBlank(
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

    private record RepositoryScope(
            String repository,
            String repositoryRef
    ) {
        private static RepositoryScope unscoped() {
            return new RepositoryScope(null, null);
        }

        private boolean isScoped() {
            return repository != null;
        }
    }


    /**
     * Pobiera dokumenty z zewnętrznych źródeł fallback (Wikipedia, StackOverflow).
     * Każde źródło jest niezależne od pozostałych - błąd jednego nie blokuje drugiego.
     */
    private List<Document> fetchFallbackCandidates(String query) {
        List<Document> fallback = new ArrayList<>();

        String wikiQuery = querySummarizer.summarizeQueryForWiki(query);
        Document wikiDocs = fetchWikiSummary(wikiQuery);
        if (wikiDocs != null) {
            fallback.add(wikiDocs);
        }

        String stackOverflowQuery = querySummarizer.summarizeQueryForStackOverflow(query);
        Document stackOverflowDocs = fetchStackOverflowAnswer(stackOverflowQuery);
        if (stackOverflowDocs != null) {
            fallback.add(stackOverflowDocs);
        }

        return fallback;
    }

    /**
     * Jak {@link #rerankCandidates}, ale nie rzuca wyjątku - błąd rerankingu
     * dokumentów fallback nie powinien wysadzać całego wyszukiwania.
     */
    private List<Document> tryRerankCandidates(String query, List<Document> candidates, int topN) {
        try {
            return rerankCandidates(query, candidates, topN);
        } catch (Exception e) {
            log.error("Reranking of fallback candidates failed for query: {}", query, e);
            return List.of();
        }
    }

    /**
     * Wysyła podane dokumenty do rerankera razem z zapytaniem i zwraca je
     * z realnym, policzonym przez model score (rerank_score) w metadanych.
     * Używane zarówno dla kandydatów z Qdrant, jak i dla dokumentów fallback
     * (Wikipedia / StackOverflow) - dzięki temu ich score jest porównywalny
     * i nie jest już wartością ustawioną na sztywno.
     */
    private List<Document> rerankCandidates(String query, List<Document> candidates, int topN) {
        List<String> documentTexts = candidates.stream()
                .map(Document::getText)
                .map(text -> text == null ? "" : text)
                .toList();

        var rerankRequest = new RerankRequest(
                rerankModel,
                query,
                documentTexts,
                Math.min(topN, candidates.size())
        );

        RerankResponse response;

        try {
            response = client.post()
                    .uri("/rerank")
                    .headers(hs -> {
                        hs.setBearerAuth(apiKey);
                        hs.setContentType(MediaType.APPLICATION_JSON);
                    })
                    .body(rerankRequest)
                    .retrieve()
                    .body(RerankResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Openrouter rerank failed. HTTP %s. Response: %s"
                            .formatted(e.getClass().getSimpleName(), e.getMessage()), e
            );
        }

        if (response == null || response.results() == null) {
            throw new IllegalStateException(
                    "Openrouter returned an empty rerank response."
            );
        }

        if (log.isDebugEnabled()) {
            response.results().forEach(result -> {
                log.debug(
                        "rerank result: index={} relevance_score={}",
                        result.index(),
                        result.relevanceScore()
                );
            });
        }

        List<Document> rerankedDocuments = new ArrayList<>(response.results().size());

        /*
        Reranker zwraca index dokumentu na wejściowej liście.
        Na jego podstawie pobieramy oryginalny Document z Qdrant / fallbacku.
         */
        for (var result : response.results()) {
            int candIdx = result.index();

            if (candIdx < 0 || candIdx >= documentTexts.size()) {
                continue;
            }

            var original = candidates.get(candIdx);
            Map<String, Object> metadata = new HashMap<>(original.getMetadata());

            if (original.getScore() != null) {
                metadata.put("vector_score", original.getScore());
            }

            metadata.put("rerank_score", result.relevanceScore());
            metadata.put("rerank_model", response.model());

            if (response.provider() != null) {
                metadata.put("rerank_provider", response.provider());
            }

            var rerankedDocument = original.mutate()
                    .metadata(metadata)
                    .score(result.relevanceScore())
                    .build();

            rerankedDocuments.add(rerankedDocument);
        }

        return rerankedDocuments;
    }

    private Document fetchWikiSummary(String topic) {
        try {
            if (topic == null || topic.isBlank()) {
                return null;
            }

            String resolvedTitle = searchWikiTitle(topic.trim());
            if (resolvedTitle == null) {
                log.debug("No Wikipedia article found for query: {}", topic);
                return null;
            }

            String uri = UriComponentsBuilder
                    .fromPath("/api/rest_v1/page/summary/{title}")
                    .buildAndExpand(resolvedTitle)
                    .toUriString();

            log.debug("Fetching Wikipedia summary for: {}", uri);

            WikiSummaryResponse wikiResponse = wikiClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(WikiSummaryResponse.class);

            if (wikiResponse == null || wikiResponse.extract() == null || wikiResponse.extract().isBlank()) {
                log.debug("Wikipedia returned empty extract for '{}'", resolvedTitle);
                return null;
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", "wikipedia");
            metadata.put("technology", "wikipedia");
            metadata.put("technology_version", "n/a");
            metadata.put("chunk_index", 0);
            metadata.put("wikipedia_title", resolvedTitle);

            Document doc = new Document(wikiResponse.extract(), metadata);

            log.debug("Successfully fetched Wikipedia summary for '{}'", resolvedTitle);
            return doc;

        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Wikipedia article not found for '{}'", topic);
            return null;
        } catch (Exception e) {
            log.error("Wikipedia API error for '{}': {}", topic, e.getMessage(), e);
            return null;
        }
    }

    private String searchWikiTitle(String query) {
        try {
            String uri = UriComponentsBuilder
                    .fromPath("/w/api.php")
                    .queryParam("action", "query")
                    .queryParam("list", "search")
                    .queryParam("srsearch", "{query}")
                    .queryParam("srlimit", 1)
                    .queryParam("format", "json")
                    .buildAndExpand(query)
                    .toUriString();

            WikiSearchResponse searchResponse = wikiClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(WikiSearchResponse.class);

            if (searchResponse == null
                    || searchResponse.query() == null
                    || searchResponse.query().search() == null
                    || searchResponse.query().search().isEmpty()) {
                return null;
            }

            String title = searchResponse
                    .query()
                    .search()
                    .getFirst()
                    .title();

            return (title == null || title.isBlank()) ? null : title;
        } catch (Exception e) {
            log.debug("Wikipedia search failed for '{}': {}", query, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Szuka na StackOverflow najtrafniejszego pytania dla zapytania, a następnie
     * pobiera jego najlepiej ocenioną odpowiedź (accepted lub najwyżej głosowaną).
     * Zwrócony dokument, tak jak dokument z Wikipedii, trafia potem do rerankera -
     * jego score NIE jest tu ustawiany na sztywno.
     */
    private Document fetchStackOverflowAnswer(String query) {
        try {
            if (query == null || query.isBlank()) {
                return null;
            }

            /*
            sort=votes (zamiast relevance) i pagesize=5: "q" i tak filtruje kandydatów
            po dopasowaniu tekstowym do zapytania, a sortowanie po głosach wśród tych
            dopasowań preferuje bardziej kanoniczne/dopracowane pytania zamiast
            pierwszego z brzegu tekstowego trafienia.
             */
            UriComponentsBuilder searchBuilder = UriComponentsBuilder
                    .fromPath("/2.3/search/advanced")
                    .queryParam("order", "desc")
                    .queryParam("sort", "votes")
                    .queryParam("answers", 1)
                    .queryParam("site", "stackoverflow")
                    .queryParam("pagesize", 5)
                    .queryParam("q", "{query}");

            if (stackOverflowApiKey != null && !stackOverflowApiKey.isBlank()) {
                searchBuilder.queryParam("key", stackOverflowApiKey);
            }

            String searchUri = searchBuilder.buildAndExpand(query.trim()).toUriString();

            log.debug("Fetching StackOverflow question for: {}", searchUri);

            StackOverflowSearchResponse searchResponse = stackOverflowClient.get()
                    .uri(searchUri)
                    .retrieve()
                    .body(StackOverflowSearchResponse.class);

            if (searchResponse == null || searchResponse.items() == null || searchResponse.items().isEmpty()) {
                log.debug("No StackOverflow question found for query: {}", query);
                return null;
            }

            /*
            Jawny wybór najlepszego pytania spośród kandydatów (a nie ślepe zaufanie
            kolejności zwróconej przez API): preferujemy najwyższy score, a przy remisie
            pytanie z większą liczbą odpowiedzi.
             */
            var question = searchResponse.items().stream()
                    .max(Comparator.comparingInt(StackOverflowSearchResponse.Item::score)
                            .thenComparingInt(StackOverflowSearchResponse.Item::answerCount))
                    .orElseThrow();

            UriComponentsBuilder answersBuilder = UriComponentsBuilder
                    .fromPath("/2.3/questions/{questionId}/answers")
                    .queryParam("order", "desc")
                    .queryParam("sort", "votes")
                    .queryParam("site", "stackoverflow")
                    .queryParam("filter", "withbody")
                    .queryParam("pagesize", 1);

            if (stackOverflowApiKey != null && !stackOverflowApiKey.isBlank()) {
                answersBuilder.queryParam("key", stackOverflowApiKey);
            }

            String answersUri = answersBuilder
                    .buildAndExpand(question.questionId())
                    .toUriString();

            StackOverflowAnswersResponse answersResponse = stackOverflowClient.get()
                    .uri(answersUri)
                    .retrieve()
                    .body(StackOverflowAnswersResponse.class);

            if (answersResponse == null || answersResponse.items() == null || answersResponse.items().isEmpty()) {
                log.debug("StackOverflow question '{}' has no answers", question.title());
                return null;
            }

            var topAnswer = answersResponse.items().getFirst();
            String answerText = stripHtml(topAnswer.body());

            if (answerText == null || answerText.isBlank()) {
                return null;
            }

            String content = question.title() + "\n\n" + answerText;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", "stackoverflow");
            metadata.put("technology", "stackoverflow");
            metadata.put("technology_version", "n/a");
            metadata.put("chunk_index", 0);
            metadata.put("stackoverflow_question_id", question.questionId());
            metadata.put("stackoverflow_link", question.link());
            metadata.put("stackoverflow_answer_score", topAnswer.score());
            metadata.put("stackoverflow_answer_accepted", topAnswer.isAccepted());

            log.debug("Successfully fetched StackOverflow answer for question '{}'", question.title());
            return new Document(content, metadata);

        } catch (HttpClientErrorException.NotFound e) {
            log.debug("StackOverflow question not found for '{}'", query);
            return null;
        } catch (Exception e) {
            log.error("StackOverflow API error for '{}': {}", query, e.getMessage(), e);
            return null;
        }
    }

    private String stripHtml(String html) {
        if (html == null) {
            return null;
        }

        String withoutTags = html.replaceAll("<[^>]+>", " ");
        String unescaped = HtmlUtils.htmlUnescape(withoutTags);

        return unescaped.replaceAll("\\s+", " ").trim();
    }
}
