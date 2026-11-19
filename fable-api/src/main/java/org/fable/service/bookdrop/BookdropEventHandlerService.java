package org.fable.service.bookdrop;

import org.fable.model.BookDropFileEvent;
import org.fable.model.entity.BookdropFileEntity;
import org.fable.model.enums.BookFileExtension;
import org.fable.repository.BookdropFileRepository;
import org.fable.service.appsettings.AppSettingService;
import org.fable.service.network.TransientProviderException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Service
public class BookdropEventHandlerService {

    private final BookdropFileRepository bookdropFileRepository;
    private final BookdropNotificationService bookdropNotificationService;
    private final AppSettingService appSettingService;
    private final BookdropMetadataService bookdropMetadataService;
    private final BookdropInboxService bookdropInboxService;
    private final TaskScheduler taskScheduler;

    private static final long STABILITY_CHECK_INTERVAL_MS = 500;
    private static final int STABILITY_REQUIRED_CHECKS = 3;
    private static final long STABILITY_MAX_WAIT_MS = 30_000;
    private static final long[] RETRY_DELAYS_MS = {30_000L, 120_000L, 300_000L, 900_000L};

    private final BlockingQueue<BookDropFileEvent> fileQueue = new LinkedBlockingQueue<>();
    private final Set<Long> scheduledRetries = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;
    private Thread workerThread;

    @Autowired
    public BookdropEventHandlerService(
            BookdropFileRepository bookdropFileRepository,
            BookdropNotificationService bookdropNotificationService,
            AppSettingService appSettingService,
            BookdropMetadataService bookdropMetadataService,
            BookdropInboxService bookdropInboxService,
            TaskScheduler taskScheduler) {
        this.bookdropFileRepository = bookdropFileRepository;
        this.bookdropNotificationService = bookdropNotificationService;
        this.appSettingService = appSettingService;
        this.bookdropMetadataService = bookdropMetadataService;
        this.bookdropInboxService = bookdropInboxService;
        this.taskScheduler = taskScheduler;
    }

    public BookdropEventHandlerService(
            BookdropFileRepository bookdropFileRepository,
            BookdropNotificationService bookdropNotificationService,
            AppSettingService appSettingService,
            BookdropMetadataService bookdropMetadataService,
            BookdropInboxService bookdropInboxService) {
        this(bookdropFileRepository, bookdropNotificationService, appSettingService,
                bookdropMetadataService, bookdropInboxService, null);
    }

    @PostConstruct
    public void init() {
        workerThread = new Thread(this::processQueue, "BookdropFileProcessor");
        workerThread.start();
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    public void enqueueFile(Path file, WatchEvent.Kind<?> kind) {
        BookDropFileEvent event = new BookDropFileEvent(file, kind);
        if (!fileQueue.contains(event)) {
            fileQueue.offer(event);
        }
    }

    private void processQueue() {
        while (running) {
            try {
                processFile(fileQueue.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("File processing thread interrupted, shutting down.");
            }
        }
    }

    public void processFile(BookDropFileEvent event) {
        Path file = event.getFile();
        WatchEvent.Kind<?> kind = event.getKind();

        if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            try {
                if (!Files.exists(file)) {
                    log.warn("File does not exist, ignoring: {}", file);
                    return;
                }

                if (Files.isDirectory(file)) {
                    log.info("New folder detected in bookdrop, ignoring: {}", file);
                    return;
                }

                String filePath = file.toAbsolutePath().toString();
                String fileName = file.getFileName().toString();

                if (BookFileExtension.fromFileName(fileName).isEmpty()) {
                    log.info("Unsupported file type detected, ignoring file: {}", fileName);
                    return;
                }

                if (bookdropFileRepository.findByFilePath(filePath).isPresent()) {
                    log.info("File already exists in Bookdrop and is pending review or acceptance: {}", filePath);
                    return;
                }

                if (!waitForFileStability(file)) {
                    log.warn("File did not stabilize within timeout, skipping: {}", file);
                    return;
                }

                log.info("Handling new bookdrop file: {}", file);

                int queueSize = fileQueue.size();

                BookdropFileEntity bookdropFileEntity = BookdropFileEntity.builder()
                        .filePath(filePath)
                        .fileName(fileName)
                        .fileSize(Files.size(file))
                        .ownerUserId(bookdropInboxService.resolveOwnerUserId(file).orElse(null))
                        .status(BookdropFileEntity.Status.PENDING_REVIEW)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

                bookdropFileEntity = bookdropFileRepository.save(bookdropFileEntity);

                if (appSettingService.getAppSettings().isMetadataDownloadOnBookdrop()) {
                    bookdropMetadataService.attachInitialMetadata(bookdropFileEntity.getId());
                    try {
                        bookdropMetadataService.attachFetchedMetadata(bookdropFileEntity.getId());
                    } catch (TransientProviderException e) {
                        log.warn("Transient network failure fetching metadata for bookdrop file {} ({}); scheduling deferred retry: {}",
                                bookdropFileEntity.getId(), bookdropFileEntity.getFileName(), e.getMessage());
                        scheduleMetadataFetchRetry(bookdropFileEntity.getId(), 0);
                    }
                } else {
                    bookdropMetadataService.attachInitialMetadata(bookdropFileEntity.getId());
                    log.info("Metadata download is disabled. Only initial metadata extracted for file: {}", bookdropFileEntity.getFileName());
                }

                bookdropNotificationService.sendBookdropFileSummaryNotification();

                if (fileQueue.isEmpty()) {
                } else {
                }

            } catch (Exception e) {
                log.error("Error handling bookdrop file: {}", file, e);
            }

        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            String deletedPath = file.toAbsolutePath().toString();
            log.info("Detected deletion event: {}", deletedPath);

            int deletedCount = bookdropFileRepository.deleteAllByFilePathStartingWith(deletedPath);
            log.info("Deleted {} BookdropFile record(s) from database matching path: {}", deletedCount, deletedPath);

            bookdropNotificationService.sendBookdropFileSummaryNotification();
        }
    }

    private boolean waitForFileStability(Path file) {
        long startTime = System.currentTimeMillis();
        long lastSize = -1;
        int stableCount = 0;

        while (System.currentTimeMillis() - startTime < STABILITY_MAX_WAIT_MS) {
            try {
                if (!Files.exists(file)) {
                    return false;
                }

                long currentSize = Files.size(file);

                if (currentSize == lastSize && currentSize > 0) {
                    stableCount++;
                    if (stableCount >= STABILITY_REQUIRED_CHECKS) {
                        return true;
                    }
                } else {
                    stableCount = 0;
                }

                lastSize = currentSize;
                Thread.sleep(STABILITY_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException e) {
                log.warn("Error checking file size for stability: {}", file, e);
                return false;
            }
        }

        log.warn("File size did not stabilize after {}ms: {}", STABILITY_MAX_WAIT_MS, file);
        return false;
    }

    void scheduleMetadataFetchRetry(Long bookdropFileId, int attemptIndex) {
        if (taskScheduler == null || !running || bookdropFileId == null) {
            return;
        }
        if (attemptIndex >= RETRY_DELAYS_MS.length) {
            scheduledRetries.remove(bookdropFileId);
            return;
        }
        if (attemptIndex == 0 && !scheduledRetries.add(bookdropFileId)) {
            log.debug("Deferred metadata retry already scheduled for bookdrop file {}", bookdropFileId);
            return;
        }
        long delayMs = RETRY_DELAYS_MS[attemptIndex];
        log.info("Scheduling deferred Bookdrop metadata retry {}/{} for file {} in {}s",
                attemptIndex + 1, RETRY_DELAYS_MS.length, bookdropFileId, delayMs / 1000);
        taskScheduler.schedule(
                () -> runDeferredMetadataFetchRetry(bookdropFileId, attemptIndex),
                Instant.now().plusMillis(delayMs));
    }

    void runDeferredMetadataFetchRetry(Long bookdropFileId, int attemptIndex) {
        if (!running || bookdropFileId == null) {
            if (bookdropFileId != null) {
                scheduledRetries.remove(bookdropFileId);
            }
            return;
        }
        Optional<BookdropFileEntity> existingOpt = bookdropFileRepository.findById(bookdropFileId);
        if (existingOpt.isEmpty()) {
            scheduledRetries.remove(bookdropFileId);
            return;
        }
        BookdropFileEntity existing = existingOpt.get();
        if (existing.getStatus() != BookdropFileEntity.Status.PENDING_REVIEW
                || (existing.getFetchedMetadata() != null && !existing.getFetchedMetadata().isBlank())) {
            log.debug("Skipping deferred Bookdrop metadata retry for file {} (status={}, alreadyFetched={})",
                    bookdropFileId, existing.getStatus(), existing.getFetchedMetadata() != null);
            scheduledRetries.remove(bookdropFileId);
            return;
        }

        try {
            bookdropMetadataService.attachFetchedMetadata(bookdropFileId);
            scheduledRetries.remove(bookdropFileId);
            bookdropNotificationService.sendBookdropFileSummaryNotification();
            log.info("Deferred Bookdrop metadata retry succeeded for file {} on attempt {}", bookdropFileId, attemptIndex + 1);
        } catch (TransientProviderException e) {
            if (attemptIndex + 1 < RETRY_DELAYS_MS.length) {
                log.warn("Deferred Bookdrop metadata retry {} failed transiently for file {}: {}",
                        attemptIndex + 1, bookdropFileId, e.getMessage());
                scheduleMetadataFetchRetry(bookdropFileId, attemptIndex + 1);
            } else {
                scheduledRetries.remove(bookdropFileId);
                log.warn("Exhausted {} deferred metadata fetch retries for bookdrop file {}; file remains PENDING_REVIEW.",
                        RETRY_DELAYS_MS.length, bookdropFileId);
            }
        } catch (Exception e) {
            scheduledRetries.remove(bookdropFileId);
            log.error("Deferred Bookdrop metadata retry failed permanently for file {}: {}", bookdropFileId, e.getMessage());
        }
    }
}