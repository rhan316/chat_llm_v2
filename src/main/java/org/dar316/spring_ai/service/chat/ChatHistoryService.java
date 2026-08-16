package org.dar316.spring_ai.service.chat;

import io.qdrant.client.QdrantClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatHistoryService {

    private final VectorStore chatHistoryVectorStore;

    public ChatHistoryService(
            QdrantClient qdrantClient,
            EmbeddingModel embeddingModel,
            @Value("${rag.chat-history.collection-name:chat_history}")
            String chatHistoryCollectionName
    ) {
        QdrantVectorStore store = QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName(chatHistoryCollectionName)
                .initializeSchema(true)   // ta kolekcja może być tworzona automatycznie - nie ma ryzyka pomyłki nazwy
                .build();

        try {
            store.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("Nie udało się zainicjalizować kolekcji chat_history", e);
        }

        this.chatHistoryVectorStore = store;
    }

    public void saveMessage(String conversationId, String role, String text) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source_type", "chat_history");
        metadata.put("conversation_id", conversationId);
        metadata.put("role", role);
        metadata.put("timestamp", Instant.now().toEpochMilli());

        var doc = new Document(text, metadata);
        chatHistoryVectorStore.add(List.of(doc));
    }

    public List<Document> getRelevantHistory(String conversationId, String currentQuery, int topK) {
        var feb = new FilterExpressionBuilder();

        var filter = feb.and(
                feb.eq("source_type", "chat_history"),
                feb.eq("conversation_id", conversationId)
        ).build();

        var req = SearchRequest.builder()
                .query(currentQuery)
                .topK(topK)
                .filterExpression(filter)
                .build();

        return chatHistoryVectorStore.similaritySearch(req);
    }
}
