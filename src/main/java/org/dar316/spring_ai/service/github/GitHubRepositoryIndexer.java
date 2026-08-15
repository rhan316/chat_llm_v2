package org.dar316.spring_ai.service.github;

import org.dar316.spring_ai.config.GitHubProperties;
import org.dar316.spring_ai.dto.github.GitHubIndexResponse;
import org.dar316.spring_ai.dto.github.GithubIndexRequest;
import org.dar316.spring_ai.dto.github.client.GitHubDiscussionResponse;
import org.dar316.spring_ai.dto.github.client.GitHubTreeEntry;
import org.dar316.spring_ai.dto.github.client.ResolvedRevision;
import org.dar316.spring_ai.dto.github.client.TextBlob;
import org.dar316.spring_ai.dto.github.repoIndexer.NormalizedRequest;
import org.dar316.spring_ai.dto.github.repoIndexer.Selection;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

@Service
public final class GitHubRepositoryIndexer {

    private static final Pattern SECTION_SEPARATOR =
            Pattern.compile("(?m)^\\h*\\+\\+\\+RAG_SECTION\\+\\+\\+\\h*$");

    private static final Pattern SIMPLE_GITHUB_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}");

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "adoc",
            "asciidoc",
            "bash",
            "c",
            "cc",
            "cfg",
            "conf",
            "cpp",
            "cs",
            "css",
            "go",
            "gradle",
            "groovy",
            "h",
            "hpp",
            "html",
            "ini",
            "java",
            "js",
            "json",
            "jsx",
            "kt",
            "kts",
            "markdown",
            "md",
            "php",
            "properties",
            "py",
            "rb",
            "rst",
            "rs",
            "scala",
            "sh",
            "sql",
            "toml",
            "ts",
            "tsx",
            "txt",
            "xml",
            "yaml",
            "yml"
    );

    private static final Set<String> EXCLUDED_DIRECTORY_NAMES = Set.of(
            ".git",
            ".gradle",
            ".idea",
            ".mvn",
            ".vscode",
            "build",
            "coverage",
            "dist",
            "generated",
            "node_modules",
            "out",
            "target",
            "vendor"
    );

    private static final Set<String> ROOT_TEXT_FILES = Set.of(
            "build.gradle",
            "build.gradle.kts",
            "dockerfile",
            "docker-compose.yaml",
            "docker-compose.yml",
            "gradle.properties",
            "mvnw",
            "pom.xml",
            "settings.gradle",
            "settings.gradle.kts"
    );

    private final org.springframework.ai.vectorstore.VectorStore vectorStore;
    private final GitHubClient gitHubClient;
    private final GitHubProperties properties;
    private final GitHubRepositorySyncLock syncLock;

    public GitHubRepositoryIndexer(
            org.springframework.ai.vectorstore.VectorStore vectorStore,
            GitHubClient gitHubClient,
            GitHubProperties properties,
            GitHubRepositorySyncLock syncLock
    ) {
        this.vectorStore = Objects.requireNonNull(
                vectorStore,
                "vectorStore must not be null"
        );
        this.gitHubClient = Objects.requireNonNull(
                gitHubClient,
                "gitHubClient must not be null"
        );
        this.properties = Objects.requireNonNull(
                properties,
                "properties must not be null"
        );
        this.syncLock = Objects.requireNonNull(
                syncLock,
                "syncLock must not be null"
        );
    }

    public GitHubIndexResponse index(GithubIndexRequest request) {
        var normalized = normalize(request);

        return syncLock.withWriteLock(
                normalized.repository(),
                normalized.ref(),
                () -> indexLocked(normalized)
        );
    }

    private GitHubIndexResponse indexLocked(NormalizedRequest request) {
        ResolvedRevision revision = gitHubClient.resolveRevision(
                request.owner(),
                request.repositoryName(),
                request.ref()
        );

        List<GitHubTreeEntry> tree = gitHubClient.getRecursiveTree(
                request.owner(),
                request.repositoryName(),
                revision.treeSha()
        );

        var selection = selectFiles(tree);

        String syncId = UUID.randomUUID().toString();
        String indexedAt = Instant.now().toString();

        int indexedFiles = 0;
        int skippedFiles = selection.skippedFiles();
        int totalChunks = 0;
        boolean currentSyncStored = false;

        try {
            for (GitHubTreeEntry entry : selection.files()) {
                Optional<TextBlob> blob = gitHubClient.getUtf8TextBlob(
                        request.owner(),
                        request.repositoryName(),
                        entry.sha()
                );

                if (blob.isEmpty()) {
                    skippedFiles++;
                    continue;
                }

                List<Document> chunks = createChunks(
                        blob.get().text(),
                        request,
                        revision.commitSha(),
                        entry,
                        syncId,
                        indexedAt
                );

                if (chunks.isEmpty()) {
                    skippedFiles++;
                    continue;
                }

                if (totalChunks + chunks.size() > properties.getIndex().getMaxChunks()) {
                    throw new ResponseStatusException(
                            HttpStatus.CONTENT_TOO_LARGE,
                            "Repository exceeds github.index.max-chunks"
                    );
                }

                currentSyncStored = true;
                vectorStore.add(chunks);

                indexedFiles++;
                totalChunks += chunks.size();
            }

            int discussionChunks = indexDiscussions(
                    request,
                    revision.commitSha(),
                    syncId,
                    indexedAt,
                    totalChunks
            );

            if (discussionChunks > 0) {
                totalChunks += discussionChunks;
                currentSyncStored = true;
            }

            if (indexedFiles == 0 && discussionChunks == 0) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "No supported README, documentation, source, or configuration files were found"
                );
            }

            deletePreviousSyncs(request.repository(), request.ref(), syncId);

            return new GitHubIndexResponse(
                    request.repository(),
                    request.ref(),
                    revision.commitSha(),
                    indexedFiles,
                    skippedFiles,
                    totalChunks
            );
        } catch (RuntimeException e) {
            if (currentSyncStored) {
                rollbackCurrentSync(request.repository(), request.ref(), syncId);
            }

            throw e;
        }
    }

    private int indexDiscussions(
            NormalizedRequest request,
            String commitSha,
            String syncId,
            String indexedAt,
            int totalChunks
    ) {
        List<GitHubDiscussionResponse> discussions = gitHubClient.getDiscussions(
                request.owner(),
                request.repositoryName()
        );

        if (discussions.isEmpty()) return 0;

        int addedChunks = 0;
        int indexedDiscussionsCount = 0;

        for (var dis : discussions) {

            boolean isAnswered = dis.answerCount() != null && dis.answerCount() > 0;
            int upvotes = dis.reactions() != null ? dis.reactions().totalCount() : 0;

            // Skip noisy or unanswered discussions
            if (!isAnswered && upvotes < 2) continue;

            // Format discussion int a single text blob using the RAG_SECTION separator
            var disText = new StringBuilder();
            disText.append("# Discussion: ").append(dis.title()).append("\n\n");
            disText.append("Upvotes: ").append(upvotes).append("\n");
            disText.append("Answered: ").append(isAnswered).append("\n");
            disText.append(dis.body() != null ? dis.body() : "");

            if (dis.comments() != null) {
                for (var comm :  dis.comments()) {
                    if (comm.body() != null && !comm.body().isBlank()) {
                        disText.append("\n\n+++RAG_SECTION+++\n\n");
                        disText.append(comm.body());
                    }
                }
            }

            /*
            Create a synthetic GitHubTreeEntry so we can reuse createChunks
            Alternatively, write a dedicated chunking method for a discussions to avoid hacky entry creation.
            For simplicity, we construct the metadata directly:
             */

            Map<String, Object> sourceMetadata = new HashMap<>();
            String sourceId = "github_discussion:" + request.repository() + "@" + request.ref() + ":" + dis.number();

            sourceMetadata.put("source", sourceId);
            sourceMetadata.put("source_id", sourceId);
            sourceMetadata.put("source_type", "github");
            sourceMetadata.put("repository", request.repository());
            sourceMetadata.put("repository_ref", request.ref());
            sourceMetadata.put("commit_sha", commitSha);
            sourceMetadata.put("path", "discussions/" + dis.number());
            sourceMetadata.put("github_blob_sha", "discussion-" + dis.number());
            sourceMetadata.put("technology", request.technology());
            sourceMetadata.put("technology_version", request.technologyVersion());
            sourceMetadata.put("document_type", "discussion");
            sourceMetadata.put("language", "en");
            sourceMetadata.put("sync_id", syncId);
            sourceMetadata.put("indexed_at", indexedAt);

            // Add an expiration timestamp (60 days from now) in epoch millis
            long expiresAt = Instant.now()
                    .plus(60, ChronoUnit.DAYS)
                    .toEpochMilli();

            sourceMetadata.put("expires_at", expiresAt);

            List<Document> sections = splitIntoSections(disText.toString(), sourceMetadata);
            if (sections.isEmpty()) continue;

            var splitter = TokenTextSplitter.builder()
                    .withChunkSize(600)
                    .withMinChunkSizeChars(250)
                    .withMinChunkLengthToEmbed(50)
                    .withMaxNumChunks(20_000)
                    .withKeepSeparator(true)
                    .build();

            List<Document> chunks = splitter.apply(sections);
            List<Document> enriched = new ArrayList<>(chunks.size());

            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> metadata = new HashMap<>(chunks.get(i).getMetadata());
                metadata.put("chunk_index", i);
                metadata.put("chunk_count", chunks.size());
                enriched.add(new Document(chunks.get(i).getText(), metadata));
            }

            // Check max chunks limit
            if (totalChunks + addedChunks + enriched.size() > properties.getIndex().getMaxChunks()) {
                throw new ResponseStatusException(
                        HttpStatus.CONTENT_TOO_LARGE,
                        "Repository exceeds github.index.max-chunks (including discussions)"
                );
            }

            vectorStore.add(enriched);
            addedChunks += enriched.size();
            indexedDiscussionsCount++;
        }

        return addedChunks;
    }

    private Selection selectFiles(List<GitHubTreeEntry> tree) {
        List<GitHubTreeEntry> blobs = tree.stream()
                .filter(Objects::nonNull)
                .filter(entry -> "blob".equals(entry.type()))
                .toList();

        List<GitHubTreeEntry> selected = blobs.stream()
                .filter(this::isCandidate)
                .sorted(
                        java.util.Comparator.comparing(GitHubTreeEntry::path)
                )
                .toList();

        if (selected.size() > properties.getIndex().getMaxFiles()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Repository exceeds maximum allowed files");
        }

        long totalBytes = 0L;

        for  (GitHubTreeEntry entry : selected) {
            try {
                totalBytes = Math.addExact(totalBytes, entry.size());
            } catch (ArithmeticException e) {
                throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, e.getMessage());
            }
        }

        if (totalBytes > properties.getIndex().getMaxTotalBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Repository exceeds maximum allowed bytes");
        }

        return new Selection(selected, blobs.size() - selected.size());
    }

    private boolean isCandidate(GitHubTreeEntry entry) {
        if (entry.path() == null
                || entry.path().isBlank()
                || entry.sha() == null
                || entry.sha().isBlank()
                || entry.size() == null
                || entry.size() < 0
                || entry.size() > properties.getIndex().getMaxFileSizeBytes()) {
            return false;
        }

        if ("120000".equals(entry.mode())) {
            return false;
        }

        String path = entry.path().strip();

        if (!isSafeGitPath(path)) {
            return false;
        }

        String normalizedPath = path.toLowerCase(Locale.ROOT);

        if (containsExcludedDirectory(normalizedPath)
                || isIgnoredFilename(normalizedPath)) {
            return false;
        }

        String extension = extensionOf(normalizedPath);

        // Decyzja końcowa: akceptujemy plik, jeśli jest readme,
        // znajduje się w ROOT_TEXT_FILES, LUB ma obsługiwane rozszerzenie.
        return isReadme(normalizedPath)
                || ROOT_TEXT_FILES.contains(normalizedPath)
                || TEXT_EXTENSIONS.contains(extension);
    }

    private List<Document> createChunks(
            String text,
            NormalizedRequest request,
            String commitSha,
            GitHubTreeEntry entry,
            String syncId,
            String indexedAt
    ) {
        if (Objects.isNull(text) || text.isBlank()) {
            return List.of();
        }

        String path = entry.path().strip();
        String sourceId = "github:" +
                request.repository() +
                "@" +
                request.ref() +
                ":" +
                path;

        Map<String, Object> sourceMetadata = new HashMap<>();

        sourceMetadata.put("source", sourceId);
        sourceMetadata.put("source_id", sourceId);
        sourceMetadata.put("source_type", "github");
        sourceMetadata.put("repository", request.repository());
        sourceMetadata.put("repository_ref", request.ref());
        sourceMetadata.put("commit_sha", commitSha);
        sourceMetadata.put("path", path);
        sourceMetadata.put("github_blob_sha", entry.sha());
        sourceMetadata.put("technology", request.technology());
        sourceMetadata.put(
                "technology_version",
                request.technologyVersion()
        );
        sourceMetadata.put("document_type", documentType(path));
        sourceMetadata.put("language", languageFor(path));
        sourceMetadata.put("sync_id", syncId);
        sourceMetadata.put("indexed_at", indexedAt);

        List<Document> sections = splitIntoSections(text, sourceMetadata);

        if (sections.isEmpty()) return List.of();

        var splitter = TokenTextSplitter.builder()
                .withChunkSize(600)
                .withMinChunkSizeChars(250)
                .withMinChunkLengthToEmbed(50)
                .withMaxNumChunks(20_000)
                .withKeepSeparator(true)
                .build();

        List<Document> chunks = splitter.apply(sections);

        if (chunks.isEmpty()) return List.of();

        List<Document> enriched = new  ArrayList<>(chunks.size());

        for (int index = 0; index < chunks.size(); index++) {
            Document chunk = Objects.requireNonNull(
                    chunks.get(index),
                    "chunk must not be null"
            );

            Map<String, Object> metadata = new HashMap<>(
                    chunk.getMetadata()
            );

            metadata.put("chunk_index", index);
            metadata.put("chunk_count", chunks.size());

            enriched.add(
                    new Document(
                            Objects.requireNonNull(
                                    chunk.getText(),
                                    "chunk text must not be null"
                            ),
                            metadata
                    )
            );
        }

        return enriched;
    }

    private List<Document> splitIntoSections(String text, Map<String, Object> sourceMetadata) {
        List<String> sectionTexts = SECTION_SEPARATOR
                .splitAsStream(text)
                .map(String::strip)
                .filter(sect -> !sect.isBlank())
                .toList();

        List<Document> sections = new ArrayList<>(sectionTexts.size());

        for (int i = 0; i < sectionTexts.size(); i++) {
            Map<String, Object> metadata = new HashMap<>(sourceMetadata);

            metadata.put("source_document_index", 0);
            metadata.put("section_index", i);
            metadata.put("section_count",  sectionTexts.size());
            metadata.put("section_id", "0:" + i);

            sections.add(new Document(sectionTexts.get(i), metadata));
        }

        return sections;
    }

    private void deletePreviousSyncs(String repository, String ref, String currentSyncId) {
        var feb = new FilterExpressionBuilder();

        vectorStore.delete(
                feb.and(
                        feb.eq("source_type", "github"),
                        feb.and(
                                feb.eq("repository", repository),
                                feb.and(
                                        feb.eq("repository_ref", ref),
                                        feb.ne("sync_id", currentSyncId)
                                )
                        )
                ).build()
        );
    }

    private void rollbackCurrentSync(String repository, String ref, String syncId) {
        try {
            var feb = new FilterExpressionBuilder();

            vectorStore.delete(
                    feb.and(
                            feb.eq("source_type", "github"),
                            feb.and(
                                    feb.eq("repository", repository),
                                    feb.and(
                                            feb.eq("repository_ref", ref),
                                            feb.eq("sync_id", syncId)
                                    )
                            )
                    ).build()
            );
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private NormalizedRequest normalize(GithubIndexRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        String owner = requireGitHubName(request.owner(), "owner");
        String repoName = requireGitHubName(request.repository(), "repository");
        String ref = requireRef(request.ref());

        return new NormalizedRequest(
                owner,
                repoName,
                owner + "/" + repoName,
                ref,
                requireNonBlank(request.technology(), "technology"),
                requireNonBlank(request.technologyVersion(), "technologyVersion")
        );
    }

    private String requireGitHubName(String value, String fieldName) {
        String normalized = requireNonBlank(value, fieldName);

        if (!SIMPLE_GITHUB_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException(fieldName + " is not a valid GitHub name");
        }

        return normalized;
    }

    private String requireRef(String value) {
        String normalized = requireNonBlank(value, "ref");

        if (normalized.length() > 255
                || normalized.startsWith("/")
                || normalized.endsWith("/")
                || normalized.contains("..")
                || normalized.contains("@{")
                || normalized.chars().anyMatch(
                Character::isISOControl
        )) {
            throw new IllegalArgumentException(
                    "ref has an invalid Git format"
            );
        }

        return normalized;
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return value.strip();
    }

    private boolean isSafeGitPath(String path) {
        if (path.startsWith("/") || path.indexOf('\u0000') >= 0 ) return false;

        String[] seg =  path.split("/", -1);

        for (String s : seg) {
            if (s.isBlank() || s.equals(".") || s.equals("..")) return false;
        }

        return true;
    }

    private boolean containsExcludedDirectory(String normalizedPath) {
        String[] segments = normalizedPath.split("/");

        for (int i = 0; i < segments.length - 1; i++) {
            if (EXCLUDED_DIRECTORY_NAMES.contains(segments[i])) {
                return true;
            }
        }

        return false;
    }

    private boolean isIgnoredFilename(String normalizedPath) {
        return normalizedPath.endsWith(".min.js")
                || normalizedPath.endsWith(".map")
                || normalizedPath.endsWith(".lock")
                || normalizedPath.endsWith("package-lock.json")
                || normalizedPath.endsWith("pnpm-lock.yaml")
                || normalizedPath.endsWith("yarn.lock");
    }

    private boolean isReadme(String normalizedPath) {
        if (normalizedPath.contains("/")) return false;

        return normalizedPath.equals("readme") || normalizedPath.startsWith("readme.");
    }

    private String documentType(String path) {
        return extensionOf(path).isEmpty() ? "text" :  extensionOf(path);
    }

    private String languageFor(String path) {
        String normalizedPath = path.toLowerCase(Locale.ROOT);

        if (normalizedPath.endsWith("/dockerfile")
                || normalizedPath.equals("dockerfile")
        ) {
            return "dockerfile";
        }

        return switch (extensionOf(path)) {
            case "java" -> "java";
            case "kt", "kts" -> "kotlin";
            case "groovy", "gradle" -> "groovy";
            case "xml" -> "xml";
            case "properties", "ini", "cfg", "conf" -> "properties";
            case "yaml", "yml" -> "yaml";
            case "json" -> "json";
            case "sql" -> "sql";
            case "py" -> "python";
            case "js", "jsx" -> "javascript";
            case "ts", "tsx" -> "typescript";
            case "go" -> "go";
            case "rs" -> "rust";
            case "rb" -> "ruby";
            case "php" -> "php";
            case "c", "cc", "cpp", "h", "hpp" -> "c-family";
            case "sh", "bash" -> "shell";
            case "md", "markdown", "adoc", "asciidoc", "rst" -> "documentation";
            case "html" -> "html";
            case "css" -> "css";
            default -> "text";
        };
    }

    private String extensionOf(String path) {
        int slashIndex = path.lastIndexOf('/');
        int dotIndex = path.lastIndexOf('.');

        if (dotIndex <= slashIndex || dotIndex == path.length() - 1) {
            return "";
        }

        return path.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
