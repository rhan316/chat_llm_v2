package org.dar316.spring_ai.service.github;

import org.dar316.spring_ai.config.GitHubProperties;
import org.dar316.spring_ai.dto.github.GithubIndexRequest;
import org.dar316.spring_ai.dto.github.client.GitHubDiscussionResponse;
import org.dar316.spring_ai.dto.github.client.GitHubTreeResponse;
import org.dar316.spring_ai.dto.github.client.ResolvedRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class GitHubRepositoryIndexerTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private GitHubClient gitHubClient;

    @Mock
    private GitHubRepositorySyncLock syncLock;

    private GitHubRepositoryIndexer indexer;

    @BeforeEach
    public void setUp() {

        var properties = new GitHubProperties();
        properties.getIndex().setMaxChunks(10_000);

        // Bypass the lock and execute the operation directly
        when(syncLock.withWriteLock(anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    Supplier<?> supplier = inv.getArgument(2);
                    return supplier.get();
                });

        indexer = new GitHubRepositoryIndexer(vectorStore, gitHubClient, properties, syncLock);
    }

    @Test
    void whenDiscussionsAreUnanswered_thenTheyAreFilteredOut() {
        var req = new GithubIndexRequest(
                "owner", "repo", "main", "tech", "1.0"
        );

        // 1. Mock an empty repo tree so we don't deal with file indexing logic
        when(gitHubClient.resolveRevision(anyString(), anyString(), anyString()))
                .thenReturn(new ResolvedRevision("commitSha", "treeSha"));

        when(gitHubClient.getRecursiveTree(anyString(), anyString(), anyString()))
                .thenReturn(new GitHubTreeResponse(false, List.of()).tree());

        // 2. Create a long text string to ensure TokenTextSplitter produces a chunk
        String longText = """
                Lorem ipsum dolor sit amet, consectetur adipiscing elit.
                Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.
                Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi
                ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit.
                """
                .repeat(100);

        // 3. Mock discussion: 1 valid (answered, upvotes > 1), 1 invalid (unanswered, 0 upvotes)
        List<GitHubDiscussionResponse> discussions = List.of(
                new GitHubDiscussionResponse(
                        1, "Valid Discussion", longText,
                        List.of(new GitHubDiscussionResponse.GitHubContent("Comment")),
                        1, new GitHubDiscussionResponse.Reactions(5)
                ),
                new GitHubDiscussionResponse(
                        2, "invalid Discussion", longText,
                        List.of(),
                        0, new GitHubDiscussionResponse.Reactions(0)
                )
        );

        when(gitHubClient.getDiscussions(anyString(), anyString()))
                .thenReturn(discussions);

        // Act
        var response = indexer.index(req);

        // Assert
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, atLeastOnce())
                .add(captor.capture());

        // Flatten all captured documents
        List<Document> addedDocs = captor.getAllValues().stream()
                .flatMap(List::stream)
                .toList();

        // Only the valid discussion should have been indexed
        assertEquals(10, addedDocs.size());
        assertEquals("discussion", addedDocs.getFirst().getMetadata().get("document_type"));
        assertTrue(addedDocs.getFirst().getMetadata().containsKey("expires_at"));
    }
}
