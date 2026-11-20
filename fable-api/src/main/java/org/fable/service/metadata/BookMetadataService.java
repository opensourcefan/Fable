package org.fable.service.metadata;

import org.fable.exception.ApiError;
import org.fable.mapper.BookMapper;
import org.fable.mapper.BookMetadataMapper;
import org.fable.service.event.aop.BroadcastBookUpdate;
import org.fable.mapper.MetadataClearFlagsMapper;
import org.fable.model.MetadataClearFlags;
import org.fable.model.MetadataUpdateContext;
import org.fable.model.MetadataUpdateWrapper;
import org.fable.model.dto.Book;
import org.fable.model.dto.BookMetadata;
import org.fable.model.dto.ComicMetadata;
import org.fable.model.dto.request.BulkMetadataUpdateRequest;
import org.fable.model.dto.request.FetchMetadataRequest;
import org.fable.model.dto.request.ToggleAllLockRequest;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.BookMetadataEntity;
import org.fable.model.enums.BookFileType;
import org.fable.model.enums.Lock;
import org.fable.model.enums.MetadataProvider;
import org.fable.model.websocket.Topic;
import org.fable.repository.BookMetadataRepository;
import org.fable.repository.BookRepository;
import org.fable.service.NotificationService;
import org.fable.service.book.BookQueryService;
import org.fable.service.metadata.extractor.CbxMetadataExtractor;
import org.fable.service.metadata.extractor.MetadataExtractorFactory;
import org.fable.service.metadata.parser.BookParser;
import org.fable.service.metadata.parser.DetailedMetadataProvider;
import org.fable.service.appsettings.AppSettingService;
import org.fable.model.dto.request.MetadataRefreshOptions;
import org.fable.util.BookUtils;
import org.fable.util.FileUtils;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.fable.model.dto.request.IsbnLookupRequest;
import org.fable.service.book.BookCreatorService;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@AllArgsConstructor
public class BookMetadataService {

    private final BookRepository bookRepository;
    private final BookMapper bookMapper;
    private final BookMetadataMapper bookMetadataMapper;
    private final BookMetadataUpdater bookMetadataUpdater;
    private final NotificationService notificationService;
    private final BookMetadataRepository bookMetadataRepository;
    private final BookQueryService bookQueryService;
    private final Map<MetadataProvider, BookParser> parserMap;
    private final CbxMetadataExtractor cbxMetadataExtractor;
    private final MetadataExtractorFactory metadataExtractorFactory;
    private final MetadataClearFlagsMapper metadataClearFlagsMapper;
    private final PlatformTransactionManager transactionManager;
    private final AppSettingService appSettingService;
    private final BookCreatorService bookCreatorService;


    public Flux<BookMetadata> getProspectiveMetadataListForBookId(long bookId, FetchMetadataRequest request) {
        BookEntity bookEntity = bookRepository.findById(bookId).orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        Book book = bookMapper.toBook(bookEntity);

        return Flux.fromIterable(request.getProviders())
                .flatMap(provider ->
                    Mono.fromCallable(() -> fetchMetadataListFromAProvider(provider, book, request))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMapMany(Flux::fromIterable)
                            .onErrorResume(e -> {
                                log.error("Error fetching metadata from provider: {}", provider, e);
                                return Flux.empty();
                            })
                );
    }

    public List<BookMetadata> fetchMetadataListFromAProvider(MetadataProvider provider, Book book, FetchMetadataRequest request) {
        FetchMetadataRequest requestCopy = copyAndCleanFetchMetadataRequest(request, provider);
        return getParser(provider).fetchMetadata(book, requestCopy);
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


    public BookMetadata lookupByIsbn(IsbnLookupRequest request) {
        List<MetadataProvider> providers = deriveProviderChainFromSettings();

        FetchMetadataRequest fetchRequest = FetchMetadataRequest.builder()
                .isbn(request.getIsbn())
                .providers(providers)
                .build();

        Book emptyBook = Book.builder().build();

        for (MetadataProvider provider : providers) {
            try {
                List<BookMetadata> results = fetchMetadataListFromAProvider(provider, emptyBook, fetchRequest);
                if (results != null && !results.isEmpty()) {
                    return results.getFirst();
                }
            } catch (Exception e) {
                log.warn("ISBN lookup failed for provider {}: {}", provider, e.getMessage());
            }
        }
        return null;
    }

    private List<MetadataProvider> deriveProviderChainFromSettings() {
        try {
            MetadataRefreshOptions options = appSettingService.getAppSettings().getDefaultMetadataRefreshOptions();
            if (options != null && options.getFieldOptions() != null) {
                MetadataRefreshOptions.FieldProvider titleProvider = options.getFieldOptions().getTitle();
                if (titleProvider != null) {
                    List<MetadataProvider> chain = Stream.of(
                                    titleProvider.getP1(), titleProvider.getP2(),
                                    titleProvider.getP3(), titleProvider.getP4())
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList();
                    if (!chain.isEmpty()) {
                        return chain;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to derive provider chain from settings, falling back to default: {}", e.getMessage());
        }
        return List.of(MetadataProvider.Google);
    }

    public BookMetadata getDetailedProviderMetadata(MetadataProvider provider, String providerItemId) {
        BookParser parser = getParser(provider);
        if (parser instanceof DetailedMetadataProvider detailedProvider) {
            return detailedProvider.fetchDetailedMetadata(providerItemId);
        }
        return null;
    }

    private BookParser getParser(MetadataProvider provider) {
        BookParser parser = parserMap.get(provider);
        if (parser == null) {
            throw ApiError.METADATA_SOURCE_NOT_IMPLEMENT_OR_DOES_NOT_EXIST.createException();
        }
        return parser;
    }

    @BroadcastBookUpdate
    @Transactional
    public List<Book> toggleFieldLocks(List<Long> bookIds, Map<String, String> fieldActions) {
        Map<String, String> fieldMapping = Map.of(
                "thumbnailLocked", "coverLocked"
        );
        List<BookEntity> books = bookQueryService.findAllWithMetadataByIds(new HashSet<>(bookIds));

        for (BookEntity book : books) {
            BookMetadataEntity metadataEntity = book.getMetadata();
            fieldActions.forEach((field, action) -> {
                String entityField = fieldMapping.getOrDefault(field, field);
                try {
                    String setterName = "set" + Character.toUpperCase(entityField.charAt(0)) + entityField.substring(1);
                    Method setter = BookMetadataEntity.class.getMethod(setterName, Boolean.class);
                    setter.invoke(metadataEntity, "LOCK".equalsIgnoreCase(action));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to invoke setter for field: " + entityField + " on bookId: " + metadataEntity.getBookId(), e);
                }
            });
        }

        bookRepository.saveAll(books);
        return books.stream().map(b -> bookMapper.toBookWithDescription(b, true)).collect(Collectors.toList());
    }

    @BroadcastBookUpdate
    @Transactional
    public List<Book> toggleAllLock(ToggleAllLockRequest request) {
        boolean lock = request.getLock() == Lock.LOCK;
        List<BookEntity> books = bookQueryService.findAllWithMetadataByIds(request.getBookIds())
                .stream()
                .peek(book -> book.getMetadata().applyLockToAllFields(lock))
                .toList();
        bookRepository.saveAll(books);
        return books.stream().map(b -> bookMapper.toBookWithDescription(b, true)).collect(Collectors.toList());
    }

    public BookMetadata getComicInfoMetadata(long bookId) {
        log.info("Extracting ComicInfo metadata for book ID: {}", bookId);
        BookEntity bookEntity = bookRepository.findById(bookId).orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        var primaryFile = bookEntity.getPrimaryBookFile();
        if (primaryFile == null || primaryFile.getBookType() != BookFileType.CBX) {
            log.info("Unsupported operation for book ID {} - no file or not CBX type", bookId);
            return null;
        }
        return cbxMetadataExtractor.extractMetadata(new File(FileUtils.getBookFullPath(bookEntity)));
    }

    public BookMetadata getFileMetadata(long bookId) {
        log.info("Extracting file metadata for book ID: {}", bookId);
        BookEntity bookEntity = bookRepository.findById(bookId).orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        var primaryFile = bookEntity.getPrimaryBookFile();
        if (primaryFile == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Book has no file to extract metadata from");
        }
        return metadataExtractorFactory.extractMetadata(primaryFile.getBookType(), new File(FileUtils.getBookFullPath(bookEntity)));
    }

    @BroadcastBookUpdate
    @Transactional
    public Book updateMetadata(long bookId, MetadataUpdateWrapper metadataUpdateWrapper, boolean mergeCategories, org.fable.model.enums.MetadataReplaceMode replaceMode) {
        BookEntity bookEntity = bookQueryService.findAllWithMetadataByIds(Collections.singleton(bookId)).stream()
                .findFirst()
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        MetadataUpdateContext context = MetadataUpdateContext.builder()
                .bookEntity(bookEntity)
                .metadataUpdateWrapper(metadataUpdateWrapper)
                .updateThumbnail(true)
                .mergeCategories(mergeCategories)
                .replaceMode(replaceMode)
                .mergeMoods(false)
                .mergeTags(false)
                .build();

        bookMetadataUpdater.setBookMetadata(context);
        if (Boolean.TRUE.equals(bookEntity.getStaged()) && !isFollowUpTagOnlyUpdate(metadataUpdateWrapper)) {
            bookEntity.setStagingCompletedAt(java.time.Instant.now());
        }
        bookRepository.save(bookEntity);
        return bookMapper.toBookWithDescription(bookEntity, true);
    }

    @BroadcastBookUpdate
    @Transactional
    public List<Book> bulkUpdateMetadata(BulkMetadataUpdateRequest request, boolean mergeCategories, boolean mergeMoods, boolean mergeTags) {
        MetadataClearFlags clearFlags = metadataClearFlagsMapper.toClearFlags(request);

        BookMetadata bookMetadata = BookMetadata.builder()
                .authors(request.getAuthors())
                .publisher(request.getPublisher())
                .language(request.getLanguage())
                .seriesName(request.getSeriesName())
                .seriesTotal(request.getSeriesTotal())
                .publishedDate(request.getPublishedDate())
                .categories(request.getGenres() != null ? request.getGenres() : Collections.emptySet())
                .moods(request.getMoods() != null ? request.getMoods() : Collections.emptySet())
                .tags(request.getTags() != null ? request.getTags() : Collections.emptySet())
                .ageRating(request.getAgeRating())
                .contentRating(request.getContentRating())
                .build();

        List<Book> updatedBooks = new ArrayList<>();
        for (Long bookId : request.getBookIds()) {
            try {
                Book book = processSingleBookUpdate(bookId, bookMetadata, clearFlags, mergeCategories, mergeMoods, mergeTags);
                if (book != null) updatedBooks.add(book);
            } catch (Exception e) {
                log.error("Failed to update metadata for book ID {}", bookId, e);
            }
        }
        return updatedBooks;
    }

    @BroadcastBookUpdate
    @Transactional
    public Book wipeBookMetadata(long bookId) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        wipeBookMetadata(book);
        return bookMapper.toBookWithDescription(book, true);
    }

    @BroadcastBookUpdate
    @Transactional
    public List<Book> wipeBookMetadata(Set<Long> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Book> updatedBooks = new ArrayList<>();
        for (Long bookId : bookIds) {
            Book book = processSingleBookWipe(bookId);
            if (book != null) updatedBooks.add(book);
        }
        return updatedBooks;
    }

    @BroadcastBookUpdate
    public List<Book> restoreTitlesFromFilename(Set<Long> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Book> restoredBooks = new ArrayList<>();
        for (Long bookId : bookIds) {
            Book book = processSingleTitleRestore(bookId);
            if (book != null) {
                restoredBooks.add(book);
            }
        }
        return restoredBooks;
    }

    private Book processSingleBookUpdate(Long bookId, BookMetadata bookMetadata, MetadataClearFlags clearFlags, boolean mergeCategories, boolean mergeMoods, boolean mergeTags) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate.execute(status -> {
            BookEntity book = bookRepository.findByIdWithBookFiles(bookId).orElse(null);
            if (book == null) {
                log.warn("Book not found for metadata update: {}", bookId);
                return null;
            }

            MetadataUpdateContext context = MetadataUpdateContext.builder()
                    .bookEntity(book)
                    .metadataUpdateWrapper(MetadataUpdateWrapper.builder()
                            .metadata(bookMetadata)
                            .clearFlags(clearFlags)
                            .build())
                    .updateThumbnail(false)
                    .mergeCategories(mergeCategories)
                    .mergeMoods(mergeMoods)
                    .mergeTags(mergeTags)
                    .build();

            bookMetadataUpdater.setBookMetadata(context);
            if (Boolean.TRUE.equals(book.getStaged()) && !isFollowUpTagOnlyUpdate(context.getMetadataUpdateWrapper())) {
                book.setStagingCompletedAt(java.time.Instant.now());
            }
            return bookMapper.toBookWithDescription(book, true);
        });
    }

    private Book processSingleBookWipe(Long bookId) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate.execute(status -> {
            BookEntity book = bookRepository.findByIdWithBookFiles(bookId).orElse(null);
            if (book == null) {
                log.warn("Book not found for metadata wipe: {}", bookId);
                return null;
            }

            wipeBookMetadata(book);
            return bookMapper.toBookWithDescription(book, true);
        });
    }

    private Book processSingleTitleRestore(Long bookId) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate.execute(status -> {
            BookEntity book = bookRepository.findByIdWithBookFiles(bookId).orElse(null);
            if (book == null) {
                log.warn("Book not found for title restore: {}", bookId);
                return null;
            }

            BookMetadataEntity metadata = book.getMetadata();
            String fallbackTitle = resolveFilenameFallbackTitle(book);
            if (metadata == null || fallbackTitle == null || fallbackTitle.isBlank()) {
                return null;
            }
            if (Boolean.TRUE.equals(metadata.getTitleLocked())) {
                return null;
            }
            if (metadata.getTitle() != null && !metadata.getTitle().isBlank()) {
                return null;
            }

            MetadataUpdateContext context = MetadataUpdateContext.builder()
                    .bookEntity(book)
                    .metadataUpdateWrapper(MetadataUpdateWrapper.builder()
                            .metadata(BookMetadata.builder()
                                    .bookId(metadata.getBookId())
                                    .title(fallbackTitle)
                                    .build())
                            .build())
                    .updateThumbnail(false)
                    .mergeCategories(false)
                    .mergeMoods(false)
                    .mergeTags(false)
                    .replaceMode(org.fable.model.enums.MetadataReplaceMode.REPLACE_WHEN_PROVIDED)
                    .build();

            bookMetadataUpdater.setBookMetadata(context);
            return bookMapper.toBookWithDescription(book, true);
        });
    }

    private static final String METADATA_FOLLOW_UP_TAG = "Metadata Follow-Up Req";

    private boolean isFollowUpTagOnlyUpdate(MetadataUpdateWrapper wrapper) {
        if (wrapper == null || wrapper.getMetadata() == null) {
            return false;
        }
        BookMetadata m = wrapper.getMetadata();
        if (m.getTags() == null || !m.getTags().contains(METADATA_FOLLOW_UP_TAG)) {
            return false;
        }
        MetadataClearFlags cf = wrapper.getClearFlags();
        if (cf != null && (cf.isTitle() || cf.isSubtitle() || cf.isPublisher() || cf.isPublishedDate()
                || cf.isDescription() || cf.isSeriesName() || cf.isSeriesNumber() || cf.isSeriesTotal()
                || cf.isIsbn13() || cf.isIsbn10() || cf.isPageCount() || cf.isLanguage()
                || cf.isAuthors() || cf.isCategories() || cf.isMoods() || cf.isTags())) {
            return false;
        }
        return m.getTitle() == null
                && m.getSubtitle() == null
                && m.getPublisher() == null
                && m.getPublishedDate() == null
                && m.getDescription() == null
                && m.getSeriesName() == null
                && m.getSeriesNumber() == null
                && m.getSeriesTotal() == null
                && m.getIsbn13() == null
                && m.getIsbn10() == null
                && m.getPageCount() == null
                && m.getLanguage() == null
                && m.getThumbnailUrl() == null
                && (m.getAuthors() == null || m.getAuthors().isEmpty())
                && (m.getCategories() == null || m.getCategories().isEmpty())
                && (m.getMoods() == null || m.getMoods().isEmpty());
    }

    private void wipeBookMetadata(BookEntity book) {
        BookMetadataEntity metadata = book.getMetadata();
        book.setLastMetadataFetchAt(null);
        book.setStagingCompletedAt(null);
        book.setIsbnDiscoveryStatus(null);
        book.setIsbnDiscoveryCheckedAt(null);
        book.setIsbnDiscoveryDetail(null);
        if (metadata == null) {
            return;
        }

        // Completed Staging triage keys off lastMetadataFetchAt / isbnVerified.
        // A full wipe should return the book to the Staged inbox.
        metadata.setIsbnVerified(Boolean.FALSE);
        metadata.setIsbnWrittenToFile(Boolean.FALSE);

        String fallbackTitle = resolveFilenameFallbackTitle(book);

        metadata.applyLockToAllFields(false);
        metadata.setRating(null);
        metadata.setReviewCount(null);
        metadata.setEmbeddingVector(null);
        metadata.setEmbeddingUpdatedAt(null);

        MetadataUpdateContext context = MetadataUpdateContext.builder()
                .bookEntity(book)
                .metadataUpdateWrapper(buildMetadataWipeWrapper(metadata.getBookId(), fallbackTitle))
                .updateThumbnail(false)
                .mergeCategories(false)
                .mergeMoods(false)
                .mergeTags(false)
                .replaceMode(org.fable.model.enums.MetadataReplaceMode.REPLACE_ALL)
                .build();

        bookMetadataUpdater.setBookMetadata(context);
    }

    /**
     * Clears unlocked metadata fields only. Unlike {@link #wipeBookMetadata(long)}, this
     * never unlocks locked fields. Used by ISBN multi-pass fill as a safe pre-wipe.
     */
    @Transactional
    @BroadcastBookUpdate
    public Book clearUnlockedMetadata(long bookId) {
        BookEntity book = bookRepository.findById(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        clearUnlockedMetadata(book);
        return bookMapper.toBookWithDescription(book, true);
    }

    void clearUnlockedMetadata(BookEntity book) {
        BookMetadataEntity metadata = book.getMetadata();
        if (metadata == null) {
            return;
        }

        MetadataClearFlags clearFlags = buildClearUnlockedFlags(metadata);
        BookMetadata empty = BookMetadata.builder()
                .bookId(metadata.getBookId())
                .build();

        MetadataUpdateContext context = MetadataUpdateContext.builder()
                .bookEntity(book)
                .metadataUpdateWrapper(MetadataUpdateWrapper.builder()
                        .metadata(empty)
                        .clearFlags(clearFlags)
                        .build())
                .updateThumbnail(false)
                .mergeCategories(false)
                .mergeMoods(false)
                .mergeTags(false)
                .replaceMode(org.fable.model.enums.MetadataReplaceMode.REPLACE_ALL)
                .build();

        bookMetadataUpdater.setBookMetadata(context);
    }

    static MetadataClearFlags buildClearUnlockedFlags(BookMetadataEntity metadata) {
        MetadataClearFlags clearFlags = new MetadataClearFlags();
        clearFlags.setTitle(!Boolean.TRUE.equals(metadata.getTitleLocked()));
        clearFlags.setSubtitle(!Boolean.TRUE.equals(metadata.getSubtitleLocked()));
        clearFlags.setPublisher(!Boolean.TRUE.equals(metadata.getPublisherLocked()));
        clearFlags.setPublishedDate(!Boolean.TRUE.equals(metadata.getPublishedDateLocked()));
        clearFlags.setDescription(!Boolean.TRUE.equals(metadata.getDescriptionLocked()));
        clearFlags.setSeriesName(!Boolean.TRUE.equals(metadata.getSeriesNameLocked()));
        clearFlags.setSeriesNumber(!Boolean.TRUE.equals(metadata.getSeriesNumberLocked()));
        clearFlags.setSeriesTotal(!Boolean.TRUE.equals(metadata.getSeriesTotalLocked()));
        clearFlags.setIsbn13(!Boolean.TRUE.equals(metadata.getIsbn13Locked()));
        clearFlags.setIsbn10(!Boolean.TRUE.equals(metadata.getIsbn10Locked()));
        clearFlags.setAsin(!Boolean.TRUE.equals(metadata.getAsinLocked()));
        clearFlags.setGoodreadsId(!Boolean.TRUE.equals(metadata.getGoodreadsIdLocked()));
        clearFlags.setComicvineId(!Boolean.TRUE.equals(metadata.getComicvineIdLocked()));
        clearFlags.setHardcoverId(!Boolean.TRUE.equals(metadata.getHardcoverIdLocked()));
        clearFlags.setHardcoverBookId(!Boolean.TRUE.equals(metadata.getHardcoverBookIdLocked()));
        clearFlags.setGoogleId(!Boolean.TRUE.equals(metadata.getGoogleIdLocked()));
        clearFlags.setPageCount(!Boolean.TRUE.equals(metadata.getPageCountLocked()));
        clearFlags.setLanguage(!Boolean.TRUE.equals(metadata.getLanguageLocked()));
        clearFlags.setAmazonRating(!Boolean.TRUE.equals(metadata.getAmazonRatingLocked()));
        clearFlags.setAmazonReviewCount(!Boolean.TRUE.equals(metadata.getAmazonReviewCountLocked()));
        clearFlags.setGoodreadsRating(!Boolean.TRUE.equals(metadata.getGoodreadsRatingLocked()));
        clearFlags.setGoodreadsReviewCount(!Boolean.TRUE.equals(metadata.getGoodreadsReviewCountLocked()));
        clearFlags.setHardcoverRating(!Boolean.TRUE.equals(metadata.getHardcoverRatingLocked()));
        clearFlags.setHardcoverReviewCount(!Boolean.TRUE.equals(metadata.getHardcoverReviewCountLocked()));
        clearFlags.setLubimyczytacId(!Boolean.TRUE.equals(metadata.getLubimyczytacIdLocked()));
        clearFlags.setLubimyczytacRating(!Boolean.TRUE.equals(metadata.getLubimyczytacRatingLocked()));
        clearFlags.setRanobedbId(!Boolean.TRUE.equals(metadata.getRanobedbIdLocked()));
        clearFlags.setRanobedbRating(!Boolean.TRUE.equals(metadata.getRanobedbRatingLocked()));
        clearFlags.setAudibleId(!Boolean.TRUE.equals(metadata.getAudibleIdLocked()));
        clearFlags.setAudibleRating(!Boolean.TRUE.equals(metadata.getAudibleRatingLocked()));
        clearFlags.setAudibleReviewCount(!Boolean.TRUE.equals(metadata.getAudibleReviewCountLocked()));
        clearFlags.setAuthors(!Boolean.TRUE.equals(metadata.getAuthorsLocked()));
        clearFlags.setCategories(!Boolean.TRUE.equals(metadata.getCategoriesLocked()));
        clearFlags.setMoods(!Boolean.TRUE.equals(metadata.getMoodsLocked()));
        clearFlags.setTags(!Boolean.TRUE.equals(metadata.getTagsLocked()));
        clearFlags.setReviews(!Boolean.TRUE.equals(metadata.getReviewsLocked()));
        clearFlags.setNarrator(!Boolean.TRUE.equals(metadata.getNarratorLocked()));
        clearFlags.setAbridged(!Boolean.TRUE.equals(metadata.getAbridgedLocked()));
        clearFlags.setAgeRating(!Boolean.TRUE.equals(metadata.getAgeRatingLocked()));
        clearFlags.setContentRating(!Boolean.TRUE.equals(metadata.getContentRatingLocked()));
        clearFlags.setCover(false);
        clearFlags.setAudiobookCover(false);
        return clearFlags;
    }

    private String resolveFilenameFallbackTitle(BookEntity book) {
        var primaryFile = book.getPrimaryBookFile();
        if (primaryFile == null) {
            return null;
        }

        return FileUtils.deriveTitleFromFileName(primaryFile.getFileName(), primaryFile.isFolderBased());
    }

    private MetadataUpdateWrapper buildMetadataWipeWrapper(Long bookId, String fallbackTitle) {
        MetadataClearFlags clearFlags = new MetadataClearFlags();
        clearFlags.setTitle(fallbackTitle == null || fallbackTitle.isBlank());
        clearFlags.setSubtitle(true);
        clearFlags.setPublisher(true);
        clearFlags.setPublishedDate(true);
        clearFlags.setDescription(true);
        clearFlags.setSeriesName(true);
        clearFlags.setSeriesNumber(true);
        clearFlags.setSeriesTotal(true);
        clearFlags.setIsbn13(true);
        clearFlags.setIsbn10(true);
        clearFlags.setAsin(true);
        clearFlags.setGoodreadsId(true);
        clearFlags.setComicvineId(true);
        clearFlags.setHardcoverId(true);
        clearFlags.setHardcoverBookId(true);
        clearFlags.setGoogleId(true);
        clearFlags.setPageCount(true);
        clearFlags.setLanguage(true);
        clearFlags.setAmazonRating(true);
        clearFlags.setAmazonReviewCount(true);
        clearFlags.setGoodreadsRating(true);
        clearFlags.setGoodreadsReviewCount(true);
        clearFlags.setHardcoverRating(true);
        clearFlags.setHardcoverReviewCount(true);
        clearFlags.setLubimyczytacId(true);
        clearFlags.setLubimyczytacRating(true);
        clearFlags.setRanobedbId(true);
        clearFlags.setRanobedbRating(true);
        clearFlags.setAudibleId(true);
        clearFlags.setAudibleRating(true);
        clearFlags.setAudibleReviewCount(true);
        clearFlags.setAuthors(true);
        clearFlags.setCategories(true);
        clearFlags.setMoods(true);
        clearFlags.setTags(true);
        clearFlags.setReviews(true);
        clearFlags.setNarrator(true);
        clearFlags.setAbridged(true);
        clearFlags.setAgeRating(true);
        clearFlags.setContentRating(true);

        ComicMetadata comicMetadata = ComicMetadata.builder()
                .issueNumber(null)
                .volumeName(null)
                .volumeNumber(null)
                .storyArc(null)
                .storyArcNumber(null)
                .alternateSeries(null)
                .alternateIssue(null)
                .pencillers(Collections.emptySet())
                .inkers(Collections.emptySet())
                .colorists(Collections.emptySet())
                .letterers(Collections.emptySet())
                .coverArtists(Collections.emptySet())
                .editors(Collections.emptySet())
                .imprint(null)
                .format(null)
                .blackAndWhite(null)
                .manga(null)
                .readingDirection(null)
                .characters(Collections.emptySet())
                .teams(Collections.emptySet())
                .locations(Collections.emptySet())
                .webLink(null)
                .notes(null)
                .build();

        BookMetadata metadata = BookMetadata.builder()
                .bookId(bookId)
            .title(fallbackTitle)
                .authors(Collections.emptyList())
                .categories(Collections.emptySet())
                .moods(Collections.emptySet())
                .tags(Collections.emptySet())
                .comicMetadata(comicMetadata)
                .build();

        return MetadataUpdateWrapper.builder()
                .metadata(metadata)
                .clearFlags(clearFlags)
                .build();
    }
}
