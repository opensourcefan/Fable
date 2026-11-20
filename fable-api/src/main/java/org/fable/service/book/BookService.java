package org.fable.service.book;

import org.fable.config.security.service.AuthenticationService;
import org.fable.exception.ApiError;
import org.fable.mapper.BookMapper;
import org.fable.model.dto.*;
import org.fable.model.dto.request.ReadProgressRequest;
import org.fable.model.dto.response.BookDeletionResponse;
import org.fable.model.dto.response.BookStatusUpdateResponse;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.BookFileEntity;
import org.fable.model.entity.LibraryPathEntity;
import org.fable.model.entity.ShelfEntity;
import org.fable.model.entity.UserBookFileProgressEntity;
import org.fable.model.entity.UserBookProgressEntity;
import org.fable.model.enums.BookFileType;
import org.fable.model.enums.RemoveFromLibraryMode;
import org.fable.repository.*;
import org.fable.repository.ComicPanelFlowRepository;
import org.fable.service.metadata.sidecar.SidecarMetadataWriter;
import org.fable.service.monitoring.MonitoringRegistrationService;
import org.fable.service.progress.ReadingProgressService;
import org.fable.service.FileStreamingService;
import org.fable.util.FileService;
import org.fable.util.FileUtils;
import org.fable.service.appsettings.AppSettingService;
import org.fable.service.library.LibraryVisibilityService;
import org.fable.app.dto.AppBookGridSummary;
import org.fable.app.dto.AppPageResponse;
import org.fable.app.specification.AppBookSpecification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.fable.model.enums.AuditAction;
import org.fable.service.audit.AuditService;

@Slf4j
@AllArgsConstructor
@Service
public class BookService {

    private static final Map<String, String> PAGED_SORT_FIELD_ALIASES = Map.ofEntries(
        Map.entry("title", "metadata.title"),
        Map.entry("seriesName", "metadata.seriesName"),
        Map.entry("seriesNumber", "metadata.seriesNumber"),
        Map.entry("publisher", "metadata.publisher"),
        Map.entry("publishedDate", "metadata.publishedDate"),
        Map.entry("pageCount", "metadata.pageCount"),
        Map.entry("rating", "metadata.rating"),
        Map.entry("amazonRating", "metadata.amazonRating"),
        Map.entry("amazonReviewCount", "metadata.amazonReviewCount"),
        Map.entry("goodreadsRating", "metadata.goodreadsRating"),
        Map.entry("goodreadsReviewCount", "metadata.goodreadsReviewCount"),
        Map.entry("hardcoverRating", "metadata.hardcoverRating"),
        Map.entry("hardcoverReviewCount", "metadata.hardcoverReviewCount"),
        Map.entry("ranobedbRating", "metadata.ranobedbRating"),
        Map.entry("narrator", "metadata.narrator")
    );

    private static final Set<String> SUPPORTED_PAGED_SORT_FIELDS = Set.of(
        "addedOn",
        "fileName",
        "metadata.title",
        "metadata.seriesName",
        "metadata.seriesNumber",
        "metadata.publisher",
        "metadata.publishedDate",
        "metadata.pageCount",
        "metadata.rating",
        "metadata.amazonRating",
        "metadata.amazonReviewCount",
        "metadata.goodreadsRating",
        "metadata.goodreadsReviewCount",
        "metadata.hardcoverRating",
        "metadata.hardcoverReviewCount",
        "metadata.ranobedbRating",
        "metadata.narrator",
        "personalRating",
        "lastReadTime",
        "dateFinished",
        "readStatus"
    );

    public record MediaResource(Resource resource, MediaType mediaType) {
    }

    private final BookRepository bookRepository;
    private final PdfViewerPreferencesRepository pdfViewerPreferencesRepository;
    private final CbxViewerPreferencesRepository cbxViewerPreferencesRepository;
    private final NewPdfViewerPreferencesRepository newPdfViewerPreferencesRepository;
    private final FileService fileService;
    private final BookMapper bookMapper;
    private final UserBookProgressRepository userBookProgressRepository;
    private final AuthenticationService authenticationService;
    private final BookQueryService bookQueryService;
    private final ReadingProgressService readingProgressService;
    private final org.fable.service.ai.AiSearchHealthService aiSearchHealthService;
    private final ComicPanelFlowRepository comicPanelFlowRepository;
    private final BookDownloadService bookDownloadService;
    private final MonitoringRegistrationService monitoringRegistrationService;
    private final BookUpdateService bookUpdateService;
    private final EbookViewerPreferenceRepository ebookViewerPreferencesRepository;
    private final ShelfRepository shelfRepository;
    private final SidecarMetadataWriter sidecarMetadataWriter;
    private final FileStreamingService fileStreamingService;
    private final AuditService auditService;
    private final AppSettingService appSettingService;
    private final LibraryVisibilityService libraryVisibilityService;


    public List<Book> getBookDTOs(boolean includeDescription, boolean stripForListView) {
        FableUser user = getAuthenticatedOrSystemUser();
        Set<Long> accessibleLibraryIds = libraryVisibilityService.getAccessibleLibraryIds(user);

        List<Book> books = bookQueryService.getAllBooksByLibraryIds(
                accessibleLibraryIds,
                includeDescription,
                stripForListView,
                user.getId()
        );

        Set<Long> bookIds = books.stream().map(Book::getId).collect(Collectors.toSet());
        Map<Long, UserBookProgressEntity> progressMap =
                readingProgressService.fetchUserProgress(user.getId(), bookIds);
        Map<Long, UserBookFileProgressEntity> fileProgressMap =
                readingProgressService.fetchUserFileProgress(user.getId(), bookIds);

        books.forEach(book -> {
            readingProgressService.enrichBookWithProgress(
                    book,
                    progressMap.get(book.getId()),
                    fileProgressMap.get(book.getId())
            );
            Set<Shelf> filtered = filterShelvesByUserId(book.getShelves(), user.getId());
            book.setShelves(!includeDescription && filtered != null && filtered.isEmpty() ? null : filtered);
        });

        applyAiPanelFlags(books, user.getId());
        applyAiSearchFlags(books, user.getId());

        return books;
    }

    private Set<Long> getUserLibraryIds(FableUser user) {
        return libraryVisibilityService.getAccessibleLibraryIds(user);
    }

    public List<Book> getBooksByIds(Set<Long> bookIds, boolean withDescription) {
        FableUser user = getAuthenticatedOrSystemUser();
        Set<Long> userLibraryIds = getUserLibraryIds(user);

        List<BookEntity> bookEntities = bookQueryService.findAllWithMetadataByIds(bookIds);

        bookEntities = bookEntities.stream()
                .filter(book -> userLibraryIds.contains(book.getLibrary().getId()))
                .toList();

        Set<Long> entityIds = bookEntities.stream().map(BookEntity::getId).collect(Collectors.toSet());

        Map<Long, UserBookProgressEntity> progressMap =
                readingProgressService.fetchUserProgress(user.getId(), entityIds);
        Map<Long, UserBookFileProgressEntity> fileProgressMap =
                readingProgressService.fetchUserFileProgress(user.getId(), entityIds);

        return bookEntities.stream().map(bookEntity -> {
            Book book = bookMapper.toBook(bookEntity);
            if (!withDescription) book.getMetadata().setDescription(null);
            readingProgressService.enrichBookWithProgress(
                    book,
                    progressMap.get(bookEntity.getId()),
                    fileProgressMap.get(bookEntity.getId())
            );
            return book;
        }).collect(Collectors.collectingAndThen(Collectors.toList(), books -> {
            applyAiPanelFlags(books, user.getId());
            applyAiSearchFlags(books, user.getId());
            return books;
        }));
    }

    public Book getBook(long bookId, boolean withDescription) {
        FableUser user = getAuthenticatedOrSystemUser();
        BookEntity bookEntity = bookRepository.findByIdWithBookFiles(bookId).orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        UserBookProgressEntity userProgress = userBookProgressRepository.findByUserIdAndBookId(user.getId(), bookId)
                .orElse(new UserBookProgressEntity());

        // Fetch file-level progress for the book (most recent across all files)
        UserBookFileProgressEntity fileProgress = readingProgressService
                .fetchUserFileProgress(user.getId(), Set.of(bookId))
                .get(bookId);

        Book book = bookMapper.toBook(bookEntity);
        book.setShelves(filterShelvesByUserId(book.getShelves(), user.getId()));
        readingProgressService.enrichBookWithProgress(book, userProgress, fileProgress);

        if (!withDescription) {
            book.getMetadata().setDescription(null);
        }

        applyAiPanelFlags(List.of(book), user.getId());
        applyAiSearchFlags(List.of(book), user.getId());

        return book;
    }

    public void enrichBooksWithAiFlags(List<Book> books, Long userId) {
        applyAiPanelFlags(books, userId);
        applyAiSearchFlags(books, userId);
    }

    private void applyAiPanelFlags(List<Book> books, Long userId) {
        if (books == null || books.isEmpty()) {
            return;
        }

        Set<Long> bookIds = books.stream()
                .map(Book::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (bookIds.isEmpty()) {
            return;
        }

        Set<Long> scannedBookIds = new HashSet<>(
                comicPanelFlowRepository.findScannedBookIdsByUserIdAndBookIdIn(userId, bookIds)
        );

        books.forEach(book -> book.setHasAiPanelData(book.getId() != null && scannedBookIds.contains(book.getId())));
    }

    private void applyAiSearchFlags(List<Book> books, Long userId) {
        if (books == null || books.isEmpty()) {
            return;
        }

        Set<Long> bookIds = books.stream()
                .map(Book::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (bookIds.isEmpty()) {
            return;
        }

        List<org.fable.repository.projection.AiSearchBookStatusProjection> statuses = bookRepository.findBookIdsWithAiSearchEmbeddings(userId, bookIds);
        Map<Long, String> embeddedBookModels = statuses.stream()
                .collect(Collectors.toMap(
                    org.fable.repository.projection.AiSearchBookStatusProjection::getBookId,
                    org.fable.repository.projection.AiSearchBookStatusProjection::getEmbeddingModel,
                    (v1, v2) -> v1 // In case of duplicates, keep the first
                ));

        Map<Long, String> modelMap = statuses.stream()
                .collect(Collectors.toMap(
                    org.fable.repository.projection.AiSearchBookStatusProjection::getBookId,
                    org.fable.repository.projection.AiSearchBookStatusProjection::getEmbeddingModel,
                    (v1, v2) -> v1 // In case of duplicates, keep the first
                ));

        final String activeModel = (aiSearchHealthService != null && aiSearchHealthService.getStatus() != null)
            ? aiSearchHealthService.getStatus().getEmbeddingModel()
            : null;

        for (Book book : books) {
            String embeddedModel = modelMap.get(book.getId());
            if (embeddedModel == null) {
                book.setHasAiSearchData(false);
                book.setHasMismatchedAiSearchData(false);
                book.setAiSearchEmbeddingModel(null);
            } else {
                book.setHasAiSearchData(true);
                boolean isMismatch = activeModel != null && !activeModel.equals(embeddedModel);
                book.setHasMismatchedAiSearchData(isMismatch);
                book.setAiSearchEmbeddingModel(embeddedModel);
            }
        }
    }

    private void applyAiSearchFlagsToGridSummaries(List<AppBookGridSummary> summaries, Long userId) {
        if (summaries == null || summaries.isEmpty()) {
            return;
        }
        Set<Long> bookIds = summaries.stream()
                .map(AppBookGridSummary::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (bookIds.isEmpty()) {
            return;
        }

        List<org.fable.repository.projection.AiSearchBookStatusProjection> statuses = bookRepository.findBookIdsWithAiSearchEmbeddings(userId, bookIds);
        Map<Long, String> embeddedBookModels = statuses.stream()
                .collect(Collectors.toMap(
                    org.fable.repository.projection.AiSearchBookStatusProjection::getBookId,
                    org.fable.repository.projection.AiSearchBookStatusProjection::getEmbeddingModel,
                    (v1, v2) -> v1 // In case of duplicates, keep the first
                ));

        final String activeModel = (aiSearchHealthService != null && aiSearchHealthService.getStatus() != null)
            ? aiSearchHealthService.getStatus().getEmbeddingModel()
            : null;

        summaries.forEach(summary -> {
            if (summary.getId() == null || !embeddedBookModels.containsKey(summary.getId())) {
                summary.setHasAiSearchData(false);
                summary.setHasMismatchedAiSearchData(false);
                summary.setAiSearchEmbeddingModel(null);
            } else {
                summary.setHasAiSearchData(true);
                String bookModel = embeddedBookModels.get(summary.getId());
                boolean isMismatch = activeModel != null && bookModel != null && !activeModel.equalsIgnoreCase(bookModel);
                summary.setHasMismatchedAiSearchData(isMismatch);
                summary.setAiSearchEmbeddingModel(bookModel);
            }
        });
    }

    private void applyAiPanelFlagsToGridSummaries(List<AppBookGridSummary> summaries, Long userId) {
        if (summaries == null || summaries.isEmpty()) {
            return;
        }

        Set<Long> bookIds = summaries.stream()
                .map(AppBookGridSummary::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (bookIds.isEmpty()) {
            return;
        }

        Set<Long> scannedBookIds = new HashSet<>(
                comicPanelFlowRepository.findScannedBookIdsByUserIdAndBookIdIn(userId, bookIds)
        );

        summaries.forEach(summary -> summary.setHasAiPanelData(summary.getId() != null && scannedBookIds.contains(summary.getId())));
    }


    public BookViewerSettings getBookViewerSetting(long bookId, long bookFileId) {
        BookEntity bookEntity = bookRepository.findByIdWithBookFiles(bookId).orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        FableUser user = getAuthenticatedOrSystemUser();

        BookViewerSettings.BookViewerSettingsBuilder settingsBuilder = BookViewerSettings.builder();

        BookFileEntity bookFile = bookEntity.getBookFiles().stream()
                .filter(bf -> bf.getId().equals(bookFileId))
                .findFirst()
                .orElseThrow(() -> ApiError.FILE_NOT_FOUND.createException("Book file not found: " + bookFileId));
        BookFileType bookType = bookFile.getBookType();
        if (bookType == BookFileType.EPUB || bookType == BookFileType.FB2
                || bookType == BookFileType.MOBI
                || bookType == BookFileType.AZW3) {
            ebookViewerPreferencesRepository.findByBookIdAndUserId(bookId, user.getId())
                    .ifPresent(epubPref -> settingsBuilder.ebookSettings(EbookViewerPreferences.builder()
                            .bookId(bookId)
                            .userId(user.getId())
                            .fontFamily(epubPref.getFontFamily())
                            .fontSize(epubPref.getFontSize())
                            .gap(epubPref.getGap())
                            .hyphenate(epubPref.getHyphenate())
                            .isDark(epubPref.getIsDark())
                            .justify(epubPref.getJustify())
                            .lineHeight(epubPref.getLineHeight())
                            .maxBlockSize(epubPref.getMaxBlockSize())
                            .maxColumnCount(epubPref.getMaxColumnCount())
                            .maxInlineSize(epubPref.getMaxInlineSize())
                            .theme(epubPref.getTheme())
                            .flow(epubPref.getFlow())
                            .build()));
        } else if (bookType == BookFileType.PDF) {
            pdfViewerPreferencesRepository.findByBookIdAndUserId(bookId, user.getId())
                    .ifPresent(pdfPref -> settingsBuilder.pdfSettings(PdfViewerPreferences.builder()
                            .bookId(bookId)
                            .zoom(pdfPref.getZoom())
                            .spread(pdfPref.getSpread())
                            .build()));
            newPdfViewerPreferencesRepository.findByBookIdAndUserId(bookId, user.getId())
                    .ifPresent(pdfPref -> settingsBuilder.newPdfSettings(NewPdfViewerPreferences.builder()
                            .bookId(bookId)
                            .pageViewMode(pdfPref.getPageViewMode())
                            .pageSpread(pdfPref.getPageSpread())
                            .fitMode(pdfPref.getFitMode())
                            .scrollMode(pdfPref.getScrollMode())
                            .backgroundColor(pdfPref.getBackgroundColor())
                            .build()));
        } else if (bookType == BookFileType.CBX) {
            cbxViewerPreferencesRepository.findByBookIdAndUserId(bookId, user.getId())
                    .ifPresent(cbxPref -> settingsBuilder.cbxSettings(CbxViewerPreferences.builder()
                            .bookId(bookId)
                            .pageViewMode(cbxPref.getPageViewMode())
                            .pageSpread(cbxPref.getPageSpread())
                            .fitMode(cbxPref.getFitMode())
                            .scrollMode(cbxPref.getScrollMode())
                            .backgroundColor(cbxPref.getBackgroundColor())
                            .build()));
        } else {
            throw ApiError.UNSUPPORTED_BOOK_TYPE.createException();
        }
        return settingsBuilder.build();
    }

    public void updateBookViewerSetting(long bookId, BookViewerSettings bookViewerSettings) {
        bookUpdateService.updateBookViewerSetting(bookId, bookViewerSettings);
    }

    @Transactional
    public void updateReadProgress(ReadProgressRequest request) {
        readingProgressService.updateReadProgress(request);
    }

    @Transactional
    public List<BookStatusUpdateResponse> updateReadStatus(List<Long> bookIds, String status) {
        return bookUpdateService.updateReadStatus(bookIds, status);
    }

    @Transactional
    public List<Book> assignShelvesToBooks(Set<Long> bookIds, Set<Long> shelfIdsToAssign, Set<Long> shelfIdsToUnassign) {
        return bookUpdateService.assignShelvesToBooks(bookIds, shelfIdsToAssign, shelfIdsToUnassign);
    }

    @Transactional
    public List<Book> assignFileTypeToBooks(Set<Long> bookIds, String fileType) {
        return bookUpdateService.assignFileTypeToBooks(bookIds, fileType);
    }

    @Transactional
    public List<Book> releaseFromStaging(Set<Long> bookIds) {
        return bookUpdateService.releaseFromStaging(bookIds);
    }

    @Transactional
    public List<Book> stageForReview(Set<Long> bookIds) {
        return bookUpdateService.stageForReview(bookIds);
    }

    public Resource getBookThumbnail(long bookId) {
        return getBookThumbnail(bookId, false).resource();
    }

    public MediaResource getBookThumbnail(long bookId, boolean preferWebp) {
        Path webpPath = preferWebp ? fileService.ensureBookThumbnailWebp(bookId) : null;
        try {
            if (webpPath != null && Files.exists(webpPath)) {
                return toMediaResource(webpPath, MediaType.parseMediaType("image/webp"));
            }
            Path thumbnailPath = Paths.get(fileService.getThumbnailFile(bookId));
            if (Files.exists(thumbnailPath)) {
                return toMediaResource(thumbnailPath, MediaType.IMAGE_JPEG);
            }
            return new MediaResource(new ClassPathResource("static/images/missing-cover.jpg"), MediaType.IMAGE_JPEG);
        } catch (MalformedURLException e) {
            throw new RuntimeException("Failed to load book cover for bookId=" + bookId, e);
        }
    }

    public Resource getBookCover(long bookId) {
        Path coverPath = Paths.get(fileService.getCoverFile(bookId));
        try {
            if (Files.exists(coverPath)) {
                return new UrlResource(coverPath.toUri());
            } else {
                return new ClassPathResource("static/images/missing-cover.jpg");
            }
        } catch (MalformedURLException e) {
            throw new RuntimeException("Failed to load book cover for bookId=" + bookId, e);
        }
    }

    public Resource getBookCover(String coverHash) {
        BookEntity bookEntity = bookRepository.findByBookCoverHash(coverHash).orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(coverHash));
        return getBookCover(bookEntity.getId());
    }

    public Resource getAudiobookThumbnail(long bookId) {
        return getAudiobookThumbnail(bookId, false).resource();
    }

    public MediaResource getAudiobookThumbnail(long bookId, boolean preferWebp) {
        Path webpPath = preferWebp ? fileService.ensureAudiobookThumbnailWebp(bookId) : null;
        try {
            if (webpPath != null && Files.exists(webpPath)) {
                return toMediaResource(webpPath, MediaType.parseMediaType("image/webp"));
            }
            Path thumbnailPath = Paths.get(fileService.getAudiobookThumbnailFile(bookId));
            if (Files.exists(thumbnailPath)) {
                return toMediaResource(thumbnailPath, MediaType.IMAGE_JPEG);
            }
            return new MediaResource(new ClassPathResource("static/images/missing-cover.jpg"), MediaType.IMAGE_JPEG);
        } catch (MalformedURLException e) {
            throw new RuntimeException("Failed to load audiobook thumbnail for bookId=" + bookId, e);
        }
    }

    public Resource getAudiobookCover(long bookId) {
        Path coverPath = Paths.get(fileService.getAudiobookCoverFile(bookId));
        try {
            if (Files.exists(coverPath)) {
                return new UrlResource(coverPath.toUri());
            } else {
                return new ClassPathResource("static/images/missing-cover.jpg");
            }
        } catch (MalformedURLException e) {
            throw new RuntimeException("Failed to load audiobook cover for bookId=" + bookId, e);
        }
    }

    public ResponseEntity<Resource> downloadBook(Long bookId) {
        return bookDownloadService.downloadBook(bookId);
    }

    private MediaResource toMediaResource(Path path, MediaType mediaType) throws MalformedURLException {
        return new MediaResource(new UrlResource(path.toUri()), mediaType);
    }

    public void downloadAllBookFiles(Long bookId, HttpServletResponse response) {
        bookDownloadService.downloadAllBookFiles(bookId, response);
    }

    public ResponseEntity<Resource> getBookContent(long bookId) {
        return getBookContent(bookId, null);
    }

    public ResponseEntity<Resource> getBookContent(long bookId, String bookType) {
        BookEntity bookEntity = bookRepository.findById(bookId).orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        String filePath;
        if (bookType != null) {
            BookFileType requestedType = BookFileType.valueOf(bookType.toUpperCase());
            BookFileEntity bookFile = bookEntity.getBookFiles().stream()
                    .filter(bf -> bf.getBookType() == requestedType)
                    .findFirst()
                    .orElseThrow(() -> ApiError.FILE_NOT_FOUND.createException("No file of type " + bookType + " found for book"));
            filePath = bookFile.getFullFilePath().toString();
        } else {
            filePath = FileUtils.getBookFullPath(bookEntity);
        }
        File file = new File(filePath);
        if (!file.exists()) {
            throw ApiError.FILE_NOT_FOUND.createException(filePath);
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.length())
                .body(resource);
    }

    public void streamBookContent(long bookId, String bookType, HttpServletRequest request, HttpServletResponse response) throws IOException {
        BookEntity bookEntity = bookRepository.findById(bookId).orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        String filePath;
        if (bookType != null) {
            BookFileType requestedType = BookFileType.valueOf(bookType.toUpperCase());
            BookFileEntity bookFile = bookEntity.getBookFiles().stream()
                    .filter(bf -> bf.getBookType() == requestedType)
                    .findFirst()
                    .orElseThrow(() -> ApiError.FILE_NOT_FOUND.createException("No file of type " + bookType + " found for book"));
            filePath = bookFile.getFullFilePath().toString();
        } else {
            filePath = FileUtils.getBookFullPath(bookEntity);
        }

        Path path = Paths.get(filePath);
        String fileName = path.getFileName().toString();
        String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "";
        String contentType = switch (extension.toLowerCase()) {
            case "pdf" -> "application/pdf";
            case "epub" -> "application/epub+zip";
            case "mobi", "azw3" -> "application/x-mobipocket-ebook";
            case "cbz" -> "application/vnd.comicbook+zip";
            case "cbr" -> "application/vnd.comicbook-rar";
            case "fb2" -> "application/x-fictionbook+xml";
            default -> "application/octet-stream";
        };

        fileStreamingService.streamWithRangeSupport(path, contentType, request, response);
    }

    @Transactional
    public ResponseEntity<BookDeletionResponse> deleteBooks(Set<Long> ids) {
        return deleteBooks(ids, true);
    }

    @Transactional
    public ResponseEntity<BookDeletionResponse> deleteBooks(Set<Long> ids, boolean deleteFromDisk) {
        return deleteBooks(ids, deleteFromDisk, RemoveFromLibraryMode.REMOVE_FOREVER);
    }

    @Transactional
    public ResponseEntity<BookDeletionResponse> deleteBooks(Set<Long> ids, boolean deleteFromDisk, RemoveFromLibraryMode removeMode) {
        FableUser user = getAuthenticatedOrSystemUser();
        List<BookEntity> books = bookQueryService.findAllWithMetadataByIds(ids);

        Set<Long> userLibraryIds = getUserLibraryIds(user);
        books = books.stream()
                .filter(book -> userLibraryIds.contains(book.getLibrary().getId()))
                .toList();

        if (deleteFromDisk && !appSettingService.getAppSettings().isAllowFileDeletion()) {
            throw ApiError.FILE_DELETION_DISABLED.createException();
        }

        Set<Long> affectedIds = books.stream()
                .map(BookEntity::getId)
                .collect(Collectors.toSet());

        if (!deleteFromDisk) {
            Instant removedAt = Instant.now();
            boolean removeForever = removeMode == RemoveFromLibraryMode.REMOVE_FOREVER;
            books.forEach(book -> {
                book.setDeleted(true);
                book.setDeletedAt(removedAt);
                book.setRemovedFromLibrary(removeForever);
            });
            bookRepository.saveAll(books);
            auditService.log(
                    AuditAction.BOOK_DELETED,
                    (removeForever
                            ? "Removed " + affectedIds.size() + " book(s) from library permanently"
                            : "Removed " + affectedIds.size() + " book(s) from library until next scan")
            );
            return ResponseEntity.ok(new BookDeletionResponse(affectedIds, List.of()));
        }

        List<Long> failedFileDeletions = new ArrayList<>();
        for (BookEntity book : books) {
            if (Boolean.TRUE.equals(book.getIsPhysical()) && !book.hasFiles()) {
                sidecarMetadataWriter.deleteSidecarFiles(book);
            }

            for (BookFileEntity bookFile : book.getBookFiles()) {
                Path fullFilePath = bookFile.getFullFilePath();
                try {
                    if (Files.exists(fullFilePath)) {
                        try {
                            monitoringRegistrationService.unregisterSpecificPath(fullFilePath.getParent());
                        } catch (Exception ex) {
                            log.warn("Failed to unregister monitoring for path: {}", fullFilePath.getParent(), ex);
                        }

                        // Handle folder-based audiobooks (delete directory recursively)
                        if (bookFile.isFolderBased() && Files.isDirectory(fullFilePath)) {
                            deleteDirectoryRecursively(fullFilePath);
                            log.info("Deleted folder-based audiobook: {}", fullFilePath);
                        } else {
                            Files.delete(fullFilePath);
                            log.info("Deleted book file: {}", fullFilePath);
                        }

                        Set<Path> libraryRoots = book.getLibrary().getLibraryPaths().stream()
                                .map(LibraryPathEntity::getPath)
                                .map(Paths::get)
                                .map(Path::normalize)
                                .collect(Collectors.toSet());

                        deleteEmptyParentDirsUpToLibraryFolders(fullFilePath.getParent(), libraryRoots);

                        try {
                            sidecarMetadataWriter.deleteSidecarFiles(fullFilePath);
                        } catch (Exception e) {
                            log.warn("Failed to delete sidecar files for: {}", fullFilePath, e);
                        }
                    }
                } catch (IOException e) {
                    log.warn("Failed to delete book file: {}", fullFilePath, e);
                    failedFileDeletions.add(book.getId());
                } finally {
                    monitoringRegistrationService.registerSpecificPath(fullFilePath.getParent(), book.getLibrary().getId());
                }
            }
        }

        bookRepository.deleteAllInBatch(books);
            auditService.log(AuditAction.BOOK_DELETED, "Deleted " + affectedIds.size() + " book(s)");
            BookDeletionResponse response = new BookDeletionResponse(affectedIds, failedFileDeletions);
        return failedFileDeletions.isEmpty()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.MULTI_STATUS).body(response);
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;

        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
        }
    }

    public void deleteEmptyParentDirsUpToLibraryFolders(Path currentDir, Set<Path> libraryRoots) {
        Path dir = currentDir;
        Set<String> ignoredFilenames = Set.of(".DS_Store", "Thumbs.db");
        dir = dir.toAbsolutePath().normalize();

        Set<Path> normalizedRoots = new HashSet<>();
        for (Path root : libraryRoots) {
            normalizedRoots.add(root.toAbsolutePath().normalize());
        }

        while (dir != null) {
            boolean isLibraryRoot = false;
            for (Path root : normalizedRoots) {
                try {
                    if (Files.isSameFile(root, dir)) {
                        isLibraryRoot = true;
                        break;
                    }
                } catch (IOException e) {
                    log.warn("Failed to compare paths: {} and {}", root, dir);
                }
            }

            if (isLibraryRoot) {
                log.debug("Reached library root: {}. Stopping cleanup.", dir);
                break;
            }

            File[] files = dir.toFile().listFiles();
            if (files == null) {
                log.warn("Cannot read directory: {}. Stopping cleanup.", dir);
                break;
            }

            boolean hasImportantFiles = false;
            for (File file : files) {
                if (!ignoredFilenames.contains(file.getName())) {
                    hasImportantFiles = true;
                    break;
                }
            }

            if (!hasImportantFiles) {
                for (File file : files) {
                    try {
                        Files.delete(file.toPath());
                        log.info("Deleted ignored file: {}", file.getAbsolutePath());
                    } catch (IOException e) {
                        log.warn("Failed to delete ignored file: {}", file.getAbsolutePath());
                    }
                }
                try {
                    Files.delete(dir);
                    log.info("Deleted empty directory: {}", dir);
                } catch (IOException e) {
                    log.warn("Failed to delete directory: {}", dir, e);
                    break;
                }
                dir = dir.getParent();
            } else {
                log.debug("Directory {} contains important files. Stopping cleanup.", dir);
                break;
            }
        }
    }

    public Set<Shelf> filterShelvesByUserId(Set<Shelf> shelves, Long userId) {
        if (shelves == null) return Collections.emptySet();
        return shelves.stream()
                .filter(shelf -> userId.equals(shelf.getUserId()))
                .collect(Collectors.toSet());
    }

    public AppPageResponse<AppBookGridSummary> getBooksPaged(int page, int size, List<String> sorts,
            String sortField, String sortDir, Long libraryId,
            Long shelfId, boolean unshelved, boolean staged, List<String> mediaTypes, String search, List<String> authors, List<String> categories,
            String series, String publisher, String language, String isbn,
            String readStatus, String bookType, String contentRating, String filterMode) {
        FableUser user = getAuthenticatedOrSystemUser();

        // Build sort
        Sort springSort = buildSort(sorts, sortField, sortDir);
        Pageable pageable = PageRequest.of(page, size, springSort);

        // Build filter specification
        Specification<BookEntity> filterSpec = buildFilterSpec(search, authors, categories,
            series, publisher, language, isbn, readStatus, bookType, contentRating,
            shelfId, unshelved, staged, mediaTypes, filterMode, user);

        Set<Long> accessibleLibraryIds = getUserLibraryIds(user);

        // Build base specification with library access control
        Specification<BookEntity> baseSpec;
        if (libraryId != null) {
            if (!accessibleLibraryIds.contains(libraryId)) {
                baseSpec = (root, query, cb) -> cb.disjunction();
            } else {
                baseSpec = AppBookSpecification.inLibrary(libraryId);
            }
        } else {
            baseSpec = AppBookSpecification.inLibraries(accessibleLibraryIds);
        }

        Specification<BookEntity> combined = AppBookSpecification.combine(
                AppBookSpecification.notDeleted(),
                baseSpec,
                filterSpec
        );

        Page<AppBookGridSummary> bookPage = bookQueryService.findAllPaged(combined, pageable, user.getId());

        Set<Long> bookIds = bookPage.getContent().stream().map(AppBookGridSummary::getId).collect(Collectors.toSet());
        Map<Long, UserBookProgressEntity> progressMap =
                readingProgressService.fetchUserProgress(user.getId(), bookIds);
        Map<Long, UserBookFileProgressEntity> fileProgressMap =
                readingProgressService.fetchUserFileProgress(user.getId(), bookIds);

        bookPage.getContent().forEach(summary -> {
            readingProgressService.enrichGridSummaryWithProgress(
                    summary,
                    progressMap.get(summary.getId()),
                    fileProgressMap.get(summary.getId())
            );
        });

        applyAiPanelFlagsToGridSummaries(bookPage.getContent(), user.getId());
        applyAiSearchFlagsToGridSummaries(bookPage.getContent(), user.getId());

        return AppPageResponse.of(
                bookPage.getContent(),
                bookPage.getNumber(),
                bookPage.getSize(),
                bookPage.getTotalElements()
        );
    }

    private Sort buildSort(List<String> sorts, String sortField, String sortDir) {
        if (sorts != null && !sorts.isEmpty()) {
            List<Sort.Order> orders = new ArrayList<>();
            for (int i = 0; i < sorts.size(); i++) {
                String s = sorts.get(i);
                String[] parts = s.split(",");
                if (parts.length >= 2) {
                    Sort.Direction direction = "desc".equalsIgnoreCase(parts[1].trim())
                            ? Sort.Direction.DESC : Sort.Direction.ASC;
                    orders.add(createPagedSortOrder(parts[0].trim(), direction));
                } else if (i + 1 < sorts.size()) {
                    // Spring may auto-split "field,dir" into two separate list entries
                    String next = sorts.get(i + 1);
                    String trimmedNext = next.trim().toLowerCase();
                    if ("asc".equals(trimmedNext) || "desc".equals(trimmedNext)) {
                        Sort.Direction direction = "desc".equals(trimmedNext)
                                ? Sort.Direction.DESC : Sort.Direction.ASC;
                        orders.add(createPagedSortOrder(s.trim(), direction));
                        i++; // skip the direction token
                    }
                }
            }
            return orders.isEmpty() ? Sort.by(Sort.Direction.DESC, "addedOn") : Sort.by(orders);
        }
        if (sortField != null && !sortField.isEmpty()) {
            Sort.Direction direction = "asc".equalsIgnoreCase(sortDir)
                    ? Sort.Direction.ASC : Sort.Direction.DESC;
            return Sort.by(createPagedSortOrder(sortField, direction));
        }
        return Sort.by(Sort.Direction.DESC, "addedOn");
    }

    private Sort.Order createPagedSortOrder(String rawField, Sort.Direction direction) {
        String normalizedField = PAGED_SORT_FIELD_ALIASES.getOrDefault(rawField, rawField);
        if (!SUPPORTED_PAGED_SORT_FIELDS.contains(normalizedField)) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Unsupported sort field: " + rawField);
        }

        return new Sort.Order(direction, normalizedField);
    }

    @SuppressWarnings("unchecked")
    private Specification<BookEntity> buildFilterSpec(String search, List<String> authors,
            List<String> categories, String series, String publisher, String language,
            String isbn, String readStatus, String bookType, String contentRating,
            Long shelfId, boolean unshelved, boolean staged, List<String> mediaTypes, String filterMode, FableUser user) {
        boolean orMode = "or".equalsIgnoreCase(filterMode);

        List<Specification<BookEntity>> scopeSpecs = new ArrayList<>();
        List<Specification<BookEntity>> specs = new ArrayList<>();

        if (shelfId != null) {
            validateShelfAccess(shelfId, user);
            scopeSpecs.add(AppBookSpecification.inShelf(shelfId));
        }

        if (unshelved) {
            scopeSpecs.add(AppBookSpecification.withoutShelvesForUser(user.getId()));
        }

        if (staged) {
            scopeSpecs.add(AppBookSpecification.staged());
        }

        if (mediaTypes != null && !mediaTypes.isEmpty()) {
            List<Specification<BookEntity>> mediaTypeSpecs = mediaTypes.stream()
                    .filter(mediaType -> mediaType != null && !mediaType.trim().isEmpty())
                    .map(String::trim)
                    .map(AppBookSpecification::withCustomMediaType)
                    .toList();
            if (!mediaTypeSpecs.isEmpty()) {
                specs.add(AppBookSpecification.combineOr(mediaTypeSpecs.toArray(new Specification[0])));
            }
        }

        if (search != null && !search.trim().isEmpty()) {
            specs.add(AppBookSpecification.searchText(search));
        }
        if (authors != null && !authors.isEmpty()) {
            List<Specification<BookEntity>> authorSpecs = authors.stream()
                    .filter(a -> !a.trim().isEmpty())
                    .map(AppBookSpecification::withAuthor)
                    .toList();
            if (!authorSpecs.isEmpty()) {
                specs.add(AppBookSpecification.combineOr(authorSpecs.toArray(new Specification[0])));
            }
        }
        if (categories != null && !categories.isEmpty()) {
            List<Specification<BookEntity>> catSpecs = categories.stream()
                    .filter(c -> !c.trim().isEmpty())
                    .map(AppBookSpecification::withCategory)
                    .toList();
            if (!catSpecs.isEmpty()) {
                specs.add(AppBookSpecification.combineOr(catSpecs.toArray(new Specification[0])));
            }
        }
        if (series != null && !series.trim().isEmpty()) {
            specs.add(AppBookSpecification.inSeries(series));
        }
        if (publisher != null && !publisher.trim().isEmpty()) {
            specs.add(AppBookSpecification.withPublisher(publisher));
        }
        if (language != null && !language.trim().isEmpty()) {
            specs.add(AppBookSpecification.withLanguage(language));
        }
        if (isbn != null && !isbn.trim().isEmpty()) {
            specs.add(AppBookSpecification.withIsbn(isbn));
        }
        if (readStatus != null && !readStatus.trim().isEmpty()) {
            try {
                org.fable.model.enums.ReadStatus status =
                        org.fable.model.enums.ReadStatus.valueOf(readStatus.toUpperCase());
                specs.add(AppBookSpecification.withReadStatus(status, user.getId()));
            } catch (IllegalArgumentException ignored) {
                // invalid read status value — skip filter
            }
        }
        if (bookType != null && !bookType.trim().isEmpty()) {
            String upper = bookType.toUpperCase().trim();
            if ("PHYSICAL".equals(upper)) {
                specs.add((root, query, cb) -> cb.isTrue(root.get("isPhysical")));
            } else if ("AUDIOBOOK".equals(upper)) {
                specs.add(AppBookSpecification.hasAudiobookFile());
            } else {
                try {
                    BookFileType bft = BookFileType.valueOf(upper);
                    specs.add(AppBookSpecification.withFileType(bft));
                } catch (IllegalArgumentException ignored) {
                    // invalid book type
                }
            }
        }
        if (contentRating != null && !contentRating.trim().isEmpty()) {
            specs.add(AppBookSpecification.withContentRating(contentRating));
        }

        Specification<BookEntity> facetSpec = null;
        if (!specs.isEmpty()) {
            Specification<BookEntity>[] specArray = specs.toArray(new Specification[0]);
            facetSpec = orMode ? AppBookSpecification.combineOr(specArray)
                               : AppBookSpecification.combine(specArray);
        }

        if (facetSpec != null) {
            scopeSpecs.add(facetSpec);
        }

        if (scopeSpecs.isEmpty()) {
            return (root, query, cb) -> cb.conjunction();
        }

        return AppBookSpecification.combine(scopeSpecs.toArray(new Specification[0]));
    }

    private void validateShelfAccess(Long shelfId, FableUser user) {
        ShelfEntity shelf = shelfRepository.findById(shelfId)
                .orElseThrow(() -> ApiError.SHELF_NOT_FOUND.createException(shelfId));

        if (!shelf.isPublic() && !Objects.equals(shelf.getUser().getId(), user.getId())) {
            throw ApiError.FORBIDDEN.createException("Access denied to shelf " + shelfId);
        }
    }

    public Book updateCurrentlyReadingStatus(long bookId, boolean isCurrentlyReading) {
        FableUser user = getAuthenticatedOrSystemUser();
        BookEntity bookEntity = bookRepository.findById(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        
        // Check if user has access to this book
        Set<Long> userLibraryIds = getUserLibraryIds(user);
        if (!userLibraryIds.contains(bookEntity.getLibrary().getId())) {
            throw ApiError.BOOK_NOT_FOUND.createException(bookId);
        }
        
        // Use BookUpdateService to update the field
        return bookUpdateService.updateCurrentlyReadingStatus(bookId, isCurrentlyReading);
    }

    private FableUser getAuthenticatedOrSystemUser() {
        FableUser user = authenticationService.getAuthenticatedUser();
        return user != null ? user : authenticationService.getSystemUser();
    }

}

