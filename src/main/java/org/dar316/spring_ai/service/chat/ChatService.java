package org.dar316.spring_ai.service.chat;

import org.dar316.spring_ai.dto.chat.ChatRequest;
import org.dar316.spring_ai.service.RerankedRagService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ChatHistoryService chatHistoryService;
    private final RerankedRagService rerankedRagService;
    private final String cheapChatModel;
    private final String defaultTechnology;
    private final String defaultTechnologyVersion;

    public ChatService(
            ChatClient.Builder chatClientBuilder,
            ChatHistoryService chatHistoryService,
            RerankedRagService rerankedRagService,
            @Value("${ai.summary.model}") String cheapChatModel,
            @Value("${rag.chat.default-technology}") String defaultTechnology,
            @Value("${rag.chat.default-technology-version}") String defaultTechnologyVersion
            ) {
        this.chatClient = chatClientBuilder.build();
        this.chatHistoryService = chatHistoryService;
        this.rerankedRagService = rerankedRagService;
        this.cheapChatModel = cheapChatModel;
        this.defaultTechnology = defaultTechnology;
        this.defaultTechnologyVersion = defaultTechnologyVersion;
    }

    public String chat(ChatRequest request) {
        String conversationId = request.conversationId();
        String userQuery = request.query();

        // 1. Retrieve only semantically relevant past messages
        List<Document> recentHistory = chatHistoryService.getRelevantHistory(
                conversationId,
                userQuery,
                4
        );

        // 2. Rewrite follow-up questions into standalone questions
        String standaloneQuery = rewriteQuery(recentHistory, userQuery);

        // 3. RAG over the corpus selected by the request (or the configured default)
        Corpus corpus = resolveCorpus(request);

        List<Document> ragContext = rerankedRagService.findAndRerankDocuments(
                standaloneQuery,
                corpus.technology(),
                corpus.technologyVersion(),
                10,
                5
        );

        String contextText = ragContext.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String systemPrompt = """
                You are a helpful AI assistant for software engineers.
                Use the following retrieved context to answer the user's question.
                If the context is not relevant, ignore it and answer using your general knowledge.
                \s
                Retrieved Context:
                %s
            """.formatted(contextText.isBlank() ? "No relevant context found." : contextText);

        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(standaloneQuery)
                .options(OpenAiChatOptions.builder().model(cheapChatModel))
                .call()
                .content();

        if (response != null && !response.isBlank()) {
            chatHistoryService.saveMessage(conversationId, "user", userQuery);
            chatHistoryService.saveMessage(conversationId, "assistant", response);
        }

        return response;
    }

    public Flux<String> streamChat(ChatRequest request) {
        String conversationId = request.conversationId();
        String userQuery = request.query();

        List<Document> recentHistory = chatHistoryService.getRelevantHistory(
                conversationId,
                userQuery,
                4
        );

        String standaloneQuery = rewriteQuery(recentHistory, userQuery);

        Corpus corpus = resolveCorpus(request);

        List<Document> ragContextDocs = rerankedRagService.findAndRerankDocuments(
                standaloneQuery,
                corpus.technology(),
                corpus.technologyVersion(),
                10,
                5
        );

        String ragContext = ragContextDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String historyContext = recentHistory.stream()
                .map(doc -> {
                    var roleObj = doc.getMetadata().get("role");
                    String role = roleObj != null ? roleObj.toString() : "";

                    return role + ": " + doc.getText();
                })
                .collect(Collectors.joining("\n\n"));

        String systemPrompt = """
            You are a helpful AI assistant for software engineers.
            Use the following retrieved context to answer the user's question.
            If the context is not relevant, ignore it and answer using your general knowledge.

            Retrieved Context:
            %s

            Recent Chat History:
            %s
        """.formatted(
                ragContext.isBlank() ? "No relevant context found." : ragContext,
                historyContext.isBlank() ? "No previous history." : historyContext
        );

        var responseAcc = new StringBuilder();

        return chatClient.prompt()
                .system(systemPrompt)
                .user(standaloneQuery)
                .options(OpenAiChatOptions.builder().model(cheapChatModel))
                .stream()
                .content()
                .doOnNext(responseAcc::append)
                .doOnComplete(() -> {
                    String finalResponse = responseAcc.toString();
                    if (!finalResponse.isBlank()) {
                        chatHistoryService.saveMessage(conversationId, "user", userQuery);
                        chatHistoryService.saveMessage(conversationId, "assistant", finalResponse);
                    }
                })
                .doOnError(e -> {
                    System.err.println("Streaming error for conversation " + conversationId + ": " + e.getMessage());
                });
    }

    /*
    Uses the cheap LLM to rewrite a follow-up question into a standalone question.
    Example: "Can I use it with JOIN?" --> "Can I use SQL UNION with JOIN?"
     */
    private String rewriteQuery(List<Document> history, String userQuery) {
        if (history.isEmpty()) {
            return userQuery; // No history, no need to rewrite
        }

        String historyText = history.stream()
                .map(doc -> {
                    String role = (String) doc.getMetadata().get("role");
                    String text = doc.getText();
                    return role + ": " + text;
                })
                .collect(Collectors.joining("\n\n"));

        String rewritePrompt = """
                Given the following conversation history and a new follow-up question,
                rewrite the follow-up question as a standalone, self-contained question.
                Do not answer the question. Only rewrite it.
                
                Conversation History:
                %s
                
                Follow-up Question:
                %s
                
                Standalone Question:
                
                """
                .formatted(historyText, userQuery);

        try {
            String rewritten = chatClient.prompt()
                    .system(rewritePrompt)
                    .user(userQuery)
                    .options(OpenAiChatOptions.builder())
                    .call()
                    .content();

            // If the LLM fails to rewrite or returns empty,
            // fallback to the original query
            return (rewritten == null || rewritten.isBlank()) ? userQuery : rewritten;
        } catch (Exception e) {
            return userQuery; // Fail-safe
        }
    }

    private Corpus resolveCorpus(ChatRequest request) {
        String technology = isBlank(request.technology())
                ? defaultTechnology : request.technology().strip();
        String technologyVersion = isBlank(request.technologyVersion())
                ? defaultTechnologyVersion : request.technologyVersion().strip();

        return new Corpus(technology, technologyVersion);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
