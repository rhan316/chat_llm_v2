package org.dar316.spring_ai.service.chat;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatHistoryService {

    private final VectorStore  vectorStore;

    public ChatHistoryService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void saveMessage(String conversationId, String role, String text) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source_type", "chat_history");
        metadata.put("conversation_id", conversationId);
        metadata.put("role", role);
        metadata.put("timestamp", Instant.now().toEpochMilli());

        var doc = new Document(text, metadata);
        vectorStore.add(List.of(doc));
    }

    public List<Document> getRelevantHistory(String conversationId, String currentQuery, int topK) {
        var feb = new FilterExpressionBuilder();

        // Filter strictly by this conversation ID and our chat history source type
        var filter = feb.and(
                feb.eq("source_type", "chat_history"),
                feb.eq("conversation_id", conversationId)
        ).build();

        var req = SearchRequest.builder()
                .query(currentQuery)
                .topK(topK)
                .filterExpression(filter)
                .build();

        return vectorStore.similaritySearch(req);
    }
}
