package org.fable.service.metadata;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fable.exception.ApiError;
import org.fable.mapper.BookMapper;
import org.fable.model.MetadataUpdateContext;
import org.fable.model.MetadataUpdateWrapper;
import org.fable.model.dto.Book;
import org.fable.model.dto.BookMetadata;
import org.fable.model.dto.MetadataBatchProgressNotification;
import org.fable.model.dto.metadata.IsbnDiscoveryResult;
import org.fable.model.dto.request.FetchMetadataRequest;
import org.fable.model.dto.request.MetadataRefreshOptions;
import org.fable.model.dto.settings.AppSettings;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.BookMetadataEntity;
import org.fable.model.enums.MetadataFetchTaskStatus;
import org.fable.model.enums.MetadataProvider;
import org.fable.model.enums.MetadataReplaceMode;
import org.fable.model.websocket.Topic;
import org.fable.repository.BookRepository;
import org.fable.service.appsettings.AppSettingService;
import org.fable.service.NotificationService;
import org.fable.service.metadata.parser.BookParser;
import org.fable.service.metadata.parser.ParserUtils;
import org.fable.service.network.TransientProviderException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * ISBN-anchored multi-pass metadata fill.
 * Does not change {@link BookMetadataService#lookupByIsbn} first-hit behavior.
 */
@Slf4j
@Service
public class IsbnMetadataFillService {

    private final AppSettingService appSettingService;
    private final BookRepository bookRepository;
    private final BookMapper bookMapper;
    private final BookMetadataService bookMetadataService;
    private final BookMetadataUpdater bookMetadataUpdater;
    private final IsbnDiscoveryService isbnDiscoveryService;
    private final IsbnDiscoveryStatusService isbnDiscoveryStatusService;
    private final NotificationService notificationService;
    private final Map<MetadataProvider, BookParser> parserMap;
    private final PlatformTransactionManager transactionManager;

    public IsbnMetadataFillService(
            AppSettingService appSettingService,
            BookRepository bookRepository,
            BookMapper bookMapper,
            BookMetadataService bookMetadataService,
            BookMetadataUpdater bookMetadataUpdater,
            IsbnDiscoveryService isbnDiscoveryService,
            IsbnDiscoveryStatusService isbnDiscoveryStatusService,
            NotificationService notificationService,
            Map<MetadataProvider, BookParser> parserMap) {
        this(appSettingService, bookRepository, bookMapper, bookMetadataService, bookMetadataUpdater,
                isbnDiscoveryService, isbnDiscoveryStatusService, notificationService, parserMap, null);
    }

    @Autowired
    public IsbnMetadataFillService(
            AppSettingService appSettingService,
            BookRepository bookRepository,
            BookMapper bookMapper,
            BookMetadataService bookMetadataService,
            BookMetadataUpdater bookMetadataUpdater,
            IsbnDiscoveryService isbnDiscoveryService,
            IsbnDiscoveryStatusService isbnDiscoveryStatusService,
            NotificationService notificationService,
            Map<MetadataProvider, BookParser> parserMap,
            PlatformTransactionManager transactionManager) {
        this.appSettingService = appSettingService;
        this.bookRepository = bookRepository;
        this.bookMapper = bookMapper;
        this.bookMetadataService = bookMetadataService;
        this.bookMetadataUpdater = bookMetadataUpdater;
        this.isbnDiscoveryService = isbnDiscoveryService;
        this.isbnDiscoveryStatusService = isbnDiscoveryStatusService;
        this.notificationService = notificationService;
        this.parserMap = parserMap;
        this.transactionManager = transactionManager;
    }

    record IsbnMergeOutcome(BookMetadata merged, TransientProviderException firstTransientFailure) {
        boolean failedDueToNetwork() {
            return merged == null && firstTransientFailure != null;
        }
    }

    private record PreparedIsbnBook(
            BookMetadata existing,
            Path filePath,
            IsbnFillOutcome earlyOutcome
    ) {}

    /**
     * Fetch metadata from each configured provider by ISBN and merge:
     * first successful provider becomes the base; later providers fill missing fields only.
     */
    public BookMetadata mergeByIsbn(String isbn, BookMetadata existingHints) {
        return mergeByIsbn(isbn, existingHints, null);
    }

    public BookMetadata mergeByIsbn(
            String isbn,
            BookMetadata existingHints,
            List<MetadataProvider> selectedProviders) {
        IsbnMergeOutcome outcome = mergeByIsbnOutcome(isbn, existingHints, selectedProviders);
        if (outcome.failedDueToNetwork()) {
            throw outcome.firstTransientFailure();
        }
        return outcome.merged();
    }

    IsbnMergeOutcome mergeByIsbnOutcome(
            String isbn,
            BookMetadata existingHints,
            List<MetadataProvider> selectedProviders) {
        String cleaned = ParserUtils.cleanIsbn(isbn);
        if (!ParserUtils.isValidIsbnChecksum(cleaned)) {
            return new IsbnMergeOutcome(null, null);
        }

        List<MetadataProvider> providers = selectedProviders == null || selectedProviders.isEmpty()
                ? deriveProviderChain()
                : new ArrayList<>(new LinkedHashSet<>(selectedProviders));
        BookMetadata merged = null;
        TransientProviderException firstTransientFailure = null;
        Book emptyBook = Book.builder().metadata(existingHints).build();
        FetchMetadataRequest request = FetchMetadataRequest.builder()
                .isbn(cleaned)
                .providers(providers)
                .build();

        for (MetadataProvider provider : providers) {
            BookParser parser = parserMap.get(provider);
            if (parser == null) {
                continue;
            }
            try {
                List<BookMetadata> results = parser.fetchMetadata(emptyBook, request);
                if (results == null || results.isEmpty()) {
                    continue;
                }
                BookMetadata candidate = results.getFirst();
                if (merged == null) {
                    merged = copyMetadata(candidate);
                } else {
                    fillMissingFields(merged, candidate);
                }
            } catch (TransientProviderException e) {
                log.warn("ISBN multi-pass fetch encountered transient network error for provider {}: {}", provider, e.getMessage());
                if (firstTransientFailure == null) {
                    firstTransientFailure = e;
                }
            } catch (Exception e) {
                log.warn("ISBN multi-pass fetch failed for provider {}: {}", provider, e.getMessage());
            }
        }

        if (merged == null) {
            return new IsbnMergeOutcome(null, firstTransientFailure);
        }

        if (merged.getIsbn13() == null || merged.getIsbn13().isBlank()) {
            String isbn13 = ParserUtils.toIsbn13(cleaned);
            if (isbn13 != null) {
                merged.setIsbn13(isbn13);
            }
        }
        if (merged.getIsbn10() == null || merged.getIsbn10().isBlank()) {
            String isbn10 = ParserUtils.toIsbn10(cleaned);
            if (isbn10 != null) {
                merged.setIsbn10(isbn10);
            }
        }
        merged.setIsbnVerified(Boolean.TRUE);
        return new IsbnMergeOutcome(merged, firstTransientFailure);
    }

    /**
     * Discover ISBN if needed, multi-pass fetch, clear-unlocked when MULTI_PASS,
     * then auto-apply or return metadata for review based on ISBN-scoped setting.
     * Ambiguous discoveries that still have a best candidate are fetched and always staged.
     */
    public IsbnFillOutcome fillBookFromIsbn(long bookId) {
        return fillBookFromIsbn(bookId, null);
    }

    public IsbnFillOutcome fillBookFromIsbn(long bookId, List<MetadataProvider> selectedProviders) {
        AppSettings settings = appSettingService.getAppSettings();
        if (!settings.isIsbnDiscoveryEnabled()) {
            return IsbnFillOutcome.disabled(bookId);
        }

        PreparedIsbnBook prepared = inTransaction(() -> {
            BookEntity book = bookRepository.findById(bookId)
                    .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
            BookMetadataEntity entityMeta = book.getMetadata();
            if (entityMeta == null) {
                String failMessage = "Book has no metadata entity";
                isbnDiscoveryStatusService.recordError(book, failMessage);
                return new PreparedIsbnBook(null, null, IsbnFillOutcome.error(bookId, failMessage));
            }
            if (entityMeta.areAllFieldsLocked()) {
                return new PreparedIsbnBook(null, null, IsbnFillOutcome.skipped(bookId, "All metadata fields locked"));
            }
            BookMetadata existing = bookMapper.toBook(book).getMetadata();
            Path path = book.getFullFilePath();
            return new PreparedIsbnBook(existing, path, null);
        });

        if (prepared.earlyOutcome() != null) {
            return prepared.earlyOutcome();
        }

        BookMetadata existing = prepared.existing();
        String isbn = firstNonBlank(existing != null ? existing.getIsbn13() : null,
                existing != null ? existing.getIsbn10() : null);

        boolean discovered = false;
        boolean forceReview = false;
        IsbnDiscoveryResult discovery = null;
        if (isbn == null || !ParserUtils.isValidIsbnChecksum(isbn)) {
            Path path = prepared.filePath();
            if (path == null) {
                String failMessage = "No ISBN on file and no resolvable file path for discovery";
                emitPhaseProgress(MetadataBatchProgressNotification.PHASE_ISBN_FAILED, failMessage);
                inTransaction(() -> {
                    bookRepository.findById(bookId).ifPresent(b -> isbnDiscoveryStatusService.recordError(b, failMessage));
                    return null;
                });
                return IsbnFillOutcome.error(bookId, failMessage);
            }
            emitPhaseProgress(MetadataBatchProgressNotification.PHASE_ISBN_DISCOVERY,
                    "ISBN fetch — book " + bookProgressLabel() + "…");
            discovery = isbnDiscoveryService.discoverFromFile(path.toFile(), existing);
            if (discovery.hasResolvedIsbn()) {
                isbn = firstNonBlank(discovery.getIsbn13(), discovery.getIsbn10());
                discovered = true;
            } else if (discovery.getStatus() == IsbnDiscoveryResult.Status.AMBIGUOUS
                    && firstNonBlank(discovery.getIsbn13(), discovery.getIsbn10()) != null) {
                // Best candidate present but not verified — fetch and always require human review.
                isbn = firstNonBlank(discovery.getIsbn13(), discovery.getIsbn10());
                discovered = true;
                forceReview = true;
            } else {
                String failMessage = discovery.getMessage() != null
                        ? discovery.getMessage()
                        : "No checksum-valid ISBN found";
                emitPhaseProgress(MetadataBatchProgressNotification.PHASE_ISBN_FAILED, failMessage);
                IsbnDiscoveryResult finalDiscovery = discovery;
                inTransaction(() -> {
                    bookRepository.findById(bookId).ifPresent(b -> recordDiscoveryProblem(b, finalDiscovery, failMessage));
                    return null;
                });
                return IsbnFillOutcome.error(bookId, failMessage);
            }
        }

        inTransaction(() -> {
            bookRepository.findById(bookId).ifPresent(isbnDiscoveryStatusService::clearRecordedProblem);
            return null;
        });

        emitPhaseProgress(MetadataBatchProgressNotification.PHASE_METADATA_FETCH,
                "Metadata fetch — book " + bookProgressLabel() + "…");
        IsbnMergeOutcome mergeOutcome = mergeByIsbnOutcome(isbn, existing, selectedProviders);
        if (mergeOutcome.failedDueToNetwork()) {
            // Never clear unlocked metadata when all providers failed due to a network outage.
            throw mergeOutcome.firstTransientFailure();
        }
        BookMetadata merged = mergeOutcome.merged();
        if (merged == null) {
            // Verified ISBN with no provider hits (genuine 0-result response): still clear unlocked fields and apply the ISBN.
            // Ambiguous (forceReview) discoveries stay in the review queue.
            if (forceReview || settings.isIsbnFetchReviewBeforeApply()) {
                BookMetadata stub = existing != null ? existing.toBuilder().build() : BookMetadata.builder().build();
                stub.setIsbn13(ParserUtils.toIsbn13(isbn));
                stub.setIsbn10(ParserUtils.toIsbn10(isbn));
                stub.setIsbnVerified(Boolean.FALSE);
                return IsbnFillOutcome.needsReview(bookId, discovery,
                                forceReview
                                        ? "Ambiguous ISBN staged for review (providers returned no metadata)"
                                        : "ISBN fetch staged for review (providers returned no metadata)")
                        .withMetadata(stub);
            }

            BookMetadata isbnOnly = BookMetadata.builder()
                    .isbn13(ParserUtils.toIsbn13(isbn))
                    .isbn10(ParserUtils.toIsbn10(isbn))
                    .isbnVerified(Boolean.TRUE)
                    .build();
            boolean finalDiscovered = discovered;
            return inTransaction(() -> applyMergedMetadata(bookId, isbnOnly, finalDiscovered, settings, false));
        }

        boolean requireReview = forceReview || settings.isIsbnFetchReviewBeforeApply();
        if (requireReview) {
            if (forceReview) {
                merged.setIsbnVerified(Boolean.FALSE);
            }
            return IsbnFillOutcome.needsReview(bookId, discovery,
                            forceReview ? "Ambiguous ISBN staged for review" : "ISBN fetch staged for review")
                    .withMetadata(merged);
        }

        boolean finalDiscovered = discovered;
        return inTransaction(() -> applyMergedMetadata(bookId, merged, finalDiscovered, settings, true));
    }

    private <T> T inTransaction(Supplier<T> action) {
        if (transactionManager == null) {
            return action.get();
        }
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private void recordDiscoveryProblem(
            BookEntity book,
            IsbnDiscoveryResult discovery,
            String failMessage) {
        if (discovery != null
                && (discovery.getStatus() == IsbnDiscoveryResult.Status.NOT_FOUND
                || discovery.getStatus() == IsbnDiscoveryResult.Status.AMBIGUOUS)) {
            isbnDiscoveryStatusService.recordNotFound(book, failMessage);
            return;
        }
        String safeDetail = discovery != null
                && discovery.getStatus() == IsbnDiscoveryResult.Status.OCR_UNAVAILABLE
                ? failMessage
                : "ISBN discovery could not be completed";
        isbnDiscoveryStatusService.recordError(book, safeDetail);
    }

    private IsbnFillOutcome applyMergedMetadata(
            long bookId,
            BookMetadata merged,
            boolean discovered,
            AppSettings settings,
            boolean updateThumbnail) {
        BookEntity book = bookRepository.findById(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        if (book.getMetadata() != null && book.getMetadata().areAllFieldsLocked()) {
            return IsbnFillOutcome.skipped(bookId, "All metadata fields locked");
        }
        boolean multiPass = settings.getIsbnFillMode() == null
                || "MULTI_PASS".equalsIgnoreCase(settings.getIsbnFillMode());
        if (multiPass) {
            bookMetadataService.clearUnlockedMetadata(book);
            book = bookRepository.findById(bookId)
                    .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        }

        MetadataReplaceMode mode = multiPass
                ? MetadataReplaceMode.REPLACE_WHEN_PROVIDED
                : MetadataReplaceMode.REPLACE_MISSING;

        boolean writeBack = settings.isIsbnFileWriteBackEnabled();
        merged.setIsbnVerified(Boolean.TRUE);
        if (writeBack) {
            merged.setIsbnWrittenToFile(Boolean.TRUE);
        }

        MetadataUpdateContext context = MetadataUpdateContext.builder()
                .bookEntity(book)
                .metadataUpdateWrapper(MetadataUpdateWrapper.builder().metadata(merged).build())
                .updateThumbnail(updateThumbnail)
                .mergeCategories(true)
                .mergeMoods(true)
                .mergeTags(true)
                .replaceMode(mode)
                .forceFileWrite(writeBack)
                .build();
        bookMetadataUpdater.setBookMetadata(context);
        java.time.Instant now = java.time.Instant.now();
        book.setLastMetadataFetchAt(now);
        if (Boolean.TRUE.equals(book.getStaged())) {
            book.setStagingCompletedAt(now);
        }
        bookRepository.save(book);

        Book updated = bookMapper.toBookWithDescription(book, true);
        notificationService.sendMessage(Topic.BOOK_METADATA_UPDATE, updated);
        return IsbnFillOutcome.applied(bookId, updated.getMetadata(), discovered);
    }

    private List<MetadataProvider> deriveProviderChain() {
        try {
            MetadataRefreshOptions options = appSettingService.getAppSettings().getDefaultMetadataRefreshOptions();
            if (options != null && options.getFieldOptions() != null) {
                MetadataRefreshOptions.FieldOptions fieldOptions = options.getFieldOptions();
                LinkedHashSet<MetadataProvider> chain = new LinkedHashSet<>();
                Stream.of(
                                fieldOptions.getTitle(), fieldOptions.getSubtitle(), fieldOptions.getDescription(),
                                fieldOptions.getAuthors(), fieldOptions.getPublisher(), fieldOptions.getPublishedDate(),
                                fieldOptions.getSeriesName(), fieldOptions.getIsbn13(), fieldOptions.getIsbn10(),
                                fieldOptions.getCover(), fieldOptions.getCategories(), fieldOptions.getPageCount()
                        )
                        .filter(Objects::nonNull)
                        .forEach(fp -> {
                            if (fp.getP1() != null) chain.add(fp.getP1());
                            if (fp.getP2() != null) chain.add(fp.getP2());
                            if (fp.getP3() != null) chain.add(fp.getP3());
                            if (fp.getP4() != null) chain.add(fp.getP4());
                        });
                // Always try Google as a dense ISBN fallback when configured providers miss.
                chain.add(MetadataProvider.Google);
                if (!chain.isEmpty()) {
                    return new ArrayList<>(chain);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to derive ISBN fill provider chain: {}", e.getMessage());
        }
        return List.of(MetadataProvider.Google, MetadataProvider.Hardcover, MetadataProvider.Amazon);
    }

    static void fillMissingFields(BookMetadata target, BookMetadata source) {
        if (target == null || source == null) {
            return;
        }
        if (isBlank(target.getTitle()) && !isBlank(source.getTitle())) target.setTitle(source.getTitle());
        if (isBlank(target.getSubtitle()) && !isBlank(source.getSubtitle())) target.setSubtitle(source.getSubtitle());
        if (isBlank(target.getPublisher()) && !isBlank(source.getPublisher())) target.setPublisher(source.getPublisher());
        if (target.getPublishedDate() == null && source.getPublishedDate() != null) target.setPublishedDate(source.getPublishedDate());
        if (isBlank(target.getDescription()) && !isBlank(source.getDescription())) target.setDescription(source.getDescription());
        if (isBlank(target.getSeriesName()) && !isBlank(source.getSeriesName())) target.setSeriesName(source.getSeriesName());
        if (target.getSeriesNumber() == null && source.getSeriesNumber() != null) target.setSeriesNumber(source.getSeriesNumber());
        if (target.getSeriesTotal() == null && source.getSeriesTotal() != null) target.setSeriesTotal(source.getSeriesTotal());
        if (isBlank(target.getIsbn13()) && !isBlank(source.getIsbn13())) target.setIsbn13(source.getIsbn13());
        if (isBlank(target.getIsbn10()) && !isBlank(source.getIsbn10())) target.setIsbn10(source.getIsbn10());
        if (target.getPageCount() == null && source.getPageCount() != null) target.setPageCount(source.getPageCount());
        if (isBlank(target.getLanguage()) && !isBlank(source.getLanguage())) target.setLanguage(source.getLanguage());
        if (isBlank(target.getAsin()) && !isBlank(source.getAsin())) target.setAsin(source.getAsin());
        if (isBlank(target.getGoodreadsId()) && !isBlank(source.getGoodreadsId())) target.setGoodreadsId(source.getGoodreadsId());
        if (isBlank(target.getGoogleId()) && !isBlank(source.getGoogleId())) target.setGoogleId(source.getGoogleId());
        if (isBlank(target.getHardcoverId()) && !isBlank(source.getHardcoverId())) target.setHardcoverId(source.getHardcoverId());
        if (isBlank(target.getHardcoverBookId()) && !isBlank(source.getHardcoverBookId())) target.setHardcoverBookId(source.getHardcoverBookId());
        if (isBlank(target.getComicvineId()) && !isBlank(source.getComicvineId())) target.setComicvineId(source.getComicvineId());
        if (isBlank(target.getDoubanId()) && !isBlank(source.getDoubanId())) target.setDoubanId(source.getDoubanId());
        if (isBlank(target.getLubimyczytacId()) && !isBlank(source.getLubimyczytacId())) target.setLubimyczytacId(source.getLubimyczytacId());
        if (isBlank(target.getRanobedbId()) && !isBlank(source.getRanobedbId())) target.setRanobedbId(source.getRanobedbId());
        if (isBlank(target.getAudibleId()) && !isBlank(source.getAudibleId())) target.setAudibleId(source.getAudibleId());
        if (isBlank(target.getNarrator()) && !isBlank(source.getNarrator())) target.setNarrator(source.getNarrator());
        if (target.getAbridged() == null && source.getAbridged() != null) target.setAbridged(source.getAbridged());
        if (target.getAmazonRating() == null && source.getAmazonRating() != null) target.setAmazonRating(source.getAmazonRating());
        if (target.getAmazonReviewCount() == null && source.getAmazonReviewCount() != null) target.setAmazonReviewCount(source.getAmazonReviewCount());
        if (target.getGoodreadsRating() == null && source.getGoodreadsRating() != null) target.setGoodreadsRating(source.getGoodreadsRating());
        if (target.getGoodreadsReviewCount() == null && source.getGoodreadsReviewCount() != null) target.setGoodreadsReviewCount(source.getGoodreadsReviewCount());
        if (target.getHardcoverRating() == null && source.getHardcoverRating() != null) target.setHardcoverRating(source.getHardcoverRating());
        if (target.getHardcoverReviewCount() == null && source.getHardcoverReviewCount() != null) target.setHardcoverReviewCount(source.getHardcoverReviewCount());
        if (target.getDoubanRating() == null && source.getDoubanRating() != null) target.setDoubanRating(source.getDoubanRating());
        if (target.getDoubanReviewCount() == null && source.getDoubanReviewCount() != null) target.setDoubanReviewCount(source.getDoubanReviewCount());
        if (target.getLubimyczytacRating() == null && source.getLubimyczytacRating() != null) target.setLubimyczytacRating(source.getLubimyczytacRating());
        if (target.getRanobedbRating() == null && source.getRanobedbRating() != null) target.setRanobedbRating(source.getRanobedbRating());
        if (target.getAudibleRating() == null && source.getAudibleRating() != null) target.setAudibleRating(source.getAudibleRating());
        if (target.getAudibleReviewCount() == null && source.getAudibleReviewCount() != null) target.setAudibleReviewCount(source.getAudibleReviewCount());
        if (target.getRating() == null && source.getRating() != null) target.setRating(source.getRating());
        if (target.getAgeRating() == null && source.getAgeRating() != null) target.setAgeRating(source.getAgeRating());
        if (isBlank(target.getContentRating()) && !isBlank(source.getContentRating())) target.setContentRating(source.getContentRating());
        if (isBlank(target.getExternalUrl()) && !isBlank(source.getExternalUrl())) target.setExternalUrl(source.getExternalUrl());
        if (target.getAudiobookMetadata() == null && source.getAudiobookMetadata() != null) {
            target.setAudiobookMetadata(source.getAudiobookMetadata());
        }
        if (target.getComicMetadata() == null && source.getComicMetadata() != null) {
            target.setComicMetadata(source.getComicMetadata());
        }
        if (isBlank(target.getThumbnailUrl()) && !isBlank(source.getThumbnailUrl())) target.setThumbnailUrl(source.getThumbnailUrl());
        if ((target.getAuthors() == null || target.getAuthors().isEmpty())
                && source.getAuthors() != null && !source.getAuthors().isEmpty()) {
            target.setAuthors(new ArrayList<>(source.getAuthors()));
        }
        if ((target.getCategories() == null || target.getCategories().isEmpty())
                && source.getCategories() != null && !source.getCategories().isEmpty()) {
            target.setCategories(source.getCategories());
        }
        if ((target.getMoods() == null || target.getMoods().isEmpty())
                && source.getMoods() != null && !source.getMoods().isEmpty()) {
            target.setMoods(source.getMoods());
        }
        if ((target.getTags() == null || target.getTags().isEmpty())
                && source.getTags() != null && !source.getTags().isEmpty()) {
            target.setTags(source.getTags());
        }
        if ((target.getBookReviews() == null || target.getBookReviews().isEmpty())
                && source.getBookReviews() != null && !source.getBookReviews().isEmpty()) {
            target.setBookReviews(new ArrayList<>(source.getBookReviews()));
        }
    }

    private static BookMetadata copyMetadata(BookMetadata source) {
        return source.toBuilder().build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String bookProgressLabel() {
        MetadataTaskContext.TaskContext ctx = MetadataTaskContext.get();
        if (ctx == null || ctx.total() <= 0) {
            return "?";
        }
        return (ctx.completed() + 1) + " of " + ctx.total();
    }

    /**
     * Emits a mid-book phase update for the Tasks progress widget when running under
     * {@link IsbnDiscoveryTask} (MetadataTaskContext set). No-op outside a task.
     */
    void emitPhaseProgress(String phase, String message) {
        MetadataTaskContext.TaskContext ctx = MetadataTaskContext.get();
        if (ctx == null || phase == null || phase.isBlank()) {
            return;
        }
        notificationService.sendMessage(
                Topic.BOOK_METADATA_BATCH_PROGRESS,
                new MetadataBatchProgressNotification(
                        ctx.taskId(),
                        ctx.completed(),
                        ctx.total(),
                        message,
                        MetadataFetchTaskStatus.IN_PROGRESS.name(),
                        ctx.review(),
                        false,
                        null,
                        phase
                )
        );
    }

    public record IsbnFillOutcome(
            long bookId,
            Status status,
            BookMetadata metadata,
            IsbnDiscoveryResult discovery,
            String message,
            boolean discoveredIsbn
    ) {
        public enum Status {
            APPLIED, NEEDS_REVIEW, SKIPPED, DISABLED, ERROR
        }

        static IsbnFillOutcome disabled(long bookId) {
            return new IsbnFillOutcome(bookId, Status.DISABLED, null, null, "ISBN discovery disabled", false);
        }

        static IsbnFillOutcome skipped(long bookId, String message) {
            return new IsbnFillOutcome(bookId, Status.SKIPPED, null, null, message, false);
        }

        static IsbnFillOutcome error(long bookId, String message) {
            return new IsbnFillOutcome(bookId, Status.ERROR, null, null, message, false);
        }

        static IsbnFillOutcome needsReview(long bookId, IsbnDiscoveryResult discovery, String message) {
            return new IsbnFillOutcome(bookId, Status.NEEDS_REVIEW, null, discovery, message, false);
        }

        static IsbnFillOutcome applied(long bookId, BookMetadata metadata, boolean discovered) {
            return new IsbnFillOutcome(bookId, Status.APPLIED, metadata, null, "Applied", discovered);
        }

        IsbnFillOutcome withMetadata(BookMetadata metadata) {
            return new IsbnFillOutcome(bookId, status, metadata, discovery, message, discoveredIsbn);
        }
    }
}
