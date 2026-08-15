package org.dar316.spring_ai.service.chat;

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

    public ChatService(
            ChatClient.Builder chatClientBuilder,
            ChatHistoryService chatHistoryService,
            RerankedRagService rerankedRagService, @Value("${ai.summary.model}") String cheapChatModel
            ) {
        this.chatClient = chatClientBuilder.build();
        this.chatHistoryService = chatHistoryService;
        this.rerankedRagService = rerankedRagService;
        this.cheapChatModel = cheapChatModel;
    }

    public String chat(String conversationId, String userQuery) {
        // 1. Retrieve only semantically relevant past messages (e.g., top 5)
        List<Document> recentHistory = chatHistoryService.getRelevantHistory(
                conversationId,
                userQuery,
                4
        );

        // 2. Rewrite the query if it contains pronouns or references to past messages
        String standaloneQuery = rewriteQuery(recentHistory, userQuery);

        /*
        3. Perform RAG using the REWRITTEN query
            This will hit Qdrant, or trigger the Wikipedia/StackOverflow fallback if needed
         */
        List<Document> ragContext = rerankedRagService.findAndRerankDocuments(
                standaloneQuery,
                "general", // or pass the specific technology
                "1.0",
                10,
                5
        );

        // 4. Format the context for the final prompt
        String contextText = ragContext.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        // 5. Call the LLM to generate the final answer


        // 2. Format the retrieved history into a context string
        String historyContext = recentHistory.stream()
                .map(doc -> {
                    String role = (String) doc.getMetadata().get("role");
                    String text = doc.getText();

                    return role + ": " + text;
                })
                .collect(Collectors.joining("\n\n"));

        // 3. Build the prompt. If no relevant history, leave it blank.
        // 5. Call the LLM to generate the final answer
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
                .user(standaloneQuery) // use the rewritten query here too
                .options(OpenAiChatOptions.builder().model(cheapChatModel))
                .call()
                .content();

        // 5. Save the new interaction to Qdrant for future semantic retrieval
        if (response != null && !response.isBlank()) {
            chatHistoryService.saveMessage(conversationId, "user", userQuery);
            chatHistoryService.saveMessage(conversationId, "assistant", response);
        }

        return response;
    }

    public Flux<String> streamChat(String conversationId, String userQuery) {
        List<Document> recentHistory = chatHistoryService.getRelevantHistory(
                conversationId,
                userQuery,
                4
        );

        String standaloneQuery = rewriteQuery(recentHistory, userQuery);

        List<Document> ragContextDocs = rerankedRagService.findAndRerankDocuments(
                standaloneQuery,
                "general",
                "1.0",
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
}
