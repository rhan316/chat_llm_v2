package org.dar316.spring_ai.service;

import org.dar316.spring_ai.dto.chatSum.ChatResponse;
import org.dar316.spring_ai.dto.chatSum.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class AiQuerySummarizer {
    private static final Logger log = LoggerFactory.getLogger(AiQuerySummarizer.class);
    private final RestClient aiRestClient;
    private final String summaryModel;

    public AiQuerySummarizer(
            RestClient aiRestClient,
            @Value("${ai.summary.model}") String summaryModel
    ) {
        this.aiRestClient = aiRestClient;
        this.summaryModel = summaryModel;
    }

    public String summarizeQueryForWiki(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            log.warn("rawQuery is null or empty");
            return rawQuery;
        }

        String prompt = "Extract the main technical topic or entity from the following text. " +
                "Return ONLY a short, 2-5 word English noun phrase suitable for a Wikipedia search. " +
                "No explanations, no sentences. Example: 'Spring AI', 'Vector Database', 'Java Garbage Collection'.\n\n" +
                "TEXT:\n" + rawQuery;

        return summarize(rawQuery, prompt, "Wiki");
    }

    /**
     * W przeciwieństwie do summarizeQueryForWiki, tutaj celem NIE jest rzeczownikowa
     * fraza encyklopedyczna, tylko krótki zestaw słów kluczowych pasujący do sposobu,
     * w jaki ludzie faktycznie formułują tytuły pytań na StackOverflow: nazwa
     * wyjątku/klasy/metody + 2-4 słowa kontekstu, bez pełnych zdań.
     */
    public String summarizeQueryForStackOverflow(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            log.warn("rawQuery is null or empty");
            return rawQuery;
        }

        String prompt = "Extract the key technical keywords from the following text, suitable for a " +
                "Stack Overflow full-text search (which works better with short keyword sets than full " +
                "sentences). Return ONLY 3-8 English words. " +
                "ALWAYS preserve exact class, exception, method, and library names verbatim " +
                "(e.g. 'InvalidDefinitionException', 'NullPointerException', 'RestClient'). " +
                "Drop filler words like 'why', 'does', 'when', 'how', 'the', 'a'. " +
                "No explanations, no sentences, no question marks. " +
                "Example: 'Jackson InvalidDefinitionException no Creators record', " +
                "'ConcurrentModificationException removing ArrayList iterating', " +
                "'Spring RestClient 401 Basic Auth'.\n\n" +
                "TEXT:\n" + rawQuery;

        return summarize(rawQuery, prompt, "StackOverflow");
    }

    private String summarize(String rawQuery, String prompt, String targetLabel) {
        try {
            ChatResponse response = aiRestClient.post()
                    .uri("/chat/completions")
                    .body(Map.of(
                            "model", summaryModel,
                            "messages", List.of(Map.of(
                                    "role", "user",
                                    "content", prompt)),
                            "temperature", 0.0
                    ))
                    .retrieve()
                    .body(ChatResponse.class);

            if (response != null && response.choices() != null && !response.choices().isEmpty()) {
                Message msg = response.choices().getFirst().message();
                if (msg != null && msg.content() != null) {
                    String summarized = msg.content().trim();
                    String snippet = rawQuery.length() > 20 ? rawQuery.substring(0, 20) : rawQuery;
                    log.info("Query summarized for {}: [{}] -> [{}]", targetLabel, snippet, summarized);

                    return summarized;
                }
            }
            log.warn("No valid message content found in AI response for query: [{}]", rawQuery.substring(0, Math.min(50, rawQuery.length())));
        } catch (Exception e) {
            log.error("Error during summarizing query for {} search. Query: {}",
                    targetLabel, rawQuery.length() > 50 ? rawQuery.substring(0, 50) + "..." : rawQuery, e);
        }

        return rawQuery;
    }
}
