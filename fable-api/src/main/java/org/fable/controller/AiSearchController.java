package org.fable.controller;

import lombok.RequiredArgsConstructor;
import org.fable.model.dto.ai.AiServiceStatus;
import org.fable.repository.BookRepository;
import org.fable.service.ai.AiSearchHealthService;
import org.fable.service.ai.AiSearchService;
import org.fable.config.security.service.AuthenticationService;
import org.springframework.web.bind.annotation.*;
import org.fable.service.appsettings.AppSettingService;
import org.fable.model.dto.settings.AiLlmProfile;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ai/search")
public class AiSearchController {

    private final AiSearchHealthService aiSearchHealthService;
    private final AiSearchService aiSearchService;
    private final AuthenticationService authenticationService;
    private final BookRepository bookRepository;
    private final AppSettingService appSettingService;

    @GetMapping("/status")
    public AiServiceStatus getStatus() {
        return aiSearchHealthService.getStatus();
    }

    @GetMapping("/scan-status")
    public Map<String, Object> getScanStatus() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        boolean running = aiSearchService.isScanInProgress();
        result.put("running", running);
        result.put("progress", running ? aiSearchService.getLastBatchProgress() : null);
        return result;
    }

    @GetMapping("/llm-profiles")
    public List<AiLlmProfile> getLlmProfiles() {
        return appSettingService.getLlmProfiles();
    }

    @PostMapping("/llm-profiles")
    public Map<String, Object> saveLlmProfile(@RequestBody AiLlmProfile profile) throws Exception {
        appSettingService.saveLlmProfile(profile);
        return Map.of("success", true);
    }

    @DeleteMapping("/llm-profiles/{name}")
    public Map<String, Object> deleteLlmProfile(@PathVariable String name) throws Exception {
        appSettingService.deleteLlmProfile(name);
        return Map.of("success", true);
    }

    @PostMapping("/llm-profiles/{name}/activate")
    public Map<String, Object> activateLlmProfile(@PathVariable String name) throws Exception {
        appSettingService.activateLlmProfile(name);
        return Map.of("success", true);
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        return aiSearchHealthService.triggerReload();
    }

    @PostMapping("/embed")
    public Map<String, Object> embedBook(@RequestBody Map<String, Object> payload) {
        Long bookId = toLong(payload.get("bookId"));
        Long userId = authenticationService.getAuthenticatedUser().getId();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) payload.get("chunks");

        if (bookId == null) {
            throw new IllegalArgumentException("bookId is required.");
        }
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks are required.");
        }

        return aiSearchService.embedBook(bookId, userId, chunks, false);
    }

    @PostMapping("/query")
    public Map<String, Object> search(@RequestBody Map<String, Object> payload) {
        String query = (String) payload.get("query");
        Object rawBookIds = payload.get("bookIds");
        List<Long> bookIds = null;
        if (rawBookIds instanceof List<?> list) {
            bookIds = list.stream()
                    .filter(Number.class::isInstance)
                    .map(n -> ((Number) n).longValue())
                    .toList();
        }
        // Always derive userId from the authenticated session — never trust the client body.
        Long userId = authenticationService.getAuthenticatedUser().getId();

        @SuppressWarnings("unchecked")
        List<Map<String, String>> chatHistory = (List<Map<String, String>>) payload.get("chatHistory");

        boolean localOnly = Boolean.TRUE.equals(payload.get("localOnly"));

        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required.");
        }

        return aiSearchService.search(query, bookIds, userId, chatHistory, localOnly);
    }

    @GetMapping("/book-embeddings/{bookId}")
    public Map<String, Object> getBookEmbeddings(@PathVariable Long bookId) {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        return aiSearchService.getBookEmbeddingStatus(bookId, userId);
    }

    @GetMapping("/embed-status/{jobId}")
    public Map<String, Object> getEmbedJobStatus(@PathVariable String jobId) {
        return aiSearchService.getEmbedJobStatus(jobId);
    }

    @PostMapping("/extract-and-embed/{bookId}")
    public Map<String, Object> extractAndEmbedBook(@PathVariable Long bookId) {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        String username = authenticationService.getAuthenticatedUser().getUsername();
        aiSearchService.extractAndEmbedBook(bookId, userId, username);
        return Map.of("status", "STARTED");
    }

    @PostMapping("/scan-missing")
    public Map<String, Object> scanMissing(@RequestBody Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<Integer> libraryPathIdsInt = (List<Integer>) payload.get("libraryPathIds");
        if (libraryPathIdsInt == null || libraryPathIdsInt.isEmpty()) {
            throw new IllegalArgumentException("libraryPathIds is required");
        }
        List<Long> libraryPathIds = libraryPathIdsInt.stream().map(Integer::longValue).toList();
        
        Long userId = authenticationService.getAuthenticatedUser().getId();
        String username = authenticationService.getAuthenticatedUser().getUsername();
        
        aiSearchService.startScanMissingAiSearchEmbeddings(libraryPathIds, userId, username);
        return Map.of("status", "STARTED");
    }

    @PostMapping("/scan-marked")
    public Map<String, Object> scanMarked(@RequestBody Map<String, Object> payload) {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        String username = authenticationService.getAuthenticatedUser().getUsername();
        boolean forceRescan = Boolean.TRUE.equals(payload.get("forceRescan"));
        
        aiSearchService.startScanMarkedAiSearchEmbeddings(userId, username, forceRescan);
        return Map.of("status", "STARTED");
    }

    @PostMapping("/stop-scan")
    public Map<String, Object> stopScan() {
        aiSearchService.stopAiSearchScan();
        return Map.of("status", "STOPPED");
    }

    @PostMapping("/mark")
    public Map<String, Object> markBooksForAiSearch(@RequestBody Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<Integer> bookIdsInt = (List<Integer>) payload.get("bookIds");
        Boolean marked = (Boolean) payload.get("marked");

        if (bookIdsInt == null || marked == null) {
            throw new IllegalArgumentException("bookIds and marked status are required");
        }

        List<Long> bookIds = bookIdsInt.stream().map(Integer::longValue).toList();
        aiSearchService.markBooksForAiSearch(bookIds, marked);
        return Map.of("status", "UPDATED", "count", bookIds.size());
    }

    @GetMapping("/marked")
    public List<org.fable.model.dto.Book> getMarkedBooks() {
        List<org.fable.repository.projection.MarkedBookProjection> marked = bookRepository.findMarkedBooksInfo();
        return marked.stream().map(p -> org.fable.model.dto.Book.builder()
                .id(p.getId())
                .title(p.getTitle())
                .libraryName(p.getLibraryName())
                .build()).toList();
    }

    @GetMapping("/stats")
    public List<org.fable.repository.projection.EmbeddingStatsProjection> getEmbeddingStats(@RequestParam(required = false) Long libraryId) {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        if (libraryId != null) {
            return aiSearchService.getEmbeddingStats(userId, libraryId);
        }
        return aiSearchService.getEmbeddingStats(userId);
    }

    @GetMapping("/stats/summary")
    public Map<String, Object> getAiSearchStatsSummary(@RequestParam(required = false) Long libraryId) {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        return aiSearchService.getAiSearchStatsSummary(userId, libraryId);
    }

    @DeleteMapping("/embeddings")
    public Map<String, Object> deleteEmbeddings(@RequestBody Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<Number> rawBookIds = (List<Number>) payload.get("bookIds");
        if (rawBookIds == null || rawBookIds.isEmpty()) {
            throw new IllegalArgumentException("bookIds are required.");
        }
        Long userId = authenticationService.getAuthenticatedUser().getId();
        List<Long> bookIds = rawBookIds.stream().map(Number::longValue).toList();
        int deleted = aiSearchService.deleteBookEmbeddings(bookIds, userId);
        return Map.of("deletedCount", deleted);
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
