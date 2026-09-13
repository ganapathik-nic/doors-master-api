package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.gepnic.doors.masterapi.config.AiraProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sovereign Vector Knowledge Base Service for AIra.
 * Embeds and indexes DOORS operational guidelines, SOPs, and architectural rules
 * using local Ollama (nomic-embed-text) and LangChain4j InMemoryEmbeddingStore with disk persistence.
 */
@Slf4j
@Service
public class AiraKnowledgeService {

    private static final String OPERATIONAL_GUIDE = "classpath:knowledge/doors-operational-guide.md";
    private static final String SECURE_DOCUMENTS = "classpath*:secure-docs/*.pdf";
    private static final int PDF_CHUNK_MAX_CHARS = 1800;

    private final AiraProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    private volatile InMemoryEmbeddingStore<TextSegment> embeddingStore;
    private volatile EmbeddingModel embeddingModel;
    private volatile boolean ready = false;
    private volatile int segmentCount = 0;
    private final AtomicBoolean indexingInProgress = new AtomicBoolean(false);

    public AiraKnowledgeService(AiraProperties properties, ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("AIra is disabled; skipping vector knowledge base initialization.");
            return;
        }

        initEmbeddingModel();
        initOrLoadVectorStore();
    }

    public synchronized void initEmbeddingModel() {
        if (this.embeddingModel == null) {
            log.info("Initializing OllamaEmbeddingModel [model: {}, baseUrl: {}]...",
                    properties.getEmbeddingModel(), properties.getBaseUrl());
            this.embeddingModel = OllamaEmbeddingModel.builder()
                    .baseUrl(properties.getBaseUrl())
                    .modelName(properties.getEmbeddingModel())
                    .timeout(Duration.ofSeconds(60))
                    .logRequests(false)
                    .logResponses(false)
                    .build();
        }
    }

    private void initOrLoadVectorStore() {
        Path storePath = Paths.get(properties.getVectorStorePath());
        if (Files.exists(storePath)) {
            try {
                log.info("Loading DOORS vector knowledge base from cached store: {}", storePath.toAbsolutePath());
                this.embeddingStore = InMemoryEmbeddingStore.fromFile(storePath);
                this.segmentCount = countSegmentsInStoreFile(storePath);
                this.ready = true;
                log.info("Successfully loaded DOORS vector knowledge base ({} segments ready).", segmentCount);
                return;
            } catch (Exception e) {
                log.warn("Failed to load existing vector store file (will attempt fresh reindexing): {}", e.getMessage());
            }
        }

        // Store file does not exist or failed to load; build fresh index
        try {
            log.info("No valid vector store found at {}. Starting initial indexing...", storePath.toAbsolutePath());
            reindexKnowledge();
        } catch (Exception e) {
            log.warn("Initial vector knowledge base indexing deferred (Ollama may be offline): {}", e.getMessage());
        }
    }

    /**
     * Retrieve relevant operational knowledge segments for a user prompt via cosine similarity.
     */
    public List<String> findRelevantKnowledge(String query) {
        return findRelevantKnowledge(query, properties.getRagMaxResults(), properties.getRagMinScore());
    }

    public List<String> findRelevantKnowledge(String query, int maxResults, double minScore) {
        if (!properties.isEnabled() || !ready || embeddingStore == null || embeddingModel == null) {
            return Collections.emptyList();
        }

        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .minScore(minScore)
                    .build();

            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
            List<String> snippets = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : result.matches()) {
                if (match.embedded() != null && match.embedded().text() != null) {
                    snippets.add(match.embedded().text());
                }
            }
            log.debug("Found {} knowledge segments for query '{}' (minScore: {})", snippets.size(), query, minScore);
            return snippets;
        } catch (Exception e) {
            log.warn("Semantic knowledge retrieval failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Parse the Markdown operational guide and embed all sections into the vector store.
     */
    public synchronized int reindexKnowledge() {
        if (!indexingInProgress.compareAndSet(false, true)) {
            log.warn("Knowledge reindexing is already in progress.");
            return this.segmentCount;
        }

        try {
            initEmbeddingModel();
            Resource resource = resourceLoader.getResource(OPERATIONAL_GUIDE);
            if (!resource.exists()) {
                log.error("Knowledge source file not found at {}", OPERATIONAL_GUIDE);
                return 0;
            }

            String content;
            try (InputStream in = resource.getInputStream()) {
                content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            List<TextSegment> segments = new ArrayList<>(parseMarkdownToSegments(content));
            Resource[] pdfResources = new PathMatchingResourcePatternResolver(resourceLoader.getClassLoader())
                    .getResources(SECURE_DOCUMENTS);
            Arrays.sort(pdfResources, Comparator.comparing(Resource::getFilename,
                    Comparator.nullsLast(String::compareToIgnoreCase)));
            if (pdfResources.length == 0) {
                throw new IllegalStateException("No secured PDF documents found at " + SECURE_DOCUMENTS);
            }
            for (Resource pdfResource : pdfResources) {
                List<TextSegment> documentSegments = parsePdfToSegments(pdfResource);
                if (documentSegments.isEmpty()) {
                    throw new IllegalStateException("PDF produced no searchable text: " + pdfResource.getFilename());
                }
                segments.addAll(documentSegments);
                log.info("Prepared {} vector segments from {}", documentSegments.size(), pdfResource.getFilename());
            }
            if (segments.isEmpty()) {
                log.warn("Knowledge sources produced 0 segments.");
                return 0;
            }

            log.info("Embedding {} operational knowledge segments using {}...",
                    segments.size(), properties.getEmbeddingModel());

            InMemoryEmbeddingStore<TextSegment> newStore = new InMemoryEmbeddingStore<>();
            for (int i = 0; i < segments.size(); i++) {
                TextSegment segment = segments.get(i);
                Embedding embedding = embeddingModel.embed(segment).content();
                newStore.add(embedding, segment);
            }

            Path storePath = Paths.get(properties.getVectorStorePath());
            if (storePath.getParent() != null) {
                Files.createDirectories(storePath.getParent());
            }

            newStore.serializeToFile(storePath);

            this.embeddingStore = newStore;
            this.segmentCount = segments.size();
            this.ready = true;

            log.info("DOORS Vector Knowledge Base successfully indexed {} segments to {}",
                    segmentCount, storePath.toAbsolutePath());
            return segmentCount;
        } catch (Exception e) {
            log.error("Failed to reindex DOORS operational knowledge base: {}", e.getMessage(), e);
            throw new RuntimeException("Knowledge indexing failed: " + e.getMessage(), e);
        } finally {
            indexingInProgress.set(false);
        }
    }

    /**
     * Chunks a markdown document into hierarchical segments based on headers (#, ##, ###).
     */
    public List<TextSegment> parseMarkdownToSegments(String markdown) {
        List<TextSegment> segments = new ArrayList<>();
        String[] lines = markdown.split("\\r?\\n");

        String currentH1 = "DOORS Platform Operational Guide";
        String currentH2 = "";
        String currentH3 = "";
        StringBuilder currentSection = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                currentH1 = trimmed.substring(2).trim();
            } else if (trimmed.startsWith("## ") && !trimmed.startsWith("### ")) {
                if (!currentSection.toString().trim().isEmpty()) {
                    String heading = buildHeading(currentH1, currentH2, currentH3);
                    segments.add(TextSegment.from(heading + "\n" + currentSection.toString().trim()));
                    currentSection.setLength(0);
                }
                currentH2 = trimmed.substring(3).trim();
                currentH3 = "";
            } else if (trimmed.startsWith("### ")) {
                if (!currentSection.toString().trim().isEmpty()) {
                    String heading = buildHeading(currentH1, currentH2, currentH3);
                    segments.add(TextSegment.from(heading + "\n" + currentSection.toString().trim()));
                    currentSection.setLength(0);
                }
                currentH3 = trimmed.substring(4).trim();
            } else if (trimmed.equals("---")) {
                // Ignore horizontal divider
            } else {
                currentSection.append(line).append("\n");
            }
        }

        if (!currentSection.toString().trim().isEmpty()) {
            String heading = buildHeading(currentH1, currentH2, currentH3);
            segments.add(TextSegment.from(heading + "\n" + currentSection.toString().trim()));
        }

        return segments;
    }

    /**
     * Extracts every PDF page and breaks its text into bounded paragraph-aware chunks.
     * The source and page prefix is persisted with every vector so coverage remains auditable.
     */
    List<TextSegment> parsePdfToSegments(Resource resource) throws Exception {
        byte[] pdfBytes;
        try (InputStream input = resource.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            pdfBytes = output.toByteArray();
        }

        List<TextSegment> segments = new ArrayList<>();
        String fileName = resource.getFilename() == null ? "unknown.pdf" : resource.getFilename();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = normalizePdfText(stripper.getText(document));
                if (pageText.isBlank()) {
                    continue;
                }
                List<String> chunks = chunkText(pageText, PDF_CHUNK_MAX_CHARS);
                for (int index = 0; index < chunks.size(); index++) {
                    String prefix = "[SOURCE: secure-docs/" + fileName + " | PAGE: " + page
                            + " | CHUNK: " + (index + 1) + "]";
                    segments.add(TextSegment.from(prefix + "\n" + chunks.get(index)));
                }
            }
        }
        return segments;
    }

    private static String normalizePdfText(String text) {
        return text.replace('\u0000', ' ')
                .replaceAll("(?m)[\\t ]+$", "")
                .replaceAll("(?m)^[\\t ]+", "")
                .replaceAll("\\R{3,}", "\n\n")
                .trim();
    }

    private static List<String> chunkText(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\\R\\s*\\R")) {
            String remaining = paragraph.replaceAll("\\s+", " ").trim();
            while (remaining.length() > maxChars) {
                int splitAt = remaining.lastIndexOf(' ', maxChars);
                if (splitAt < maxChars / 2) splitAt = maxChars;
                appendChunk(chunks, current);
                chunks.add(remaining.substring(0, splitAt).trim());
                remaining = remaining.substring(splitAt).trim();
            }
            if (remaining.isEmpty()) continue;
            if (current.length() > 0 && current.length() + 2 + remaining.length() > maxChars) {
                appendChunk(chunks, current);
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(remaining);
        }
        appendChunk(chunks, current);
        return chunks;
    }

    private static void appendChunk(List<String> chunks, StringBuilder current) {
        if (!current.isEmpty()) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }

    private String buildHeading(String h1, String h2, String h3) {
        StringBuilder sb = new StringBuilder("[").append(h1).append("]");
        if (!h2.isEmpty()) {
            sb.append(" > ").append(h2);
        }
        if (!h3.isEmpty()) {
            sb.append(" > ").append(h3);
        }
        return sb.toString();
    }

    private int countSegmentsInStoreFile(Path storePath) {
        try {
            String json = Files.readString(storePath, StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(json);
            if (root.has("entries") && root.get("entries").isArray()) {
                return root.get("entries").size();
            }
            if (root.isArray()) {
                return root.size();
            }
        } catch (Exception e) {
            log.debug("Could not determine exact entry count from file: {}", e.getMessage());
        }
        return -1;
    }

    public boolean isReady() {
        return ready && embeddingStore != null;
    }

    public int getSegmentCount() {
        return segmentCount;
    }
}
