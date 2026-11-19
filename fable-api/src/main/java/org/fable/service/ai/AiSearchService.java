package org.fable.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.fable.config.AppProperties;
import org.fable.model.dto.Book;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.BookFileEntity;
import org.fable.model.enums.BookFileType;
import org.fable.model.websocket.Topic;
import org.fable.service.network.NetworkErrorClassifier;
import org.fable.service.network.NetworkOutageExhaustedException;
import org.fable.service.network.NetworkResilienceService;
import org.fable.task.TaskCancellationManager;
import org.fable.repository.BookRepository;
import org.fable.config.security.service.AuthenticationService;
import org.fable.service.NotificationService;
import org.fable.service.book.BookCreatorService;
import org.fable.service.reader.CbxReaderService;
import org.fable.service.metadata.BookMetadataService;
import org.fable.util.epub.EpubContentReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.text.BreakIterator;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class AiSearchService {

    private final AppProperties appProperties;
    private final NotificationService notificationService;
    private final BookRepository bookRepository;
    private final AuthenticationService authenticationService;
    private final AiSearchHealthService aiSearchHealthService;
    private final org.fable.service.appsettings.AppSettingService appSettingService;
    private final BookCreatorService bookCreatorService;
    private final BookMetadataService bookMetadataService;
    private final ObjectMapper objectMapper;
    private final org.fable.service.book.BookService bookService;
    private final org.fable.service.event.BookEventBroadcaster bookEventBroadcaster;
    private final TaskCancellationManager cancellationManager;
    private final CbxReaderService cbxReaderService;
    private final NetworkResilienceService networkResilienceService;

    private static final int CHUNK_BATCH_SIZE = 50;
    private static final int MAX_CBX_IMAGE_BYTES = 2 * 1024 * 1024;  // 2 MB
    private static final int MAX_SIDECAR_RECOVERY_RETRIES_PER_BOOK = 2;

    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);
    private volatile Map<String, Object> lastBatchProgress;

    @Autowired
    public AiSearchService(
            AppProperties appProperties,
            NotificationService notificationService,
            BookRepository bookRepository,
            AuthenticationService authenticationService,
            AiSearchHealthService aiSearchHealthService,
            org.fable.service.appsettings.AppSettingService appSettingService,
            BookCreatorService bookCreatorService,
            BookMetadataService bookMetadataService,
            ObjectMapper objectMapper,
            org.fable.service.book.BookService bookService,
            org.fable.service.event.BookEventBroadcaster bookEventBroadcaster,
            TaskCancellationManager cancellationManager,
            CbxReaderService cbxReaderService,
            NetworkResilienceService networkResilienceService) {
        this.appProperties = appProperties;
        this.notificationService = notificationService;
        this.bookRepository = bookRepository;
        this.authenticationService = authenticationService;
        this.aiSearchHealthService = aiSearchHealthService;
        this.appSettingService = appSettingService;
        this.bookCreatorService = bookCreatorService;
        this.bookMetadataService = bookMetadataService;
        this.objectMapper = objectMapper;
        this.bookService = bookService;
        this.bookEventBroadcaster = bookEventBroadcaster;
        this.cancellationManager = cancellationManager;
        this.cbxReaderService = cbxReaderService;
        this.networkResilienceService = networkResilienceService;
    }

    public AiSearchService(
            AppProperties appProperties,
            NotificationService notificationService,
            BookRepository bookRepository,
            AuthenticationService authenticationService,
            AiSearchHealthService aiSearchHealthService,
            org.fable.service.appsettings.AppSettingService appSettingService,
            BookCreatorService bookCreatorService,
            BookMetadataService bookMetadataService,
            ObjectMapper objectMapper,
            org.fable.service.book.BookService bookService,
            org.fable.service.event.BookEventBroadcaster bookEventBroadcaster,
            TaskCancellationManager cancellationManager,
            CbxReaderService cbxReaderService) {
        this(appProperties, notificationService, bookRepository, authenticationService,
                aiSearchHealthService, appSettingService, bookCreatorService, bookMetadataService,
                objectMapper, bookService, bookEventBroadcaster, cancellationManager,
                cbxReaderService, null);
    }

    public boolean isScanInProgress() {
        return scanInProgress.get();
    }

    public Map<String, Object> getLastBatchProgress() {
        return lastBatchProgress;
    }

    public void stopAiSearchScan() {
        scanInProgress.set(false);
    }

    @Async
    public void startScanMissingAiSearchEmbeddings(List<Long> libraryPathIds, Long userId, String username) {
        if (!scanInProgress.compareAndSet(false, true)) {
            log.warn("AI Search scan already in progress");
            return;
        }

        try {
            sendBatchProgress(username, "STARTED", "Finding books to scan...", null, 0, 0, null, null);

            List<Long> epubBookIds = bookRepository.findAllBookIdsByLibraryPathIdInAndBookType(libraryPathIds, BookFileType.EPUB);
            List<Long> pdfBookIds = bookRepository.findAllBookIdsByLibraryPathIdInAndBookType(libraryPathIds, BookFileType.PDF);
            List<Long> cbxBookIds = bookRepository.findAllBookIdsByLibraryPathIdInAndBookType(libraryPathIds, BookFileType.CBX);
            
            Set<Long> allBookIds = new HashSet<>();
            allBookIds.addAll(epubBookIds);
            allBookIds.addAll(pdfBookIds);
            allBookIds.addAll(cbxBookIds);

            if (allBookIds.isEmpty()) {
                sendBatchProgress(username, "COMPLETED", "No EPUB, PDF, or CBX books found in selected paths.", null, 0, 0, null, null);
                return;
            }

            List<Long> embeddedIds = bookRepository.findBookIdsWithAiSearchEmbeddings(userId, allBookIds)
                .stream()
                .map(org.fable.repository.projection.AiSearchBookStatusProjection::getBookId)
                .toList();
            allBookIds.removeAll(embeddedIds);

            int total = allBookIds.size();
            if (total == 0) {
                sendBatchProgress(username, "COMPLETED", "All books in selected paths are already embedded.", null, 0, 0, null, null);
                return;
            }

            int current = 0;
            sendBatchProgress(username, "IN_PROGRESS", "Starting extraction...", null, current, total, null, null);

            List<String> importedBookTitles = new ArrayList<>();
            List<String> failedBookTitles = new ArrayList<>();

            for (Long bookId : allBookIds) {
                if (!scanInProgress.get()) {
                    sendBatchProgress(username, "STOPPED", "Scan was manually stopped.", null, current, total, null, null);
                    return;
                }
                
                try {
                    String title = resolveBookTitle(bookId);
                    sendBatchProgress(username, "IN_PROGRESS", "Processing: " + title + " (book " + (current + 1) + " of " + total + ")", null, current, total, null, null);
                    extractAndEmbedBookInternal(bookId, userId, username, true, current, total);
                    importedBookTitles.add(title);
                } catch (Exception e) {
                    log.error("Failed to embed book {} during batch scan: {}", bookId, e.getMessage());
                    failedBookTitles.add(resolveBookTitle(bookId));
                }
                current++;
                sendBatchProgress(username, "IN_PROGRESS", "Extracted book " + current + " of " + total, null, current, total, null, null);
            }

            sendBatchProgress(username, "COMPLETED", "Scan completed.", null, total, total, importedBookTitles, failedBookTitles);

        } catch (Exception e) {
            log.error("Batch AI Search scan failed", e);
            sendBatchProgress(username, "FAILED", "Scan failed: " + e.getMessage(), e.getMessage(), 0, 0, null, null);
        } finally {
            scanInProgress.set(false);
            lastBatchProgress = null;
        }
    }


    public List<org.fable.repository.projection.MarkedBookProjection> getMarkedBooks() {
        return bookRepository.findMarkedBooksInfo();
    }

    public List<org.fable.repository.projection.EmbeddingStatsProjection> getEmbeddingStats(Long userId) {
        return bookRepository.getEmbeddingStats(userId);
    }

    public List<org.fable.repository.projection.EmbeddingStatsProjection> getEmbeddingStats(Long userId, Long libraryId) {
        return bookRepository.getEmbeddingStatsByLibraryId(userId, libraryId);
    }

    public Map<String, Object> getAiSearchStatsSummary(Long userId, Long libraryId) {
        List<org.fable.repository.projection.EmbeddingStatsProjection> modelStats;
        long totalChunks;
        long markedCount;
        long storedBytes;

        if (libraryId != null) {
            modelStats = bookRepository.getEmbeddingStatsByLibraryId(userId, libraryId);
            totalChunks = bookRepository.countTotalChunksByUserIdAndLibraryId(userId, libraryId);
            markedCount = bookRepository.countMarkedForAiSearchByLibraryId(libraryId);
            storedBytes = bookRepository.sumStoredBytesByUserIdAndLibraryId(userId, libraryId);
        } else {
            modelStats = bookRepository.getEmbeddingStats(userId);
            totalChunks = bookRepository.countTotalChunksByUserId(userId);
            markedCount = bookRepository.countMarkedForAiSearch();
            storedBytes = bookRepository.sumStoredBytesByUserId(userId);
        }

        long totalEmbeddedBooks = modelStats.stream()
                .mapToLong(org.fable.repository.projection.EmbeddingStatsProjection::getCount)
                .sum();

        return Map.of(
                "totalEmbeddedBooks", totalEmbeddedBooks,
                "totalChunks", totalChunks,
                "markedCount", markedCount,
                "modelStats", modelStats,
                "storedBytes", storedBytes
        );
    }

    public int deleteBookEmbeddings(List<Long> bookIds, Long userId) {
        if (bookIds == null || bookIds.isEmpty()) {
            throw new IllegalArgumentException("bookIds are required.");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId is required.");
        }
        int deleted = bookRepository.deleteBookEmbeddings(bookIds, userId);
        try {
            List<org.fable.model.dto.Book> books = bookService.getBooksByIds(new HashSet<>(bookIds), true);
            bookEventBroadcaster.broadcastBookBatchUpdateEvent(books);
        } catch (Exception e) {
            log.warn("Failed to broadcast book updates after deleting embeddings: {}", e.getMessage());
        }
        return deleted;
    }

    @org.springframework.transaction.annotation.Transactional
    public void markBooksForAiSearch(List<Long> bookIds, boolean marked) {
        if (bookIds == null || bookIds.isEmpty()) {
            return;
        }
        bookRepository.updateMarkedForAiSearch(bookIds, marked);
        try {
            List<org.fable.model.dto.Book> books = bookService.getBooksByIds(new HashSet<>(bookIds), true);
            bookEventBroadcaster.broadcastBookBatchUpdateEvent(books);
        } catch (Exception e) {
            log.warn("Failed to broadcast book updates after marking: {}", e.getMessage());
        }
    }

    private String resolveBookTitle(Long bookId) {
        String title = bookRepository.findBookTitleById(bookId);
        return title != null ? title : "Book #" + bookId;
    }

    @Async
    public void startScanMarkedAiSearchEmbeddings(Long userId, String username, boolean force) {
        try {
            scanMarkedAiSearchEmbeddings(userId, username, force, null);
        } catch (Exception e) {
            log.error("Async scan marked AI search embeddings failed", e);
        }
    }

    public void scanMarkedAiSearchEmbeddings(Long userId, String username, boolean force, String taskId) {
        if (!scanInProgress.compareAndSet(false, true)) {
            log.warn("AI Search scan already in progress");
            return;
        }

        try {
            sendBatchProgress(username, "STARTED", "Finding marked books to scan...", null, 0, 0, null, null);

            List<Long> markedBookIds = bookRepository.findBookIdsByMarkedForAiSearchTrue();

            List<String> importedBookTitles = new ArrayList<>();
            List<String> failedBookTitles = new ArrayList<>();

            int total = markedBookIds.size();
            if (total == 0) {
                sendBatchProgress(username, "COMPLETED", "No books are marked for AI Search.", null, 0, 0, null, null);
                return;
            }

            int current = 0;
            int scannedCount = 0;
            int skippedCount = 0;
            int errorCount = 0;

            sendBatchProgress(username, "IN_PROGRESS", "Starting extraction...", null, current, total, null, null);

            String activeModel = aiSearchHealthService.getStatus().getEmbeddingModel();
            
            // Pre-fetch embedding status for all marked books to optimize skipping
            List<org.fable.repository.projection.AiSearchBookStatusProjection> embeddedStatuses = 
                bookRepository.findBookIdsWithAiSearchEmbeddings(userId, markedBookIds);
            
            Map<Long, String> embeddedModelsMap = new java.util.HashMap<>();
            for (var status : embeddedStatuses) {
                embeddedModelsMap.put(status.getBookId(), status.getEmbeddingModel());
            }

            for (Long bookId : markedBookIds) {
                if (!scanInProgress.get() || (taskId != null && cancellationManager.isTaskCancelled(taskId))) {
                    String reason = (taskId != null && cancellationManager.isTaskCancelled(taskId)) ? "cancelled" : "manually stopped";
                    String stopMsg = String.format("Scan was %s. %d scanned, %d skipped, %d failed.", reason, scannedCount, skippedCount, errorCount);
                    sendBatchProgress(username, "STOPPED", stopMsg, null, current, total, null, null);
                    return;
                }
                
                try {
                    String existingModel = embeddedModelsMap.get(bookId);
                    if (!force && existingModel != null && existingModel.equals(activeModel)) {
                        log.info("Skipping embedding for book {} because it is already embedded with model {}", bookId, activeModel);
                        bookRepository.updateMarkedForAiSearch(List.of(bookId), false);
                        skippedCount++;
                        current++;
                        sendBatchProgress(username, "IN_PROGRESS", "Skipped already scanned book " + current + " of " + total, null, current, total, null, null);
                        continue;
                    }

                    String title = resolveBookTitle(bookId);
                    sendBatchProgress(username, "IN_PROGRESS", "Processing: " + title + " (book " + (current + 1) + " of " + total + ")", null, current, total, null, null);
                    int recoveryAttempt = 0;
                    while (true) {
                        try {
                            extractAndEmbedBookInternal(bookId, userId, username, true, current, total);
                            break;
                        } catch (Exception embedEx) {
                            if (networkResilienceService != null
                                    && NetworkErrorClassifier.isConnectivityError(embedEx)
                                    && recoveryAttempt < MAX_SIDECAR_RECOVERY_RETRIES_PER_BOOK) {
                                recoveryAttempt++;
                                final int currentSnapshot = current;
                                boolean recovered = networkResilienceService.waitForEndpointRecovery(
                                        taskId,
                                        appProperties.getAiSearch().getBaseUrl(),
                                        NetworkResilienceService.SIDECAR_MAX_OUTAGE_WAIT,
                                        pauseMsg -> sendBatchProgress(username, "IN_PROGRESS", pauseMsg, null, currentSnapshot, total, null, null));
                                if (recovered) {
                                    continue;
                                }
                                String outageMsg = "AI Search service remained unreachable; stopping scan so remaining marked books can be retried later.";
                                sendBatchProgress(username, "FAILED", outageMsg, embedEx.getMessage(), current, total, importedBookTitles, failedBookTitles);
                                throw new NetworkOutageExhaustedException(outageMsg, embedEx);
                            }
                            throw embedEx;
                        }
                    }
                    scannedCount++;
                    importedBookTitles.add(title);
                    // clear the flag
                    bookRepository.updateMarkedForAiSearch(List.of(bookId), false);
                } catch (CancellationException e) {
                    String stopMsg = String.format("Scan was cancelled. %d scanned, %d skipped, %d failed.", scannedCount, skippedCount, errorCount);
                    sendBatchProgress(username, "STOPPED", stopMsg, null, current, total, null, null);
                    return;
                } catch (NetworkOutageExhaustedException e) {
                    throw e;
                } catch (Exception e) {
                    errorCount++;
                    log.error("Failed to embed book {} during marked scan: {}", bookId, e.getMessage());
                    failedBookTitles.add(resolveBookTitle(bookId));
                }
                current++;
                sendBatchProgress(username, "IN_PROGRESS", "Extracted book " + current + " of " + total, null, current, total, null, null);
            }

            String completionMsg = String.format("Scan completed. %d books scanned, %d skipped, %d failed.", scannedCount, skippedCount, errorCount);
            sendBatchProgress(username, "COMPLETED", completionMsg, null, total, total, importedBookTitles, failedBookTitles);

            if (errorCount > 0 && errorCount == total) {
                throw new RuntimeException("All books failed to embed during scan: " + String.join(", ", failedBookTitles));
            }
        } catch (Exception e) {
            log.error("Marked AI Search scan failed", e);
            throw e;
        } finally {
            scanInProgress.set(false);
            lastBatchProgress = null;
        }
    }

    public Map<String, Object> embedBook(Long bookId, Long userId, List<Map<String, Object>> chunks, boolean append) {
        String baseUrl = appProperties.getAiSearch().getBaseUrl();
        RestClient restClient = buildRestClient();

        Map<String, Object> payload = Map.of(
                "bookId", bookId,
                "userId", userId,
                "chunks", chunks,
                "append", append
        );

        return postForMap(restClient, baseUrl + "/v1/embed", payload);
    }

    public Map<String, Object> search(String query, List<Long> bookIds, Long userId, List<Map<String, String>> chatHistory, boolean localOnly) {
        String baseUrl = appProperties.getAiSearch().getBaseUrl();
        // Use a shorter read timeout for interactive search queries than for
        // embedding jobs. A hung Python/Ollama search should fail in ~2 minutes
        // instead of holding the browser connection open for the 10-minute
        // embedding timeout, which is what caused the 14-minute UI hang.
        RestClient restClient = buildSearchRestClient();

        org.fable.model.dto.settings.AiSearchSettings settings = appSettingService.getAppSettings().getAiSearchSettings();

        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("query", query);
        payload.put("bookIds", bookIds != null ? bookIds : List.of());
        payload.put("userId", userId);
        payload.put("topK", settings.getTopK());
        payload.put("displayTopK", settings.getDisplayTopK());
        payload.put("similarityThreshold", settings.getSimilarityThreshold());
        payload.put("maxTokens", settings.getMaxTokens());
        payload.put("temperature", settings.getTemperature());
        payload.put("localOnly", localOnly);
        payload.put("matryoshkaDimensions", settings.getMatryoshkaDimensions());
        payload.put("hybridSearchEnabled", settings.isHybridSearchEnabled());
        payload.put("rrfK", settings.getRrfK());
        payload.put("rerankingEnabled", settings.isRerankingEnabled());
        payload.put("rerankerModel", settings.getRerankerModel());
        if (chatHistory != null) {
            payload.put("chatHistory", chatHistory);
        }

        try {
            Map<String, Object> result = postForMap(restClient, baseUrl + "/v1/search", payload);
            return result;
        } catch (Exception e) {
            log.error("AI Search query failed: {}", e.getMessage());
            Map<String, Object> errorResult = new java.util.HashMap<>();
            errorResult.put("query", query);
            errorResult.put("results", List.of());
            errorResult.put("answer", null);
            errorResult.put("error", "Could not reach the AI Search service: " + e.getMessage());
            errorResult.put("totalChunksSearched", 0);
            return errorResult;
        }
    }

    public Map<String, Object> getBookEmbeddingStatus(Long bookId, Long userId) {
        String baseUrl = appProperties.getAiSearch().getBaseUrl();
        RestClient restClient = buildRestClient();

        return getForMap(restClient, baseUrl + "/v1/book-embeddings/{bookId}?user_id={userId}", bookId, userId);
    }

    public Map<String, Object> getEmbedJobStatus(String jobId) {
        String baseUrl = appProperties.getAiSearch().getBaseUrl();
        RestClient restClient = buildRestClient();

        return getForMap(restClient, baseUrl + "/v1/embed-status/{jobId}", jobId);
    }

    /**
     * Extracts text from a book file (EPUB or PDF), chunks it, and sends to the AI Search
     * container for embedding. This is the primary user-facing "Embed for AI Search" flow.
     */
    @Async
    public void extractAndEmbedBook(Long bookId, Long userId, String username) {
        extractAndEmbedBookInternal(bookId, userId, username, false, 0, 1);
    }

    private void extractAndEmbedBookInternal(Long bookId, Long userId, String username, boolean isBatch, Integer current, Integer total) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book not found: " + bookId));

        BookFileEntity primaryFile = book.getPrimaryBookFile();
        if (primaryFile == null) {
            throw new IllegalArgumentException("Book has no files: " + bookId);
        }

        Path fullPath = book.getFullFilePath();
        if (fullPath == null) {
            throw new IllegalArgumentException("Cannot resolve file path for book: " + bookId);
        }

        File file = fullPath.toFile();
        if (!file.exists()) {
            throw new IllegalArgumentException("Book file not found on disk: " + fullPath);
        }

        if (!isBatch) {
            sendSearchProgress(username, "STARTED", "Extracting text from " + file.getName(), null);
        }

        try {
            if (primaryFile.getBookType() == BookFileType.EPUB) {
                extractEpubChunks(file, bookId, userId, username, isBatch, current, total);
            } else if (primaryFile.getBookType() == BookFileType.PDF) {
                extractPdfChunks(file, bookId, userId, username, isBatch, current, total);
            } else if (primaryFile.getBookType() == BookFileType.CBX) {
                extractCbxChunks(file, bookId, userId, username, isBatch, current, total);
            } else {
                throw new IllegalArgumentException("Unsupported book type for AI Search: " + primaryFile.getBookType());
            }

            String activeModel = aiSearchHealthService.getStatus().getEmbeddingModel();
            if (activeModel != null) {
                bookRepository.updateAiSearchEmbeddingModel(bookId, userId, activeModel);
            } else {
                log.warn("Active embedding model is null (AI Search service may be unreachable). Skipping embedding_model update for book {}.", bookId);
            }

            // Broadcast the update so the UI gets immediate updates
            // Build a minimal DTO directly from the already-loaded entity to avoid
            // MapStruct triggering lazy loads on @Lob fields (description) in @Async context.
            try {
                Book dto = Book.builder()
                    .id(book.getId())
                    .libraryId(book.getLibrary().getId())
                    .build();
                bookEventBroadcaster.broadcastBookUpdateEvent(dto);
            } catch (Exception broadcastEx) {
                log.warn("Failed to broadcast book update for embedded book {}: {}", bookId, broadcastEx.getMessage());
            }

            if (!isBatch) {
                sendSearchProgress(username, "COMPLETED", "Book embedded successfully", null);
            }
        } catch (Exception e) {
            log.error("Failed to extract or embed book {}: {}", bookId, e.getMessage(), e);
            if (!isBatch) {
                sendSearchProgress(username, "FAILED", "Extraction/Embedding failed", e.getMessage());
            }
            throw new RuntimeException("Extraction/Embedding failed", e);
        }
    }

    private void extractEpubChunks(File epubFile, Long bookId, Long userId, String username, boolean isBatch, Integer current, Integer total) throws Exception {
        int spineSize = EpubContentReader.getSpineSize(epubFile);
        List<Map<String, Object>> chunkBatch = new ArrayList<>();
        boolean isFirstBatch = true;

        // Build TOC title map for fallback when HTML headings are absent
        Map<String, String> tocTitleMap = EpubContentReader.getTocTitleMap(epubFile);

        for (int i = 0; i < spineSize; i++) {
            if (isBatch && !scanInProgress.get()) {
                log.info("AI Search scan stopped during EPUB extraction of book {}", bookId);
                return;
            }
            if (i % 5 == 0) {
                int percentage = (int)(((double)i / spineSize) * 100);
                if (!isBatch) {
                    sendSearchProgress(username, "IN_PROGRESS", "Embedding... " + percentage + "%", null);
                } else if (current != null && total != null) {
                    sendBatchProgress(username, "IN_PROGRESS",
                        "Embedding book " + (current + 1) + " of " + total + " (" + percentage + "%)",
                        null, current, total, null, null);
                }
            }

            String html = EpubContentReader.getSpineItemContent(epubFile, i);
            String text = Jsoup.parse(html).text();
            if (text.isBlank()) {
                continue;
            }

            // Get the spine item href for TOC lookup
            String spineHref = EpubContentReader.getSpineItemHref(epubFile, i);
            String tocTitle = null;
            if (spineHref != null && tocTitleMap != null) {
                // Normalize href for matching (strip leading path components)
                String normalizedHref = spineHref.replaceFirst("#.*$", "");
                if (normalizedHref.startsWith("/")) {
                    normalizedHref = normalizedHref.substring(1);
                }
                tocTitle = tocTitleMap.get(normalizedHref);
                // Try matching just the filename portion
                if (tocTitle == null && normalizedHref.contains("/")) {
                    String filename = normalizedHref.substring(normalizedHref.lastIndexOf('/') + 1);
                    for (Map.Entry<String, String> entry : tocTitleMap.entrySet()) {
                        if (entry.getKey().endsWith(filename)) {
                            tocTitle = entry.getValue();
                            break;
                        }
                    }
                }
            }

            // Extract ALL headings from this spine item and assign each chunk the nearest preceding heading
            List<Map.Entry<Integer, String>> headings = extractAllHeadingsFromHtml(html);
            List<String> textChunks = chunkText(text);
            List<Map<String, Object>> headedChunks = assignHeadingsToChunks(textChunks, text, headings, i + 1, tocTitle);
            chunkBatch.addAll(headedChunks);

            // Flush batch when it gets large
            while (chunkBatch.size() >= CHUNK_BATCH_SIZE) {
                List<Map<String, Object>> batch = new ArrayList<>(chunkBatch.subList(0, CHUNK_BATCH_SIZE));
                chunkBatch = new ArrayList<>(chunkBatch.subList(CHUNK_BATCH_SIZE, chunkBatch.size()));
                embedBook(bookId, userId, batch, !isFirstBatch);
                isFirstBatch = false;
            }
        }

        if (!chunkBatch.isEmpty()) {
            embedBook(bookId, userId, chunkBatch, !isFirstBatch);
            chunkBatch.clear();
        }
    }

    private void extractPdfChunks(File pdfFile, Long bookId, Long userId, String username, boolean isBatch, Integer current, Integer total) throws Exception {
        List<Map<String, Object>> chunkBatch = new ArrayList<>();
        boolean isFirstBatch = true;

        org.fable.model.dto.settings.AiSearchSettings settings = appSettingService.getAppSettings().getAiSearchSettings();
        if (settings == null) {
            settings = new org.fable.model.dto.settings.AiSearchSettings();
        }

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pageCount = document.getNumberOfPages();

            for (int page = 1; page <= pageCount; page++) {
                if (isBatch && !scanInProgress.get()) {
                    log.info("AI Search scan stopped during PDF extraction of book {}", bookId);
                    return;
                }
                if (page % 5 == 0) {
                    int percentage = (int)(((double)page / pageCount) * 100);
                    if (!isBatch) {
                        sendSearchProgress(username, "IN_PROGRESS", "Embedding... " + percentage + "%", null);
                    } else if (current != null && total != null) {
                        sendBatchProgress(username, "IN_PROGRESS",
                            "Embedding book " + (current + 1) + " of " + total + " (" + percentage + "%)",
                            null, current, total, null, null);
                    }
                }

                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);

                boolean needsOcr = false;
                if (text == null || text.isBlank()) {
                    needsOcr = true;
                } else if (settings.isOcrEnabled() && !settings.isOcrFallbackOnly()) {
                    needsOcr = true;
                } else if (settings.isOcrEnabled() && text.trim().length() < 50) {
                    needsOcr = true;
                }

                if (needsOcr && settings.isOcrEnabled()) {
                    String ocrText = performOcrOnPage(document, page - 1, settings.getOcrLanguage());
                    if (ocrText != null && !ocrText.isBlank()) {
                        text = ocrText;
                    }
                }

                if (text == null || text.isBlank()) {
                    continue;
                }

                String chapterTitle = extractHeadingFromPdfText(text);
                List<String> textChunks = chunkText(text);
                for (String tc : textChunks) {
                    Map<String, Object> chunk = new LinkedHashMap<>();
                    chunk.put("text", tc);
                    chunk.put("pageNumber", page);
                    chunk.put("chapterTitle", chapterTitle);
                    chunkBatch.add(chunk);

                    if (chunkBatch.size() >= CHUNK_BATCH_SIZE) {
                        embedBook(bookId, userId, chunkBatch, !isFirstBatch);
                        isFirstBatch = false;
                        chunkBatch.clear();
                    }
                }
            }
        }

        if (!chunkBatch.isEmpty()) {
            embedBook(bookId, userId, chunkBatch, !isFirstBatch);
            chunkBatch.clear();
        }
    }

    private void extractCbxChunks(File cbxFile, Long bookId, Long userId, String username,
                                   boolean isBatch, Integer current, Integer total) throws Exception {
        List<Integer> pages = cbxReaderService.getAvailablePages(bookId);
        int totalPages = pages.size();

        if (!isBatch) {
            sendSearchProgress(username, "IN_PROGRESS",
                String.format("Starting OCR for CBX book %d (%d pages)", bookId, totalPages), null);
        }

        org.fable.model.dto.settings.AiSearchSettings settings = appSettingService.getAppSettings().getAiSearchSettings();
        if (settings == null) {
            settings = new org.fable.model.dto.settings.AiSearchSettings();
        }
        String ocrLanguage = settings.getOcrLanguage() != null ? settings.getOcrLanguage() : "eng";

        String baseUrl = appProperties.getAiSearch().getBaseUrl();
        RestClient restClient = buildRestClient();
        String ocrUrl = baseUrl + "/v1/ocr";

        StringBuilder allText = new StringBuilder();
        int ocrFailures = 0;
        int MAX_OCR_FAILURES = 10;

        for (int pageNum : pages) {
            if (isBatch && !scanInProgress.get()) {
                log.info("AI Search scan stopped during CBX extraction of book {}", bookId);
                return;
            }

            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                cbxReaderService.streamPageImage(bookId, null, pageNum, baos);
                byte[] imageBytes = baos.toByteArray();

                if (imageBytes.length > MAX_CBX_IMAGE_BYTES) {
                    byte[] compressed = compressImageForOcr(imageBytes);
                    if (compressed != null && compressed.length > 0) {
                        imageBytes = compressed;
                    }
                }

                String base64Image = Base64.getEncoder().encodeToString(imageBytes);

                Map<String, Object> ocrPayload = new java.util.HashMap<>();
                ocrPayload.put("image", base64Image);
                ocrPayload.put("lang", ocrLanguage);

                Map<String, Object> ocrResult = postForMap(restClient, ocrUrl, ocrPayload);
                String pageText = (String) ocrResult.getOrDefault("text", "");

                if (pageText != null && !pageText.isBlank()) {
                    allText.append("[Page ").append(pageNum).append("]\n");
                    allText.append(pageText.trim()).append("\n\n");
                }

                ocrFailures = 0;

                if (pageNum % 10 == 0) {
                    int percentage = (int)(((double)pageNum / totalPages) * 100);
                    if (!isBatch) {
                        sendSearchProgress(username, "IN_PROGRESS",
                            String.format("OCR progress: page %d/%d (%d%%)", pageNum, totalPages, percentage), null);
                    } else if (current != null && total != null) {
                        sendBatchProgress(username, "IN_PROGRESS",
                            String.format("Embedding book %d of %d: OCR page %d/%d (%d%%)",
                                current + 1, total, pageNum, totalPages, percentage),
                            null, current, total, null, null);
                    }
                }

            } catch (Exception e) {
                log.warn("OCR failed for book {} page {}: {}", bookId, pageNum, e.getMessage());
                ocrFailures++;
                if (ocrFailures >= MAX_OCR_FAILURES) {
                    throw new RuntimeException(
                        String.format("OCR failed for %d consecutive pages, aborting book %d",
                            MAX_OCR_FAILURES, bookId));
                }
            }
        }

        String fullText = allText.toString().trim();
        if (fullText.isEmpty()) {
            if (!isBatch) {
                sendSearchProgress(username, "COMPLETED",
                    String.format("No text extracted from CBX book %d after OCR of %d pages",
                        bookId, totalPages), null);
            }
            return;
        }

        List<String> textChunks = chunkText(fullText);
        List<Map<String, Object>> chunkBatch = new ArrayList<>();
        boolean isFirstBatch = true;

        // Map each chunk back to the page it came from. The OCR text prefixes each page
        // with "[Page N]\n", so we can recover the page number as we walk through chunks.
        int currentPage = pages.isEmpty() ? 0 : pages.get(0);
        int pageMarkerIndex = 0;
        int textOffset = 0;

        for (int i = 0; i < textChunks.size(); i++) {
            String chunkText = textChunks.get(i);
            // Advance the page marker cursor to the page that contains the start of this chunk.
            while (pageMarkerIndex < pages.size()) {
                int pageNum = pages.get(pageMarkerIndex);
                String marker = String.format("[Page %d]", pageNum);
                int markerPos = fullText.indexOf(marker, textOffset);
                if (markerPos == -1) {
                    // No further markers; keep current page.
                    break;
                }
                if (markerPos <= textOffset) {
                    currentPage = pageNum;
                    pageMarkerIndex++;
                } else {
                    break;
                }
            }

            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("text", chunkText);
            chunk.put("pageNumber", currentPage > 0 ? currentPage : null);
            chunk.put("chapterTitle", null);
            chunkBatch.add(chunk);
            textOffset += chunkText.length();

            if (chunkBatch.size() >= CHUNK_BATCH_SIZE) {
                embedBook(bookId, userId, chunkBatch, !isFirstBatch);
                isFirstBatch = false;
                chunkBatch.clear();
            }
        }

        if (!chunkBatch.isEmpty()) {
            embedBook(bookId, userId, chunkBatch, !isFirstBatch);
            chunkBatch.clear();
        }

        if (!isBatch) {
            sendSearchProgress(username, "COMPLETED",
                String.format("Completed CBX embedding for book %d: %d chunks from %d pages",
                    bookId, textChunks.size(), totalPages), null);
        }
    }

    private String performOcrOnPage(PDDocument document, int pageIndex, String language) {
        try {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage bufferedImage = renderer.renderImageWithDPI(pageIndex, 150, ImageType.RGB);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "jpg", baos);
            byte[] imageBytes = baos.toByteArray();
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            String baseUrl = appProperties.getAiSearch().getBaseUrl();
            RestClient restClient = buildRestClient();

            Map<String, Object> payload = Map.of(
                    "image", base64Image,
                    "lang", language != null ? language : "eng"
            );

            Map<String, Object> result = postForMap(restClient, baseUrl + "/v1/ocr", payload);
            if (result != null && result.containsKey("text")) {
                return (String) result.get("text");
            }
        } catch (Exception e) {
            log.error("Failed to perform OCR on page index {}: {}", pageIndex, e.getMessage());
        }
        return null;
    }

    /**
     * Extracts ALL headings (h1-h6) from EPUB HTML content with their text positions.
     * Returns a list of (characterOffset, headingTitle) pairs in document order.
     * This allows assigning each chunk the nearest preceding heading.
     */
    private List<Map.Entry<Integer, String>> extractAllHeadingsFromHtml(String html) {
        List<Map.Entry<Integer, String>> headings = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return headings;
        }
        org.jsoup.nodes.Document doc = Jsoup.parse(html);
        String fullText = doc.text();
        for (int level = 1; level <= 6; level++) {
            for (org.jsoup.nodes.Element heading : doc.select("h" + level)) {
                String title = heading.text().trim();
                if (!title.isBlank() && title.length() <= 200) {
                    // Find the character offset of this heading's text within the full text
                    int offset = fullText.indexOf(title);
                    if (offset >= 0) {
                        headings.add(new java.util.AbstractMap.SimpleEntry<>(offset, title));
                    }
                }
            }
        }
        // Sort by position in document
        headings.sort(Map.Entry.comparingByKey());
        return headings;
    }

    /**
     * Assigns the nearest preceding heading to each chunk based on text position.
     * For the first chunk, uses the first heading if it appears early enough.
     */
    private List<Map<String, Object>> assignHeadingsToChunks(
            List<String> textChunks, String fullText, List<Map.Entry<Integer, String>> headings,
            int pageNumber, String tocTitle) {
        List<Map<String, Object>> result = new ArrayList<>();
        int searchFrom = 0;
        for (String chunk : textChunks) {
            int chunkPos = fullText.indexOf(chunk, searchFrom);
            if (chunkPos < 0) {
                chunkPos = searchFrom;
            }
            searchFrom = chunkPos + chunk.length();

            // Find the last heading that appears before or at this chunk's position
            String assignedHeading = null;
            for (Map.Entry<Integer, String> heading : headings) {
                if (heading.getKey() <= chunkPos + 50) { // 50 char tolerance
                    assignedHeading = heading.getValue();
                } else {
                    break;
                }
            }

            // Fall back to TOC title if no HTML heading was found
            if (assignedHeading == null && tocTitle != null) {
                assignedHeading = tocTitle;
            }

            Map<String, Object> chunkMap = new LinkedHashMap<>();
            chunkMap.put("text", chunk);
            chunkMap.put("pageNumber", pageNumber);
            chunkMap.put("chapterTitle", assignedHeading);
            result.add(chunkMap);
        }
        return result;
    }

    /**
     * Extracts a likely heading from the first line(s) of PDF page text.
     * Heuristic: if the first non-blank line is short (<=120 chars) and
     * doesn't look like a regular sentence, treat it as a heading.
     */
    private String extractHeadingFromPdfText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] lines = text.split("\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // Heading heuristic: short line, not ending with period, not all uppercase noise
            if (trimmed.length() <= 120 && trimmed.length() >= 3) {
                // Skip lines that look like page numbers or metadata
                if (trimmed.matches("^\\d+$")) {
                    continue;
                }
                // Skip lines that are just a single word in all caps (often author name)
                if (trimmed.equals(trimmed.toUpperCase()) && trimmed.length() < 30) {
                    continue;
                }
                return trimmed;
            }
            break; // Only check the first non-blank line
        }
        return null;
    }

    private byte[] compressImageForOcr(byte[] source) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(source));
            if (original == null) {
                return source;
            }

            int width = original.getWidth();
            int height = original.getHeight();
            int maxDimension = Math.max(width, height);

            BufferedImage working = original;
            if (maxDimension > 3000) {
                double scale = 3000.0 / maxDimension;
                int targetW = Math.max(1, (int) Math.round(width * scale));
                int targetH = Math.max(1, (int) Math.round(height * scale));
                Image scaled = original.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
                working = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = working.createGraphics();
                g.drawImage(scaled, 0, 0, null);
                g.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(working, "JPEG", out);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("Failed to compress CBX image for OCR, using original: {}", e.getMessage());
            return source;
        }
    }

    private List<String> chunkText(String text) {
        int chunkSizeSetting = 1500;
        int chunkOverlapSetting = 100;
        boolean semanticChunking = false;
        double semanticThreshold = 0.3;
        try {
            org.fable.model.dto.settings.AiSearchSettings settings = appSettingService.getAppSettings().getAiSearchSettings();
            if (settings != null) {
                chunkSizeSetting = settings.getChunkSize() > 0 ? settings.getChunkSize() : 1500;
                chunkOverlapSetting = settings.getChunkOverlap() >= 0 ? settings.getChunkOverlap() : 100;
                semanticChunking = settings.isSemanticChunkingEnabled();
                semanticThreshold = settings.getSemanticChunkingThreshold() > 0 ? settings.getSemanticChunkingThreshold() : 0.3;
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve chunk settings dynamically, falling back to defaults: {}", e.getMessage());
        }

        if (semanticChunking) {
            return chunkTextSemantic(text, chunkSizeSetting, chunkOverlapSetting, semanticThreshold);
        }
        return chunkTextFixed(text, chunkSizeSetting, chunkOverlapSetting);
    }

    /**
     * Fixed-size chunking with sentence-boundary awareness (original behavior).
     * Splits text at sentence boundaries, grouping sentences until chunkSize is reached.
     * Carries over recent sentences for overlap between chunks.
     */
    private List<String> chunkTextFixed(String text, int chunkSizeSetting, int chunkOverlapSetting) {
        List<String> chunks = new ArrayList<>();
        String cleaned = text.replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return chunks;
        }

        BreakIterator boundary = BreakIterator.getSentenceInstance();
        boundary.setText(cleaned);

        int start = boundary.first();
        int end = boundary.next();

        StringBuilder currentChunk = new StringBuilder();
        java.util.Deque<String> recentSentences = new java.util.ArrayDeque<>();

        while (end != BreakIterator.DONE) {
            String sentence = cleaned.substring(start, end).trim();
            if (!sentence.isEmpty()) {
                if (currentChunk.length() + sentence.length() > chunkSizeSetting && currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                    int overlapChars = 0;
                    java.util.List<String> overlapSentences = new java.util.ArrayList<>();
                    var it = recentSentences.descendingIterator();
                    while (it.hasNext() && overlapChars < chunkOverlapSetting) {
                        String s = it.next();
                        overlapSentences.add(s);
                        overlapChars += s.length() + 1;
                    }
                    java.util.Collections.reverse(overlapSentences);
                    for (String s : overlapSentences) {
                        currentChunk.append(s).append(" ");
                    }
                }
                currentChunk.append(sentence).append(" ");
                recentSentences.addLast(sentence);
                int totalLen = 0;
                while (recentSentences.size() > 1) {
                    totalLen = recentSentences.stream().mapToInt(String::length).sum() + recentSentences.size();
                    if (totalLen - recentSentences.peekFirst().length() > chunkOverlapSetting) {
                        recentSentences.removeFirst();
                    } else {
                        break;
                    }
                }
            }
            start = end;
            end = boundary.next();
        }

        if (currentChunk.length() > 0) {
            String finalChunk = currentChunk.toString().trim();
            if (!finalChunk.isEmpty()) {
                chunks.add(finalChunk);
            }
        }

        return chunks;
    }

    /**
     * Semantic chunking that detects topic shifts via paragraph boundaries and
     * word-overlap drops between consecutive sentences. Produces more coherent,
     * self-contained chunks than fixed-size chunking.
     *
     * Algorithm:
     * 1. Split text into paragraphs (double newlines).
     * 2. Within each paragraph, split into sentences.
     * 3. Group sentences into chunks. A new chunk starts when:
     *    a. A paragraph boundary is crossed (strong topic shift), OR
     *    b. Word overlap between consecutive sentences drops below semanticThreshold, OR
     *    c. The current chunk exceeds chunkSizeSetting (hard max).
     * 4. Apply chunkOverlapSetting by carrying the last sentence(s) into the next chunk.
     */
    private List<String> chunkTextSemantic(String text, int chunkSizeSetting, int chunkOverlapSetting, double semanticThreshold) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        // Step 1: Split into paragraphs (double newlines or single newlines that act as paragraph breaks)
        String[] paragraphs = text.split("\\n\\s*\\n");
        if (paragraphs.length == 0) {
            return chunks;
        }

        StringBuilder currentChunk = new StringBuilder();
        // Track the last sentence for overlap carry-over
        String lastSentence = null;

        for (String paragraph : paragraphs) {
            String cleaned = paragraph.replaceAll("\\s+", " ").trim();
            if (cleaned.isEmpty()) {
                continue;
            }

            // Step 2: Split paragraph into sentences
            List<String> sentences = splitSentences(cleaned);
            if (sentences.isEmpty()) {
                continue;
            }

            // Step 3: Group sentences into chunks
            for (int i = 0; i < sentences.size(); i++) {
                String sentence = sentences.get(i);
                boolean isFirstInParagraph = (i == 0);
                boolean isLastInParagraph = (i == sentences.size() - 1);

                // Determine if we should start a new chunk
                boolean startNewChunk = false;

                if (currentChunk.length() > 0) {
                    // Check hard max size
                    if (currentChunk.length() + sentence.length() > chunkSizeSetting) {
                        startNewChunk = true;
                    }
                    // Check paragraph boundary (strong topic shift)
                    else if (isFirstInParagraph && lastSentence != null) {
                        double overlap = computeWordOverlap(lastSentence, sentence);
                        if (overlap < semanticThreshold) {
                            startNewChunk = true;
                        }
                    }
                    // Check word-overlap drop between consecutive sentences
                    else if (!isFirstInParagraph && lastSentence != null) {
                        double overlap = computeWordOverlap(lastSentence, sentence);
                        if (overlap < semanticThreshold) {
                            startNewChunk = true;
                        }
                    }
                }

                if (startNewChunk && currentChunk.length() > 0) {
                    // Finalize current chunk
                    chunks.add(currentChunk.toString().trim());

                    // Carry over overlap: include the last sentence from the previous chunk
                    currentChunk = new StringBuilder();
                    if (lastSentence != null && chunkOverlapSetting > 0) {
                        currentChunk.append(lastSentence).append(" ");
                    }
                }

                currentChunk.append(sentence).append(" ");
                lastSentence = sentence;
            }
        }

        // Finalize last chunk
        if (currentChunk.length() > 0) {
            String finalChunk = currentChunk.toString().trim();
            if (!finalChunk.isEmpty()) {
                chunks.add(finalChunk);
            }
        }

        // Safety valve: if semantic chunking produced zero chunks (shouldn't happen),
        // fall back to fixed-size chunking
        if (chunks.isEmpty()) {
            log.warn("Semantic chunking produced zero chunks, falling back to fixed-size chunking");
            return chunkTextFixed(text, chunkSizeSetting, chunkOverlapSetting);
        }

        return chunks;
    }

    /**
     * Split text into sentences using BreakIterator with fallback regex.
     */
    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sentences;
        }

        BreakIterator boundary = BreakIterator.getSentenceInstance();
        boundary.setText(text);

        int start = boundary.first();
        int end = boundary.next();

        while (end != BreakIterator.DONE) {
            String sentence = text.substring(start, end).trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
            start = end;
            end = boundary.next();
        }

        return sentences;
    }

    /**
     * Compute Jaccard word overlap between two sentences.
     * Returns a value between 0.0 (no shared words) and 1.0 (identical word sets).
     */
    private double computeWordOverlap(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return 0.0;
        }

        java.util.Set<String> wordsA = extractContentWords(a);
        java.util.Set<String> wordsB = extractContentWords(b);

        if (wordsA.isEmpty() || wordsB.isEmpty()) {
            return 0.0;
        }

        java.util.Set<String> intersection = new java.util.HashSet<>(wordsA);
        intersection.retainAll(wordsB);

        java.util.Set<String> union = new java.util.HashSet<>(wordsA);
        union.addAll(wordsB);

        return (double) intersection.size() / (double) union.size();
    }

    /**
     * Extract lowercase content words (3+ chars, no stopwords) from a sentence.
     */
    private java.util.Set<String> extractContentWords(String text) {
        java.util.Set<String> words = new java.util.HashSet<>();
        String[] tokens = text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").split("\\s+");
        for (String token : tokens) {
            if (token.length() >= 3 && !STOPWORDS.contains(token)) {
                words.add(token);
            }
        }
        return words;
    }

    private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
        "the", "and", "for", "that", "this", "with", "from", "have", "are",
        "was", "not", "but", "you", "all", "can", "has", "had", "her", "his",
        "its", "our", "out", "some", "than", "then", "them", "these", "they",
        "what", "when", "will", "your", "about", "also", "been", "into", "like",
        "more", "only", "other", "over", "such", "very", "which",
        "there", "their", "were", "would", "could", "should", "each", "every",
        "just", "most", "because", "while", "after", "before", "between"
    );

    public void sendSearchProgress(String username, String event, String message, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "SINGLE");
        payload.put("event", event);
        payload.put("message", message);
        if (error != null) {
            payload.put("error", error);
        }
        notificationService.sendMessageToUser(username, Topic.AI_SEARCH_PROGRESS, payload);
    }

    public void sendBatchProgress(String username, String event, String message, String error, int current, int total,
                                   List<String> importedBooks, List<String> failedBooks) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "BATCH");
        payload.put("event", event);
        payload.put("message", message);
        payload.put("current", current);
        payload.put("total", total);
        if (error != null) {
            payload.put("error", error);
        }
        if (importedBooks != null && !importedBooks.isEmpty()) {
            payload.put("importedBooks", importedBooks);
        }
        if (failedBooks != null && !failedBooks.isEmpty()) {
            payload.put("failedBooks", failedBooks);
        }
        if ("STARTED".equals(event) || "IN_PROGRESS".equals(event)) {
            this.lastBatchProgress = Map.copyOf(payload);
        } else {
            this.lastBatchProgress = null;
        }
        notificationService.sendMessageToUser(username, Topic.AI_SEARCH_PROGRESS, payload);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postForMap(RestClient restClient, String uri, Object body) {
        byte[] bytes = restClient.post()
                .uri(uri)
                .body(body)
                .exchange((request, response) -> response.getBody().readAllBytes());
        if (bytes == null || bytes.length == 0) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(bytes, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse AI Search response: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getForMap(RestClient restClient, String uri, Object... uriVariables) {
        byte[] bytes = restClient.get()
                .uri(uri, uriVariables)
                .exchange((request, response) -> response.getBody().readAllBytes());
        if (bytes == null || bytes.length == 0) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(bytes, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse AI Search response: " + e.getMessage(), e);
        }
    }

    private RestClient buildRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(appProperties.getAiSearch().getConnectTimeoutMs());
        factory.setReadTimeout(appProperties.getAiSearch().getReadTimeoutMs());
        return applyAiSearchAuth(RestClient.builder().requestFactory(factory)).build();
    }

    /**
     * RestClient for interactive /v1/search queries.
     *
     * Uses a 330s read timeout — 30s longer than the Python LLM call timeout
     * (300s) so that Python fails first and returns a graceful RAW fallback
     * instead of the Java layer timing out and showing "Could not reach the
     * AI Search service". The 10-minute embedding timeout in
     * {@link #buildRestClient()} is intentionally separate.
     */
    private RestClient buildSearchRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(appProperties.getAiSearch().getConnectTimeoutMs());
        factory.setReadTimeout(330_000);
        return applyAiSearchAuth(RestClient.builder().requestFactory(factory)).build();
    }

    private RestClient.Builder applyAiSearchAuth(RestClient.Builder builder) {
        String secret = appProperties.getAiSearch().getSharedSecret();
        if (secret != null && !secret.isBlank()) {
            builder.defaultHeader(AiSearchAuthHeaders.HEADER_NAME, secret);
        }
        return builder;
    }
}
