package org.dar316.spring_ai.service.chat;

import org.dar316.spring_ai.dto.chat.ChatRequest;
import org.dar316.spring_ai.service.RerankedRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ChatServiceTest {

    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;

    @Mock private ChatHistoryService chatHistoryService;
    @Mock private RerankedRagService rerankedRagService;

    private ChatService chatService;

    @BeforeEach
    public void setUp() {
        // Wire the fluent ChatClient mock chain
        when(chatClientBuilder.build())
                .thenReturn(chatClient);
        when(chatClient.prompt())
                .thenReturn(requestSpec);

        when(requestSpec.system(anyString()))
                .thenReturn(requestSpec);
        when(requestSpec.user(anyString()))
                .thenReturn(requestSpec);
        when(requestSpec.options(any()))
                .thenReturn(requestSpec);
        when(requestSpec.call())
                .thenReturn(callResponseSpec);

        chatService = new ChatService(
                chatClientBuilder,
                chatHistoryService,
                rerankedRagService,
                "test-cheap-model",
                "spring_ai",
                "2.0.0"
        );
    }

    @Test
    void whenFollowUpQuery_thenRewritesQueryAndPreventsSnowball() {
        String convId = "conv-123";
        String followQuery = "Can I use it with JOIN?";
        String expectedRewrittenQuery = "Can I use SQL UNION with JOIN?";
        String finalAnswer = "Yes, you can use UNION with JOIN in SQL.";

        // 1. Mock ChatHistoryService to simulate previous context
        var pastUser = new Document("What is UNION in SQL?", Map.of("role", "user"));
        var pastAssistant = new Document("UNION combines the result sets of two queries.",
                Map.of("role", "assistant"));

        when(chatHistoryService.getRelevantHistory(eq(convId), anyString(), anyInt()))
                .thenReturn(List.of(pastUser, pastAssistant));

        /*
        2. Mock the LLM to handle two sequential calls:
        - First call (rewriteQuery): returns the rewritten standalone query
        - Second call (final answer): returns the final answer
         */
        when(callResponseSpec.content())
                .thenReturn(expectedRewrittenQuery)
                .thenReturn(finalAnswer);

        // 3. Mock the RAG service to return some context (mock BOTH overloads)
        List<Document> fakeRagContext = List.of(new Document("RAG context about SQL UNION and JOIN."));

        // 5-argument version
        when(rerankedRagService.findAndRerankDocuments(
                anyString(), anyString(), anyString(), anyInt(), anyInt()
        ))
                .thenReturn(fakeRagContext);

        // --- ACT ---
        String result = chatService.chat(
                new ChatRequest(convId, followQuery, null, null)
        );

        // --- ASSERT ---

        // 1. Verify the final answer is returned
        assertEquals(finalAnswer, result);

        // 2. PROOF OF REWRITING: Verify the RerankedRagService received the REWRITTEN query,
        // not the raw follow-up
        ArgumentCaptor<String> ragQueryCaptor = ArgumentCaptor.forClass(String.class);
        verify(rerankedRagService).findAndRerankDocuments(
                ragQueryCaptor.capture(),
                eq("spring_ai"), eq("2.0.0"), anyInt(), anyInt()
        );

        assertEquals(expectedRewrittenQuery, ragQueryCaptor.getValue(),
                "RAG should receive the rewritten standalone query, not the raw follow-up ith 'it'.");

        // 3. PROOF OF SNOWBALL PREVENTION: Verify the final prompt sent to the LLM
        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        // The .system() method is called twice: once for rewrite, once for final answer
        verify(requestSpec, times(2)).system(systemPromptCaptor.capture());

        List<String> systemPrompts = systemPromptCaptor.getAllValues();
        // The second call is the final answer generation
        String finalSystemPrompt = systemPrompts.get(1);

        // The final prompt must contain the RAG context
        assertTrue(finalSystemPrompt.contains(
                "RAG context about SQL UNION and JOIN."),
                "Final prompt must contain the fetched RAG context."
                );

        // The final prompt must NOT contain the raw uncompressed chat history
        assertFalse(finalSystemPrompt.contains("What is UNION in SQL?"),
                "Final prompt must not contain the raw chat history " +
                        "this causes the snowball effect)."
        );

        // 4. Verify the interaction was saved to history
        verify(chatHistoryService, times(1))
                .saveMessage(eq(convId), eq("user"), eq(followQuery));
        verify(chatHistoryService, times(1))
                .saveMessage(eq(convId), eq("assistant"), eq(finalAnswer));
    }

    @Test
    void whenRequestSpecifiesCorpus_thenItOverridesDefaults() {
        var request = new ChatRequest("conv-456", "What is Tool Calling?", "spring-boot", "4.0");

        when(chatHistoryService.getRelevantHistory(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        when(callResponseSpec.content()).thenReturn("answer");

        when(rerankedRagService.findAndRerankDocuments(
                anyString(), anyString(), anyString(), anyInt(), anyInt()
        )).thenReturn(List.of(new Document("ctx")));

        chatService.chat(request);

        verify(rerankedRagService).findAndRerankDocuments(
                anyString(),
                eq("spring-boot"),
                eq("4.0"),
                anyInt(),
                anyInt()
        );
    }
}
