package org.fable.service.metadata;

import lombok.extern.slf4j.Slf4j;
import org.fable.config.security.service.AuthenticationService;
import org.fable.exception.ApiError;
import org.fable.mapper.BookMapper;
import org.fable.model.MetadataUpdateContext;
import org.fable.model.MetadataUpdateWrapper;
import org.fable.model.dto.*;
import org.fable.model.dto.request.FetchMetadataRequest;
import org.fable.model.dto.request.MetadataRefreshOptions;
import org.fable.model.dto.request.MetadataRefreshRequest;
import org.fable.model.dto.settings.AppSettings;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.ComicMetadataEntity;
import org.fable.model.entity.LibraryEntity;
import org.fable.model.entity.MetadataFetchJobEntity;
import org.fable.model.entity.MetadataFetchProposalEntity;
import org.fable.model.enums.FetchedMetadataProposalStatus;
import org.fable.model.enums.MetadataFetchTaskStatus;
import org.fable.model.enums.MetadataProvider;
import org.fable.model.enums.MetadataReplaceMode;
import org.fable.model.enums.TaskType;
import org.fable.model.websocket.TaskProgressPayload;
import org.fable.model.websocket.Topic;
import org.fable.repository.BookRepository;
import org.fable.repository.LibraryRepository;
import org.fable.repository.MetadataFetchJobRepository;
import org.fable.service.NotificationService;
import org.fable.service.appsettings.AppSettingService;
import org.fable.service.metadata.parser.BookParser;
import org.fable.service.network.NetworkOutageExhaustedException;
import org.fable.service.network.NetworkResilienceService;
import org.fable.service.network.TransientProviderException;
import org.fable.task.TaskStatus;
import org.fable.task.TaskCancellationManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.hibernate.Hibernate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.fable.util.BookUtils;

import static org.fable.model.enums.MetadataProvider.*;

@Slf4j
@Service
public class MetadataRefreshService {

    private static final Pattern ISSUE_RANGE_PATTERN = Pattern.compile("^(\\d+)\\s*-\\s*(\\d+)$");

    private record MetadataRefreshPlan(
            Book book,
            MetadataRefreshOptions refreshOptions,
            List<MetadataProvider> providers,
            boolean reviewMode
    ) {}

    record BookFetchOutcome(
            Map<MetadataProvider, BookMetadata> metadataMap,
            Map<MetadataProvider, TransientProviderException> transientFailures
    ) {
        boolean hasOnlyTransientFailures() {
            return metadataMap.isEmpty() && !transientFailures.isEmpty();
        }

        TransientProviderException firstTransientFailure() {
            return transientFailures.values().iterator().next();
        }
    }

    private final LibraryRepository libraryRepository;
    private final MetadataFetchJobRepository metadataFetchJobRepository;
    private final BookMapper bookMapper;
    private final BookMetadataUpdater bookMetadataUpdater;
    private final NotificationService notificationService;
    private final AppSettingService appSettingService;
    private final Map<MetadataProvider, BookParser> parserMap;
    private final ObjectMapper objectMapper;
    private final BookRepository bookRepository;
    private final PlatformTransactionManager transactionManager;
    private final AuthenticationService authenticationService;
    private final TaskCancellationManager cancellationManager;
    private final NetworkResilienceService networkResilienceService;

    @Autowired
    public MetadataRefreshService(
            LibraryRepository libraryRepository,
            MetadataFetchJobRepository metadataFetchJobRepository,
            BookMapper bookMapper,
            BookMetadataUpdater bookMetadataUpdater,
            NotificationService notificationService,
            AppSettingService appSettingService,
            Map<MetadataProvider, BookParser> parserMap,
            ObjectMapper objectMapper,
            BookRepository bookRepository,
            PlatformTransactionManager transactionManager,
            AuthenticationService authenticationService,
            TaskCancellationManager cancellationManager,
            NetworkResilienceService networkResilienceService) {
        this.libraryRepository = libraryRepository;
        this.metadataFetchJobRepository = metadataFetchJobRepository;
        this.bookMapper = bookMapper;
        this.bookMetadataUpdater = bookMetadataUpdater;
        this.notificationService = notificationService;
        this.appSettingService = appSettingService;
        this.parserMap = parserMap;
        this.objectMapper = objectMapper;
        this.bookRepository = bookRepository;
        this.transactionManager = transactionManager;
        this.authenticationService = authenticationService;
        this.cancellationManager = cancellationManager;
        this.networkResilienceService = networkResilienceService;
    }

    public MetadataRefreshService(
            LibraryRepository libraryRepository,
            MetadataFetchJobRepository metadataFetchJobRepository,
            BookMapper bookMapper,
            BookMetadataUpdater bookMetadataUpdater,
            NotificationService notificationService,
            AppSettingService appSettingService,
            Map<MetadataProvider, BookParser> parserMap,
            ObjectMapper objectMapper,
            BookRepository bookRepository,
            PlatformTransactionManager transactionManager,
            AuthenticationService authenticationService,
            TaskCancellationManager cancellationManager) {
        this(libraryRepository, metadataFetchJobRepository, bookMapper, bookMetadataUpdater,
                notificationService, appSettingService, parserMap, objectMapper, bookRepository,
                transactionManager, authenticationService, cancellationManager, null);
    }

    public TaskStatus getTerminalTaskStatus(String taskId) {
        if (taskId == null) {
            return TaskStatus.COMPLETED;
        }
        return metadataFetchJobRepository.findById(taskId)
                .map(MetadataFetchJobEntity::getStatus)
                .map(this::mapTaskStatus)
                .filter(status -> status != TaskStatus.IN_PROGRESS)
                .orElse(TaskStatus.COMPLETED);
    }


    public void refreshMetadata(MetadataRefreshRequest request, String jobId) {
        FableUser user = authenticationService.getAuthenticatedUser();
        Long userId = user != null ? user.getId() : null;
        MetadataFetchJobEntity task = null;
        int totalBooks = 0;
        int completedCount = 0;
        int failedCount = 0;
        int issueSequenceIndex = 0;
        boolean isReviewMode = false;
        try {
            AppSettings appSettings = appSettingService.getAppSettings();

            final boolean isLibraryRefresh = request.getRefreshType() == MetadataRefreshRequest.RefreshType.LIBRARY;
            final MetadataRefreshOptions requestRefreshOptions = request.getRefreshOptions();

            final boolean useRequestOptions = requestRefreshOptions != null;
            final MetadataRefreshOptions libraryRefreshOptions = !useRequestOptions && isLibraryRefresh ? resolveMetadataRefreshOptions(request.getLibraryId(), appSettings) : null;
            final List<MetadataProvider> fixedProviders = useRequestOptions ?
                    prepareProviders(requestRefreshOptions) :
                    (isLibraryRefresh ? prepareProviders(libraryRefreshOptions) : null);

            final Set<Long> actualBookIds = getBookEntities(request);
            totalBooks = actualBookIds.size();

            MetadataRefreshOptions reviewModeOptions = requestRefreshOptions != null ?
                    requestRefreshOptions :
                    (libraryRefreshOptions != null ? libraryRefreshOptions : appSettings.getDefaultMetadataRefreshOptions());
            isReviewMode = Boolean.TRUE.equals(reviewModeOptions.getReviewBeforeApply());

            task = MetadataFetchJobEntity.builder()
                    .taskId(jobId)
                    .userId(userId)
                    .status(MetadataFetchTaskStatus.IN_PROGRESS)
                    .startedAt(Instant.now())
                    .totalBooksCount(totalBooks)
                    .completedBooks(0)
                    .requestedBookIds(new ArrayList<>(actualBookIds))
                    .build();
            metadataFetchJobRepository.save(task);

            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

            for (Long bookId : actualBookIds) {
                if (cancellationManager.isTaskCancelled(jobId)) {
                    log.info("RefreshMetadataTask {} was cancelled, stopping execution", jobId);
                    cancelTask(task);
                    cancellationManager.clearCancellation(jobId);
                    return;
                }

                int finalCompletedCount = completedCount;
                int finalIssueSequenceIndex = issueSequenceIndex;
                MetadataRefreshPlan plan = null;
                try {
                    plan = prepareMetadataRefreshPlan(
                            txTemplate,
                            bookId,
                            task,
                            jobId,
                            finalCompletedCount,
                            totalBooks,
                            useRequestOptions,
                            requestRefreshOptions,
                            isLibraryRefresh,
                            libraryRefreshOptions,
                            fixedProviders,
                            appSettings
                    );

                    if (plan == null) {
                        completedCount++;
                        continue;
                    }

                    MetadataTaskContext.set(jobId, finalCompletedCount, totalBooks, plan.reviewMode());
                    MetadataRefreshPlan finalPlan = plan;
                    MetadataFetchJobEntity finalTask = task;
                    int finalTotalBooks = totalBooks;
                    BookFetchOutcome fetchOutcome;
                    if (networkResilienceService != null) {
                        fetchOutcome = networkResilienceService.executeWithOutageRecovery(
                                jobId,
                                finalPlan.providers(),
                                NetworkResilienceService.DEFAULT_MAX_OUTAGE_WAIT,
                                pauseMsg -> {
                                    updateTaskSnapshot(finalTask, finalCompletedCount, pauseMsg);
                                    sendBatchProgressNotification(
                                            jobId,
                                            finalCompletedCount,
                                            finalTotalBooks,
                                            pauseMsg,
                                            MetadataFetchTaskStatus.IN_PROGRESS,
                                            finalPlan.reviewMode()
                                    );
                                },
                                () -> {
                                    BookFetchOutcome outcome = fetchMetadataOutcomeForBook(
                                            finalPlan.providers(),
                                            finalPlan.book(),
                                            finalPlan.refreshOptions(),
                                            finalIssueSequenceIndex);
                                    if (outcome.hasOnlyTransientFailures()) {
                                        throw outcome.firstTransientFailure();
                                    }
                                    return outcome;
                                });
                    } else {
                        fetchOutcome = fetchMetadataOutcomeForBook(
                                plan.providers(),
                                plan.book(),
                                plan.refreshOptions(),
                                finalIssueSequenceIndex);
                        if (fetchOutcome.hasOnlyTransientFailures()) {
                            throw fetchOutcome.firstTransientFailure();
                        }
                    }
                    Map<MetadataProvider, BookMetadata> metadataMap = fetchOutcome.metadataMap();
                    if (plan.providers().contains(GoodReads)) {
                        delayGoodreadsRequest();
                    }

                    BookMetadata fetched = buildFetchMetadata(plan.book().getMetadata(), bookId, plan.refreshOptions(), metadataMap);
                    applyFetchedMetadata(txTemplate, bookId, task, jobId, finalCompletedCount, totalBooks, plan, fetched, metadataMap.isEmpty());
                } catch (CancellationException e) {
                    cancelTask(task);
                    cancellationManager.clearCancellation(jobId);
                    log.info("Metadata refresh task {} cancelled successfully", jobId);
                    return;
                } catch (NetworkOutageExhaustedException e) {
                    int remainingBooks = Math.max(1, totalBooks - completedCount);
                    String outageMessage = String.format(
                            "Paused for network outage and timed out waiting for connection. %d of %d books remain and can be resumed.",
                            remainingBooks,
                            totalBooks
                    );
                    log.warn("Metadata refresh task {} stopping cleanly after network outage timeout ({} remaining)", jobId, remainingBooks);
                    failTask(jobId, task, completedCount, totalBooks, outageMessage, isReviewMode);
                    cancellationManager.clearCancellation(jobId);
                    return;
                } catch (Exception e) {
                    failedCount++;
                    String bookIdentifier = plan != null ? getBookIdentifier(plan.book()) : "Book ID: " + bookId;
                    String bookTitle = plan != null ? Optional.ofNullable(getBookDisplayTitle(plan.book())).orElse(bookIdentifier) : bookIdentifier;
                    String failureReason = Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName());
                    log.error("Metadata update failed for book: {}", bookIdentifier, e);
                    updateTaskSnapshot(task, finalCompletedCount, String.format("Failed to process: %s - %s", bookTitle, failureReason));
                    sendBatchProgressNotification(
                            jobId,
                            finalCompletedCount,
                            totalBooks,
                            String.format("Failed to process: %s - %s", bookTitle, failureReason),
                            MetadataFetchTaskStatus.IN_PROGRESS,
                            plan != null && plan.reviewMode()
                    );
                } finally {
                    MetadataTaskContext.clear();
                }
                completedCount++;
                issueSequenceIndex++;
            }

            if (failedCount > 0) {
                String message = String.format(
                        "Metadata fetch completed with %d error%s (processed %d of %d books).",
                        failedCount,
                        failedCount == 1 ? "" : "s",
                        completedCount,
                        totalBooks
                );
                failTask(jobId, task, completedCount, totalBooks, message, isReviewMode);
            } else {
                completeTask(task, completedCount, totalBooks, isReviewMode);
            }
            cancellationManager.clearCancellation(jobId);
            if (failedCount > 0) {
                log.info("Metadata refresh task {} completed with {} per-book failures", jobId, failedCount);
            } else {
                log.info("Metadata refresh task {} completed successfully", jobId);
            }

        } catch (RuntimeException e) {
            cancellationManager.clearCancellation(jobId);
            if (e.getCause() instanceof InterruptedException) {
                log.info("Metadata refresh task {} cancelled successfully", jobId);
                return;
            }
            log.error("Fatal error during metadata refresh", e);
            failTask(jobId, task, completedCount, totalBooks, "Fatal error during metadata refresh: " + e.getMessage(), isReviewMode);
            throw e;
        } catch (Exception fatal) {
            cancellationManager.clearCancellation(jobId);
            log.error("Fatal error during metadata refresh", fatal);
            failTask(jobId, task, completedCount, totalBooks, "Fatal error during metadata refresh: " + fatal.getMessage(), isReviewMode);
            throw fatal;
        }
    }

    private MetadataRefreshPlan prepareMetadataRefreshPlan(
            TransactionTemplate txTemplate,
            Long bookId,
            MetadataFetchJobEntity task,
            String jobId,
            int completedCount,
            int totalBooks,
            boolean useRequestOptions,
            MetadataRefreshOptions requestRefreshOptions,
            boolean isLibraryRefresh,
            MetadataRefreshOptions libraryRefreshOptions,
            List<MetadataProvider> fixedProviders,
            AppSettings appSettings
    ) {
        return txTemplate.execute(status -> {
            BookEntity book = loadBookForRefresh(bookId);

            if (book.getMetadata().areAllFieldsLocked()) {
                log.info("Skipping locked book: {}", getBookIdentifier(book));
                markBookCompleted(task, bookId);
                updateTaskSnapshot(task, completedCount, "Skipped locked book: " + book.getMetadata().getTitle());
                sendBatchProgressNotification(jobId, completedCount, totalBooks, "Skipped locked book: " + book.getMetadata().getTitle(), MetadataFetchTaskStatus.IN_PROGRESS, false);
                return null;
            }

            MetadataRefreshOptions refreshOptions;
            List<MetadataProvider> providers;

            if (useRequestOptions) {
                refreshOptions = requestRefreshOptions;
                providers = fixedProviders;
            } else if (isLibraryRefresh) {
                refreshOptions = libraryRefreshOptions;
                providers = fixedProviders;
            } else {
                refreshOptions = resolveMetadataRefreshOptions(book.getLibrary().getId(), appSettings);
                providers = prepareProviders(refreshOptions);
            }

            if (refreshOptions == null) {
                log.warn("Skipping metadata refresh for book {} because no refresh options are configured.", getBookIdentifier(book));
                markBookCompleted(task, bookId);
                updateTaskSnapshot(task, completedCount, "Skipped: no metadata refresh options configured for " + book.getMetadata().getTitle());
                sendBatchProgressNotification(
                        jobId,
                        completedCount,
                        totalBooks,
                        "Skipped: no metadata refresh options configured for " + book.getMetadata().getTitle(),
                        MetadataFetchTaskStatus.IN_PROGRESS,
                        false
                );
                return null;
            }

            boolean reviewMode = Boolean.TRUE.equals(refreshOptions.getReviewBeforeApply());
            reportProgressIfNeeded(task, jobId, completedCount, totalBooks, book, reviewMode);
            return new MetadataRefreshPlan(bookMapper.toBook(book), refreshOptions, providers, reviewMode);
        });
    }

    private void applyFetchedMetadata(
            TransactionTemplate txTemplate,
            Long bookId,
            MetadataFetchJobEntity task,
            String jobId,
            int completedCount,
            int totalBooks,
            MetadataRefreshPlan plan,
            BookMetadata fetched,
            boolean noProviderReturnedData
    ) {
        txTemplate.executeWithoutResult(status -> {
            BookEntity book = loadBookForRefresh(bookId);

            if (book.getMetadata().areAllFieldsLocked()) {
                log.info("Skipping locked book after metadata fetch: {}", getBookIdentifier(book));
                markBookCompleted(task, bookId);
                updateTaskSnapshot(task, completedCount, "Skipped locked book: " + book.getMetadata().getTitle());
                sendBatchProgressNotification(jobId, completedCount, totalBooks, "Skipped locked book: " + book.getMetadata().getTitle(), MetadataFetchTaskStatus.IN_PROGRESS, plan.reviewMode());
                return;
            }

            if (plan.reviewMode()) {
                if (!noProviderReturnedData) {
                    saveProposal(task, book.getId(), fetched);
                } else {
                    log.info("No metadata returned by providers for book {}; skipping blank review proposal.", getBookIdentifier(book));
                }
            } else {
                MetadataReplaceMode replaceMode = plan.refreshOptions().getReplaceMode() != null
                        ? plan.refreshOptions().getReplaceMode()
                        : MetadataReplaceMode.REPLACE_MISSING;
                if (noProviderReturnedData && replaceMode == MetadataReplaceMode.REPLACE_ALL) {
                    log.warn("No metadata returned by any provider for book {} in REPLACE_ALL mode; refusing to wipe existing metadata.", getBookIdentifier(book));
                } else {
                    updateBookMetadata(book, fetched, plan.refreshOptions().isRefreshCovers(), plan.refreshOptions().isMergeCategories(), replaceMode);
                }
            }

            book.setLastMetadataFetchAt(Instant.now());

            markBookCompleted(task, bookId);

            updateTaskSnapshot(task, completedCount + 1, "Processed: " + book.getMetadata().getTitle());
            sendBatchProgressNotification(jobId, completedCount + 1, totalBooks, "Processed: " + book.getMetadata().getTitle(), MetadataFetchTaskStatus.IN_PROGRESS, plan.reviewMode());
            bookRepository.saveAndFlush(book);
        });
    }

    private BookEntity loadBookForRefresh(Long bookId) {
        BookEntity book = bookRepository.findAllWithMetadataByIds(Collections.singleton(bookId))
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        if (book.getMetadata() != null && book.getMetadata().getComicMetadata() != null) {
            ComicMetadataEntity comic = book.getMetadata().getComicMetadata();
            Hibernate.initialize(comic.getCharacters());
            Hibernate.initialize(comic.getTeams());
            Hibernate.initialize(comic.getLocations());
            Hibernate.initialize(comic.getCreatorMappings());
        }
        return book;
    }

    private void delayGoodreadsRequest() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(500, 1500));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Metadata refresh interrupted during Goodreads throttling delay");
        }
    }

    private void markBookCompleted(MetadataFetchJobEntity task, Long bookId) {
        if (task == null || bookId == null) {
            return;
        }

        List<Long> completedBookIds = new ArrayList<>(Optional.ofNullable(task.getCompletedBookIds()).orElseGet(ArrayList::new));
        if (!completedBookIds.contains(bookId)) {
            completedBookIds.add(bookId);
            task.setCompletedBookIds(completedBookIds);
        }
    }

    MetadataRefreshOptions resolveMetadataRefreshOptions(Long libraryId, AppSettings appSettings) {
        MetadataRefreshOptions defaultOptions = appSettings.getDefaultMetadataRefreshOptions();
        List<MetadataRefreshOptions> libraryOptions = appSettings.getLibraryMetadataRefreshOptions();

        if (libraryId != null && libraryOptions != null) {
            return libraryOptions.stream()
                    .filter(options -> libraryId.equals(options.getLibraryId()))
                    .findFirst()
                    .orElse(defaultOptions);
        }

        return defaultOptions;
    }

    public Map<MetadataProvider, BookMetadata> fetchMetadataForBook(List<MetadataProvider> providers, Book book) {
        return fetchMetadataForBook(providers, book, null, 0);
    }

    public Map<MetadataProvider, BookMetadata> fetchMetadataForBook(
            List<MetadataProvider> providers,
            Book book,
            MetadataRefreshOptions refreshOptions,
            int sequenceIndex
    ) {
        BookFetchOutcome outcome = fetchMetadataOutcomeForBook(providers, book, refreshOptions, sequenceIndex);
        if (outcome.hasOnlyTransientFailures()) {
            throw outcome.firstTransientFailure();
        }
        return outcome.metadataMap();
    }

    BookFetchOutcome fetchMetadataOutcomeForBook(
            List<MetadataProvider> providers,
            Book book,
            MetadataRefreshOptions refreshOptions,
            int sequenceIndex
    ) {
        FetchMetadataRequest request = buildFetchMetadataRequestFromBook(book, refreshOptions, sequenceIndex);
        Map<MetadataProvider, BookMetadata> metadataMap = new LinkedHashMap<>();
        Map<MetadataProvider, TransientProviderException> transientFailures = new LinkedHashMap<>();

        for (MetadataProvider provider : providers) {
            if (networkResilienceService != null && providers.size() > 1 && networkResilienceService.isCircuitOpen(provider)) {
                log.debug("Skipping provider {} while its failure circuit is open", provider);
                continue;
            }
            try {
                BookMetadata metadata = fetchMetadataFromProvider(provider, book, request);
                if (networkResilienceService != null) {
                    networkResilienceService.recordProviderSuccess(provider);
                }
                if (metadata != null && metadata.getProvider() != null) {
                    metadataMap.putIfAbsent(metadata.getProvider(), metadata);
                }
            } catch (TransientProviderException e) {
                log.warn("Metadata provider {} encountered transient failure for book {}: {}",
                        provider, getBookIdentifier(book), e.getMessage());
                transientFailures.put(provider, e);
            }
        }
        return new BookFetchOutcome(metadataMap, transientFailures);
    }

    public Map<MetadataProvider, BookMetadata> fetchMetadataForBook(List<MetadataProvider> providers, BookEntity bookEntity) {
        Book book = bookMapper.toBook(bookEntity);
        return fetchMetadataForBook(providers, book, null, 0);
    }

    private void reportProgressIfNeeded(MetadataFetchJobEntity task, String taskId, int completedCount, int total, BookEntity book, boolean isReviewMode) {
        if (task == null) return;
        String message = String.format("Processing '%s'", getBookDisplayTitle(book));
        updateTaskSnapshot(task, completedCount, message);
        sendBatchProgressNotification(taskId, completedCount, total, message, MetadataFetchTaskStatus.IN_PROGRESS, isReviewMode);
    }

    private void updateTaskSnapshot(MetadataFetchJobEntity task, int completedCount, String message) {
        if (task == null) {
            return;
        }
        task.setCompletedBooks(completedCount);
        task.setStatusMessage(message);
        metadataFetchJobRepository.save(task);
    }

    private String getBookIdentifier(BookEntity book) {
        String displayTitle = getBookDisplayTitle(book);
        if (displayTitle != null) {
            return displayTitle;
        }
        return "Book ID: " + book.getId();
    }

    private String getBookIdentifier(Book book) {
        String displayTitle = getBookDisplayTitle(book);
        if (displayTitle != null) {
            return displayTitle;
        }
        return "Book ID: " + book.getId();
    }

    private String getBookDisplayTitle(BookEntity book) {
        if (book.getMetadata() != null && book.getMetadata().getTitle() != null && !book.getMetadata().getTitle().isBlank()) {
            return book.getMetadata().getTitle();
        }
        if (book.getPrimaryBookFile() != null && book.getPrimaryBookFile().getFileName() != null) {
            return org.fable.util.FileUtils.deriveTitleFromFileName(
                    book.getPrimaryBookFile().getFileName(),
                    book.getPrimaryBookFile().isFolderBased()
            );
        }
        return null;
    }

    private String getBookDisplayTitle(Book book) {
        if (book.getMetadata() != null && book.getMetadata().getTitle() != null && !book.getMetadata().getTitle().isBlank()) {
            return book.getMetadata().getTitle();
        }
        if (book.getPrimaryFile() != null && book.getPrimaryFile().getFileName() != null) {
            return org.fable.util.FileUtils.deriveTitleFromFileName(
                    book.getPrimaryFile().getFileName(),
                    book.getPrimaryFile().isFolderBased()
            );
        }
        return null;
    }

    private void sendBatchProgressNotification(String taskId, int current, int total, String message, MetadataFetchTaskStatus status, boolean isReview) {
        notificationService.sendMessage(Topic.BOOK_METADATA_BATCH_PROGRESS, new MetadataBatchProgressNotification(taskId, current, total, message, status.name(), isReview));
        sendTaskProgressNotification(taskId, current, total, message, status);
    }

    private void sendTaskProgressNotification(String taskId, int current, int total, String message, MetadataFetchTaskStatus status) {
        int progress;
        if (total > 0) {
            progress = Math.min(100, Math.max(0, (current * 100) / total));
        } else {
            progress = (status == MetadataFetchTaskStatus.COMPLETED
                    || status == MetadataFetchTaskStatus.CANCELLED
                    || status == MetadataFetchTaskStatus.ERROR) ? 100 : 0;
        }

        notificationService.sendMessage(Topic.TASK_PROGRESS, TaskProgressPayload.builder()
                .taskId(taskId)
                .taskType(TaskType.REFRESH_METADATA_MANUAL)
                .message(message)
                .progress(progress)
            .currentStep(current)
            .totalSteps(total)
                .taskStatus(mapTaskStatus(status))
                .build());
    }

    private TaskStatus mapTaskStatus(MetadataFetchTaskStatus status) {
        return switch (status) {
            case IN_PROGRESS -> TaskStatus.IN_PROGRESS;
            case COMPLETED -> TaskStatus.COMPLETED;
            case CANCELLED -> TaskStatus.CANCELLED;
            case ERROR -> TaskStatus.FAILED;
        };
    }

    private void completeTask(MetadataFetchJobEntity task, int completed, int total, boolean isReviewMode) {
        String completionMessage = total == 0
                ? "No books matched the metadata refresh request."
                : "Batch metadata fetch successfully completed!";
        task.setStatus(MetadataFetchTaskStatus.COMPLETED);
        task.setCompletedAt(Instant.now());
        task.setCompletedBooks(completed);
        task.setStatusMessage(completionMessage);
        metadataFetchJobRepository.save(task);
        sendBatchProgressNotification(task.getTaskId(), completed, total, completionMessage, MetadataFetchTaskStatus.COMPLETED, isReviewMode);
    }

    private void failTask(String taskId, MetadataFetchJobEntity task, int completed, int total, String message, boolean isReviewMode) {
        if (task == null) {
            sendBatchProgressNotification(taskId, completed, total, message, MetadataFetchTaskStatus.ERROR, isReviewMode);
            return;
        }

        task.setStatus(MetadataFetchTaskStatus.ERROR);
        task.setCompletedAt(Instant.now());
        task.setCompletedBooks(completed);
        task.setStatusMessage(message);
        metadataFetchJobRepository.save(task);
        sendBatchProgressNotification(task.getTaskId(), completed, total, message, MetadataFetchTaskStatus.ERROR, isReviewMode);
    }

    private void cancelTask(MetadataFetchJobEntity task) {
        task.setStatus(MetadataFetchTaskStatus.CANCELLED);
        task.setCompletedAt(Instant.now());
        task.setStatusMessage("Task cancelled by user");
        metadataFetchJobRepository.save(task);
        sendBatchProgressNotification(task.getTaskId(), task.getCompletedBooks(), task.getTotalBooksCount(), "Task cancelled by user", MetadataFetchTaskStatus.CANCELLED, false);
    }

    private void saveProposal(MetadataFetchJobEntity job, Long bookId, BookMetadata metadata) throws JacksonException {
        MetadataFetchProposalEntity proposal = MetadataFetchProposalEntity.builder()
                .job(job)
                .bookId(bookId)
                .metadataJson(objectMapper.writeValueAsString(metadata))
                .status(FetchedMetadataProposalStatus.FETCHED)
                .fetchedAt(Instant.now())
                .build();
        job.getProposals().add(proposal);
    }


    public void updateBookMetadata(BookEntity bookEntity, BookMetadata metadata, boolean replaceCover, boolean mergeCategories) {
        updateBookMetadata(bookEntity, metadata, replaceCover, mergeCategories, MetadataReplaceMode.REPLACE_MISSING);
    }

    public void updateBookMetadata(BookEntity bookEntity, BookMetadata metadata, boolean replaceCover, boolean mergeCategories, MetadataReplaceMode replaceMode) {
        MetadataUpdateContext context = MetadataUpdateContext.builder()
                .bookEntity(bookEntity)
                .metadataUpdateWrapper(MetadataUpdateWrapper.builder()
                        .metadata(metadata)
                        .build())
                .updateThumbnail(replaceCover)
                .mergeCategories(mergeCategories)
                .replaceMode(replaceMode)
                .mergeMoods(true)
                .mergeTags(true)
                .build();

        updateBookMetadata(context);
    }

    public void updateBookMetadata(MetadataUpdateContext context) {
        if (context.getMetadataUpdateWrapper() != null && context.getMetadataUpdateWrapper().getMetadata() != null) {
            bookMetadataUpdater.setBookMetadata(context);

            Book book = bookMapper.toBookWithDescription(context.getBookEntity(), true);
            
            FableUser user = authenticationService.getAuthenticatedUser();
            if (user != null && book.getShelves() != null) {
                book.setShelves(filterShelvesByUserId(book.getShelves(), user.getId()));
            }
            
            notificationService.sendMessage(Topic.BOOK_METADATA_UPDATE, book);
        }
    }

    public List<MetadataProvider> prepareProviders(MetadataRefreshOptions refreshOptions) {
        AppSettings appSettings = appSettingService.getAppSettings();
        Set<MetadataProvider> allProviders = EnumSet.noneOf(MetadataProvider.class);
        allProviders.addAll(getAllProvidersUsingIndividualFields(refreshOptions, appSettings));
        return new ArrayList<>(allProviders);
    }

    protected Set<MetadataProvider> getAllProvidersUsingIndividualFields(MetadataRefreshOptions refreshOptions, AppSettings appSettings) {
        MetadataRefreshOptions.FieldOptions fieldOptions = refreshOptions.getFieldOptions();
        Set<MetadataProvider> uniqueProviders = EnumSet.noneOf(MetadataProvider.class);

        if (fieldOptions != null) {
            addProviderToSet(fieldOptions.getTitle(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getSubtitle(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getDescription(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getAuthors(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getPublisher(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getPublishedDate(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getSeriesName(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getSeriesNumber(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getSeriesTotal(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getIsbn13(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getIsbn10(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getLanguage(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getCategories(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getCover(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getPageCount(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getAsin(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getGoodreadsId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getComicvineId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getHardcoverId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getGoogleId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getLubimyczytacId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getAmazonRating(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getAmazonReviewCount(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getGoodreadsRating(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getGoodreadsReviewCount(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getHardcoverRating(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getHardcoverReviewCount(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getLubimyczytacRating(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getRanobedbId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getRanobedbRating(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getMoods(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getTags(), uniqueProviders, appSettings);
        }

        return uniqueProviders;
    }

    protected void addProviderToSet(MetadataRefreshOptions.FieldProvider fieldProvider, Set<MetadataProvider> providerSet, AppSettings appSettings) {
        if (fieldProvider != null) {
            if (fieldProvider.getP1() != null && isProviderEnabled(fieldProvider.getP1(), appSettings)) providerSet.add(fieldProvider.getP1());
            if (fieldProvider.getP2() != null && isProviderEnabled(fieldProvider.getP2(), appSettings)) providerSet.add(fieldProvider.getP2());
            if (fieldProvider.getP3() != null && isProviderEnabled(fieldProvider.getP3(), appSettings)) providerSet.add(fieldProvider.getP3());
            if (fieldProvider.getP4() != null && isProviderEnabled(fieldProvider.getP4(), appSettings)) providerSet.add(fieldProvider.getP4());
        }
    }

    protected boolean isProviderEnabled(MetadataProvider provider, AppSettings appSettings) {
        if (provider == null || appSettings == null || appSettings.getMetadataProviderSettings() == null) {
            return true;
        }

        var settings = appSettings.getMetadataProviderSettings();
        return switch (provider) {
            case Amazon -> settings.getAmazon() != null && settings.getAmazon().isEnabled();
            case Google -> settings.getGoogle() != null && settings.getGoogle().isEnabled();
            case GoodReads -> settings.getGoodReads() != null && settings.getGoodReads().isEnabled();
            case Hardcover -> settings.getHardcover() != null && settings.getHardcover().isEnabled();
            case Comicvine -> settings.getComicvine() != null && settings.getComicvine().isEnabled();
            case Ranobedb -> settings.getRanobedb() != null && settings.getRanobedb().isEnabled();
            case Douban -> settings.getDouban() != null && settings.getDouban().isEnabled();
            case Lubimyczytac -> settings.getLubimyczytac() != null && settings.getLubimyczytac().isEnabled();
            default -> true;
        };
    }

    private BookMetadata fetchMetadataFromProvider(MetadataProvider provider, Book book, FetchMetadataRequest request) {
        BookParser parser = getParser(provider);
        FetchMetadataRequest requestCopy = copyAndCleanFetchMetadataRequest(request, provider);

        try {
            BookMetadata topMetadata = parser.fetchTopMetadata(book, requestCopy);
            if (topMetadata != null) {
                return topMetadata;
            }

            log.debug("Metadata provider {} returned no top match for book {}. Falling back to full result scan.", provider, getBookIdentifier(book));
        } catch (CancellationException | TransientProviderException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Metadata provider {} top-match fetch failed for book {}. Falling back to full result scan. Cause: {}",
                    provider, getBookIdentifier(book), e.getMessage());
        }

        try {
            List<BookMetadata> metadataList = parser.fetchMetadata(book, requestCopy);
            if (metadataList == null || metadataList.isEmpty()) {
                return null;
            }
            return metadataList.getFirst();
        } catch (CancellationException | TransientProviderException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Metadata provider {} full result scan failed for book {}. Skipping provider. Cause: {}",
                    provider, getBookIdentifier(book), e.getMessage());
            return null;
        }
    }

    private FetchMetadataRequest copyAndCleanFetchMetadataRequest(FetchMetadataRequest request, MetadataProvider provider) {
        FetchMetadataRequest requestCopy = FetchMetadataRequest.builder()
                .bookId(request.getBookId())
                .providers(request.getProviders())
                .isbn(request.getIsbn())
                .title(request.getTitle())
                .author(request.getAuthor())
                .asin(request.getAsin())
                .sourceUrl(request.getSourceUrl())
                .issueNumber(request.getIssueNumber())
                .issueRange(request.getIssueRange())
                .build();
        BookUtils.cleanFetchMetadataRequest(requestCopy, provider);
        return requestCopy;
    }

    public BookMetadata fetchTopMetadataFromAProvider(MetadataProvider provider, Book book) {
        return fetchMetadataFromProvider(provider, book, buildFetchMetadataRequestFromBook(book));
    }

    private BookParser getParser(MetadataProvider provider) {
        BookParser parser = parserMap.get(provider);
        if (parser == null) {
            throw ApiError.METADATA_SOURCE_NOT_IMPLEMENT_OR_DOES_NOT_EXIST.createException();
        }
        return parser;
    }

    private FetchMetadataRequest buildFetchMetadataRequestFromBook(Book book) {
        return buildFetchMetadataRequestFromBook(book, null, 0);
    }

    private FetchMetadataRequest buildFetchMetadataRequestFromBook(Book book, MetadataRefreshOptions refreshOptions, int sequenceIndex) {
        BookMetadata metadata = book.getMetadata();
        FetchMetadataRequest request;
        
        if (metadata == null) {
            String sourceUrl = refreshOptions != null ? trimToNull(refreshOptions.getSourceUrl()) : null;
            String sequentialIssueNumber = refreshOptions != null ? resolveIssueNumberFromRange(refreshOptions.getIssueRange(), sequenceIndex) : null;
            String explicitIssueNumber = refreshOptions != null ? trimToNull(refreshOptions.getIssueNumber()) : null;
            String issueNumber = sequentialIssueNumber != null ? sequentialIssueNumber : explicitIssueNumber;
            request = FetchMetadataRequest.builder()
                    .title(getBookDisplayTitle(book))
                    .sourceUrl(sourceUrl)
                    .issueNumber(issueNumber)
                    .bookId(book.getId())
                    .build();
        } else {
            String isbn = metadata.getIsbn13();
            if (isbn == null || isbn.isBlank()) {
                isbn = metadata.getIsbn10();
            }
            String title = metadata.getTitle();
            if (title == null || title.isBlank()) {
                title = getBookDisplayTitle(book);
            }

            String configuredSourceUrl = metadata.getExternalUrl();
            String issueNumber = null;

            if (refreshOptions != null) {
                String customSourceUrl = trimToNull(refreshOptions.getSourceUrl());
                if (customSourceUrl != null) {
                    configuredSourceUrl = customSourceUrl;
                }

                String sequentialIssueNumber = resolveIssueNumberFromRange(refreshOptions.getIssueRange(), sequenceIndex);
                String explicitIssueNumber = trimToNull(refreshOptions.getIssueNumber());
                issueNumber = sequentialIssueNumber != null ? sequentialIssueNumber : explicitIssueNumber;
            }

            request = FetchMetadataRequest.builder()
                    .isbn(isbn)
                    .asin(metadata.getAsin())
                    .author(metadata.getAuthors() != null ? String.join(", ", metadata.getAuthors()) : null)
                    .title(title)
                    .sourceUrl(configuredSourceUrl)
                    .issueNumber(issueNumber)
                    .bookId(book.getId())
                    .build();
        }

        return request;
    }

    private String resolveIssueNumberFromRange(String rawIssueRange, int sequenceIndex) {
        String issueRange = trimToNull(rawIssueRange);
        if (issueRange == null) {
            return null;
        }

        Matcher matcher = ISSUE_RANGE_PATTERN.matcher(issueRange);
        if (!matcher.matches()) {
            log.warn("Ignoring invalid issue range '{}'. Expected format like '43-171'.", issueRange);
            return null;
        }

        int start;
        int end;
        try {
            start = Integer.parseInt(matcher.group(1));
            end = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException e) {
            return null;
        }

        if (start > end) {
            int tmp = start;
            start = end;
            end = tmp;
        }

        int currentIssue = start + Math.max(0, sequenceIndex);
        if (currentIssue > end) {
            return null;
        }

        return String.valueOf(currentIssue);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public BookMetadata buildFetchMetadata(BookMetadata existingMetadata, Long bookId, MetadataRefreshOptions refreshOptions, Map<MetadataProvider, BookMetadata> metadataMap) {
        BookMetadata metadata = BookMetadata.builder().bookId(bookId).build();

        MetadataRefreshOptions.FieldOptions fieldOptions = refreshOptions.getFieldOptions();
        if (fieldOptions == null) {
            fieldOptions = new MetadataRefreshOptions.FieldOptions();
        }

        MetadataRefreshOptions.EnabledFields enabledFields = refreshOptions.getEnabledFields();
        if (enabledFields == null) {
            enabledFields = new MetadataRefreshOptions.EnabledFields();
        }
        
        MetadataReplaceMode replaceMode = refreshOptions.getReplaceMode();
        boolean isReplaceAll = replaceMode == MetadataReplaceMode.REPLACE_ALL;

        if (enabledFields.isTitle()) {
            metadata.setTitle(resolveFieldAsString(metadataMap, fieldOptions.getTitle(), BookMetadata::getTitle));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setTitle(existingMetadata.getTitle());
        }
        
        if (enabledFields.isSubtitle()) {
            metadata.setSubtitle(resolveFieldAsString(metadataMap, fieldOptions.getSubtitle(), BookMetadata::getSubtitle));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setSubtitle(existingMetadata.getSubtitle());
        }
        
        if (enabledFields.isDescription()) {
            metadata.setDescription(resolveFieldAsString(metadataMap, fieldOptions.getDescription(), BookMetadata::getDescription));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setDescription(existingMetadata.getDescription());
        }
        
        if (enabledFields.isAuthors()) {
            metadata.setAuthors(resolveFieldAsList(metadataMap, fieldOptions.getAuthors(), BookMetadata::getAuthors));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setAuthors(existingMetadata.getAuthors());
        }
        
        if (enabledFields.isPublisher()) {
            metadata.setPublisher(resolveFieldAsString(metadataMap, fieldOptions.getPublisher(), BookMetadata::getPublisher));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setPublisher(existingMetadata.getPublisher());
        }
        
        if (enabledFields.isPublishedDate()) {
            metadata.setPublishedDate(resolveField(metadataMap, fieldOptions.getPublishedDate(), BookMetadata::getPublishedDate));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setPublishedDate(existingMetadata.getPublishedDate());
        }
        
        if (enabledFields.isSeriesName()) {
            metadata.setSeriesName(resolveFieldAsString(metadataMap, fieldOptions.getSeriesName(), BookMetadata::getSeriesName));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setSeriesName(existingMetadata.getSeriesName());
        }
        
        if (enabledFields.isSeriesNumber()) {
            metadata.setSeriesNumber(resolveField(metadataMap, fieldOptions.getSeriesNumber(), BookMetadata::getSeriesNumber));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setSeriesNumber(existingMetadata.getSeriesNumber());
        }
        
        if (enabledFields.isSeriesTotal()) {
            metadata.setSeriesTotal(resolveFieldAsInteger(metadataMap, fieldOptions.getSeriesTotal(), BookMetadata::getSeriesTotal));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setSeriesTotal(existingMetadata.getSeriesTotal());
        }
        
        if (enabledFields.isIsbn13()) {
            metadata.setIsbn13(resolveFieldAsString(metadataMap, fieldOptions.getIsbn13(), BookMetadata::getIsbn13));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setIsbn13(existingMetadata.getIsbn13());
        }
        
        if (enabledFields.isIsbn10()) {
            metadata.setIsbn10(resolveFieldAsString(metadataMap, fieldOptions.getIsbn10(), BookMetadata::getIsbn10));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setIsbn10(existingMetadata.getIsbn10());
        }
        
        if (enabledFields.isLanguage()) {
            metadata.setLanguage(resolveFieldAsString(metadataMap, fieldOptions.getLanguage(), BookMetadata::getLanguage));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setLanguage(existingMetadata.getLanguage());
        }
        
        if (enabledFields.isPageCount()) {
            metadata.setPageCount(resolveFieldAsInteger(metadataMap, fieldOptions.getPageCount(), BookMetadata::getPageCount));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setPageCount(existingMetadata.getPageCount());
        }
        
        if (enabledFields.isCover()) {
            metadata.setThumbnailUrl(resolveFieldAsString(metadataMap, fieldOptions.getCover(), BookMetadata::getThumbnailUrl));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setThumbnailUrl(existingMetadata.getThumbnailUrl());
        }
        if (enabledFields.isAmazonRating()) {
            if (metadataMap.containsKey(Amazon)) {
                metadata.setAmazonRating(metadataMap.get(Amazon).getAmazonRating());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setAmazonRating(existingMetadata.getAmazonRating());
        }

        if (enabledFields.isAmazonReviewCount()) {
            if (metadataMap.containsKey(Amazon)) {
                metadata.setAmazonReviewCount(metadataMap.get(Amazon).getAmazonReviewCount());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setAmazonReviewCount(existingMetadata.getAmazonReviewCount());
        }

        if (enabledFields.isGoodreadsRating()) {
            if (metadataMap.containsKey(GoodReads)) {
                metadata.setGoodreadsRating(metadataMap.get(GoodReads).getGoodreadsRating());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setGoodreadsRating(existingMetadata.getGoodreadsRating());
        }

        if (enabledFields.isGoodreadsReviewCount()) {
            if (metadataMap.containsKey(GoodReads)) {
                metadata.setGoodreadsReviewCount(metadataMap.get(GoodReads).getGoodreadsReviewCount());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setGoodreadsReviewCount(existingMetadata.getGoodreadsReviewCount());
        }

        if (enabledFields.isHardcoverRating()) {
            if (metadataMap.containsKey(Hardcover)) {
                metadata.setHardcoverRating(metadataMap.get(Hardcover).getHardcoverRating());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setHardcoverRating(existingMetadata.getHardcoverRating());
        }

        if (enabledFields.isHardcoverReviewCount()) {
            if (metadataMap.containsKey(Hardcover)) {
                metadata.setHardcoverReviewCount(metadataMap.get(Hardcover).getHardcoverReviewCount());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setHardcoverReviewCount(existingMetadata.getHardcoverReviewCount());
        }

        if (enabledFields.isAsin()) {
            if (metadataMap.containsKey(Amazon)) {
                metadata.setAsin(metadataMap.get(Amazon).getAsin());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setAsin(existingMetadata.getAsin());
        }
        if (enabledFields.isGoodreadsId()) {
            if (metadataMap.containsKey(GoodReads)) {
                metadata.setGoodreadsId(metadataMap.get(GoodReads).getGoodreadsId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setGoodreadsId(existingMetadata.getGoodreadsId());
        }

        if (enabledFields.isHardcoverId()) {
            if (metadataMap.containsKey(Hardcover)) {
                metadata.setHardcoverId(metadataMap.get(Hardcover).getHardcoverId());
                metadata.setHardcoverBookId(metadataMap.get(Hardcover).getHardcoverBookId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setHardcoverId(existingMetadata.getHardcoverId());
            metadata.setHardcoverBookId(existingMetadata.getHardcoverBookId());
        }

        if (enabledFields.isGoogleId()) {
            if (metadataMap.containsKey(Google)) {
                metadata.setGoogleId(metadataMap.get(Google).getGoogleId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setGoogleId(existingMetadata.getGoogleId());
        }

        if (enabledFields.isComicvineId()) {
            if (metadataMap.containsKey(Comicvine)) {
                metadata.setComicvineId(metadataMap.get(Comicvine).getComicvineId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setComicvineId(existingMetadata.getComicvineId());
        }

        if (metadataMap.containsKey(Comicvine) && metadataMap.get(Comicvine).getComicMetadata() != null) {
            metadata.setComicMetadata(metadataMap.get(Comicvine).getComicMetadata());
        }

        if (enabledFields.isLubimyczytacId()) {
            if (metadataMap.containsKey(Lubimyczytac)) {
                metadata.setLubimyczytacId(metadataMap.get(Lubimyczytac).getLubimyczytacId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setLubimyczytacId(existingMetadata.getLubimyczytacId());
        }

        if (enabledFields.isLubimyczytacRating()) {
            if (metadataMap.containsKey(Lubimyczytac)) {
                metadata.setLubimyczytacRating(metadataMap.get(Lubimyczytac).getLubimyczytacRating());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setLubimyczytacRating(existingMetadata.getLubimyczytacRating());
        }

        if (enabledFields.isRanobedbId()) {
            if (metadataMap.containsKey(Ranobedb)) {
                metadata.setRanobedbId(metadataMap.get(Ranobedb).getRanobedbId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setRanobedbId(existingMetadata.getRanobedbId());
        }

        if (enabledFields.isRanobedbRating()) {
            if (metadataMap.containsKey(Ranobedb)) {
                metadata.setRanobedbRating(metadataMap.get(Ranobedb).getRanobedbRating());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setRanobedbRating(existingMetadata.getRanobedbRating());
        }

        if (enabledFields.isMoods()) {
            if (metadataMap.containsKey(Hardcover)) {
                metadata.setMoods(metadataMap.get(Hardcover).getMoods());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setMoods(existingMetadata.getMoods());
        }

        if (enabledFields.isTags()) {
            if (metadataMap.containsKey(Hardcover)) {
                metadata.setTags(metadataMap.get(Hardcover).getTags());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setTags(existingMetadata.getTags());
        }

        if (enabledFields.isCategories()) {
            if (refreshOptions.isMergeCategories()) {
                metadata.setCategories(getAllCategories(metadataMap, fieldOptions.getCategories(), BookMetadata::getCategories));
            } else {
                metadata.setCategories(resolveFieldAsSet(metadataMap, fieldOptions.getCategories(), BookMetadata::getCategories));
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setCategories(existingMetadata.getCategories());
        }

        List<BookReview> allReviews = metadataMap.values().stream()
                .filter(Objects::nonNull)
                .flatMap(md -> Optional.ofNullable(md.getBookReviews()).stream().flatMap(Collection::stream))
                .collect(Collectors.toList());
        if (!allReviews.isEmpty()) {
            metadata.setBookReviews(allReviews);
        }

        return metadata;
    }

    protected <T > T resolveField(Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, Function < BookMetadata, T > extractor) {
        return resolveFieldWithProviders(metadataMap, fieldProvider, extractor, Objects::nonNull);
    }

    protected Integer resolveFieldAsInteger (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, Function < BookMetadata, Integer > fieldValueExtractor){
        return resolveFieldWithProviders(metadataMap, fieldProvider, fieldValueExtractor, Objects::nonNull);
    }

    protected String resolveFieldAsString (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, FieldValueExtractor fieldValueExtractor){
        return resolveFieldWithProviders(metadataMap, fieldProvider, fieldValueExtractor::extract, Objects::nonNull);
    }

    protected List<String> resolveFieldAsList (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, FieldValueExtractorList fieldValueExtractor){
        Collection<String> result = resolveFieldWithProviders(metadataMap, fieldProvider, fieldValueExtractor::extract, (value) -> value != null && !value.isEmpty());
        if (result == null) return null;
        return result instanceof List<String> list ? list : new ArrayList<>(result);
    }

    protected Set<String> resolveFieldAsSet (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, FieldValueExtractorList fieldValueExtractor){
        Collection<String> result = resolveFieldWithProviders(metadataMap, fieldProvider, fieldValueExtractor::extract, (value) -> value != null && !value.isEmpty());
        if (result == null) return null;
        return result instanceof Set<String> set ? set : new HashSet<>(result);
    }

    private <T > T resolveFieldWithProviders(Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, Function < BookMetadata, T > extractor, Predicate < T > isValidValue) {
        if (fieldProvider == null) {
            return null;
        }
        MetadataProvider[] providers = {
                fieldProvider.getP1(),
                fieldProvider.getP2(),
                fieldProvider.getP3(),
                fieldProvider.getP4()
        };
        for (MetadataProvider provider : providers) {
            if (provider != null && metadataMap.containsKey(provider)) {
                T value = extractor.apply(metadataMap.get(provider));
                if (isValidValue.test(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    Set<String> getAllCategories (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, FieldValueExtractorList fieldValueExtractor){
        Set<String> uniqueCategories = new HashSet<>();
        if (fieldProvider == null) {
            return uniqueCategories;
        }

        MetadataProvider[] providers = {
                fieldProvider.getP1(),
                fieldProvider.getP2(),
                fieldProvider.getP3(),
                fieldProvider.getP4()
        };

        for (MetadataProvider provider : providers) {
            if (provider != null && metadataMap.containsKey(provider)) {
                Collection<String> extracted = fieldValueExtractor.extract(metadataMap.get(provider));
                if (extracted != null) {
                    uniqueCategories.addAll(extracted);
                }
            }
        }

        return uniqueCategories;
    }

    protected Set<Long> getBookEntities (MetadataRefreshRequest request){
        MetadataRefreshRequest.RefreshType refreshType = request.getRefreshType();
        if (refreshType != MetadataRefreshRequest.RefreshType.LIBRARY && refreshType != MetadataRefreshRequest.RefreshType.BOOKS) {
            throw ApiError.INVALID_REFRESH_TYPE.createException();
        }
        Set<Long> selectedBookIds = switch (refreshType) {
            case LIBRARY -> {
                LibraryEntity libraryEntity = libraryRepository.findById(request.getLibraryId()).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(request.getLibraryId()));
                yield bookRepository.findBookIdsByLibraryId(libraryEntity.getId());
            }
            case BOOKS -> request.getBookIds() != null
                    ? new LinkedHashSet<>(request.getBookIds())
                    : Collections.emptySet();
        };

        if (selectedBookIds == null || selectedBookIds.isEmpty()) {
            return Collections.emptySet();
        }

        MetadataRefreshRequest.TargetMode targetMode = Optional.ofNullable(request.getTargetMode())
                .orElse(MetadataRefreshRequest.TargetMode.ALL);

        return switch (targetMode) {
            case ALL -> selectedBookIds;
            case NEVER_FETCHED -> {
                Set<Long> eligible = bookRepository.findBookIdsByIdInAndLastMetadataFetchAtIsNull(selectedBookIds);
                yield selectedBookIds.stream()
                        .filter(eligible::contains)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
            case OLDER_THAN_DAYS -> {
                int olderThanDays = Optional.ofNullable(request.getOlderThanDays())
                        .filter(days -> days > 0)
                        .orElseThrow(() -> ApiError.INVALID_REFRESH_TYPE.createException());
                Instant cutoff = Instant.now().minus(olderThanDays, ChronoUnit.DAYS);
                Set<Long> eligible = bookRepository.findBookIdsByIdInAndLastMetadataFetchAtBeforeOrNull(selectedBookIds, cutoff);
                yield selectedBookIds.stream()
                        .filter(eligible::contains)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
        };
    }

    private Set<Shelf> filterShelvesByUserId(Set<Shelf> shelves, Long userId) {
        if (shelves == null) return Collections.emptySet();
        return shelves.stream()
                .filter(shelf -> userId.equals(shelf.getUserId()))
                .collect(Collectors.toSet());
    }
}
