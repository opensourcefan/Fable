package org.fable.service.book;

import org.fable.config.security.service.AuthenticationService;
import org.fable.exception.ApiError;
import org.fable.mapper.BookMapper;
import org.fable.service.event.aop.BroadcastBookUpdate;
import org.fable.model.dto.*;
import org.fable.model.dto.response.BookStatusUpdateResponse;
import org.fable.model.dto.response.PersonalRatingUpdateResponse;
import org.fable.model.entity.*;
import org.fable.model.enums.BookFileType;
import org.fable.model.enums.FetchedMetadataProposalStatus;
import org.fable.model.enums.ReadStatus;
import org.fable.model.enums.UserPermission;
import org.fable.repository.*;
import org.fable.service.library.LibraryVisibilityService;
import org.fable.service.progress.ReadingProgressService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.EnumUtils;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@AllArgsConstructor
@Service
public class BookUpdateService {

    private final BookRepository bookRepository;
    private final PdfViewerPreferencesRepository pdfViewerPreferencesRepository;
    private final CbxViewerPreferencesRepository cbxViewerPreferencesRepository;
    private final NewPdfViewerPreferencesRepository newPdfViewerPreferencesRepository;
    private final ShelfRepository shelfRepository;
    private final BookMapper bookMapper;
    private final UserRepository userRepository;
    private final UserBookProgressRepository userBookProgressRepository;
    private final AuthenticationService authenticationService;
    private final BookQueryService bookQueryService;
    private final ReadingProgressService readingProgressService;
    private final EbookViewerPreferenceRepository ebookViewerPreferenceRepository;
    private final LibraryVisibilityService libraryVisibilityService;
    private final MetadataFetchProposalRepository proposalRepository;

    public void updateBookViewerSetting(long bookId, BookViewerSettings bookViewerSettings) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId).orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        FableUser user = authenticationService.getAuthenticatedUser();
        var primaryFile = book.getPrimaryBookFile();
        if (primaryFile == null || primaryFile.getBookType() == null) {
            throw ApiError.UNSUPPORTED_BOOK_TYPE.createException();
        }
        switch (primaryFile.getBookType()) {
            case PDF -> updatePdfViewerSettings(bookId, user.getId(), bookViewerSettings);
            case EPUB, FB2, MOBI, AZW3 -> updateEbookViewerSettings(bookId, user.getId(), bookViewerSettings);
            case CBX -> updateCbxViewerSettings(bookId, user.getId(), bookViewerSettings);
            default -> throw ApiError.UNSUPPORTED_BOOK_TYPE.createException();
        }
    }

    @Transactional
    public List<BookStatusUpdateResponse> updateReadStatus(List<Long> bookIds, String status) {
        FableUser user = authenticationService.getAuthenticatedUser();
        validateBulkOperationPermission(bookIds, user, UserPermission.CAN_BULK_RESET_BOOK_READ_STATUS);

        ReadStatus readStatus = EnumUtils.getEnumIgnoreCase(ReadStatus.class, status);
        Set<Long> existingProgressBookIds = validateBooksAndGetExistingProgress(user.getId(), bookIds);

        Instant now = Instant.now();
        Instant dateFinished = readStatus == ReadStatus.READ ? now : null;

        updateExistingProgress(user.getId(), existingProgressBookIds, readStatus, now, dateFinished);
        createNewProgress(user.getId(), bookIds, existingProgressBookIds, readStatus, now, dateFinished);

        return buildStatusUpdateResponses(bookIds, readStatus, now, dateFinished);
    }

    @Transactional
    public List<PersonalRatingUpdateResponse> updatePersonalRating(List<Long> bookIds, Integer rating) {
        FableUser user = authenticationService.getAuthenticatedUser();
        Set<Long> existingProgressBookIds = validateBooksAndGetExistingProgress(user.getId(), bookIds);

        if (!existingProgressBookIds.isEmpty()) {
            userBookProgressRepository.bulkUpdatePersonalRating(user.getId(), new ArrayList<>(existingProgressBookIds), rating);
        }

        createProgressForRating(user.getId(), bookIds, existingProgressBookIds, rating);

        return buildRatingUpdateResponses(bookIds, rating);
    }

    @Transactional
    public List<PersonalRatingUpdateResponse> resetPersonalRating(List<Long> bookIds) {
        FableUser user = authenticationService.getAuthenticatedUser();
        Set<Long> existingProgressBookIds = validateBooksAndGetExistingProgress(user.getId(), bookIds);

        if (!existingProgressBookIds.isEmpty()) {
            userBookProgressRepository.bulkUpdatePersonalRating(user.getId(), new ArrayList<>(existingProgressBookIds), null);
        }

        return buildRatingUpdateResponses(bookIds, null);
    }

    @BroadcastBookUpdate
    @Transactional
    public List<Book> assignShelvesToBooks(Set<Long> bookIds, Set<Long> shelfIdsToAssign, Set<Long> shelfIdsToUnassign) {
        FableUser user = authenticationService.getAuthenticatedUser();
        FableUserEntity userEntity = findUserOrThrow(user.getId());

        validateShelfOwnership(userEntity, shelfIdsToAssign, shelfIdsToUnassign);

        List<BookEntity> bookEntities = bookQueryService.findAllWithMetadataByIds(bookIds);
        List<ShelfEntity> shelvesToAssign = shelfRepository.findAllById(shelfIdsToAssign);

        updateBookShelves(bookEntities, shelvesToAssign, shelfIdsToUnassign);
        bookRepository.saveAll(bookEntities);

        return buildBooksWithProgress(bookEntities, user.getId());
    }

    @BroadcastBookUpdate
    @Transactional
    public List<Book> assignFileTypeToBooks(Set<Long> bookIds, String fileType) {
        FableUser user = authenticationService.getAuthenticatedUser();
        if (!UserPermission.CAN_MANAGE_LIBRARY.isGranted(user.getPermissions())) {
            throw ApiError.PERMISSION_DENIED.createException(UserPermission.CAN_MANAGE_LIBRARY);
        }

        String normalizedFileType = fileType == null ? null : fileType.trim();
        if (normalizedFileType != null && normalizedFileType.isBlank()) {
            normalizedFileType = null;
        }

        List<BookEntity> bookEntities = bookQueryService.findAllWithMetadataByIds(bookIds);
        for (BookEntity bookEntity : bookEntities) {
            bookEntity.setFileType(normalizedFileType);
        }

        bookRepository.saveAll(bookEntities);
        return buildBooksWithProgress(bookEntities, user.getId());
    }

    @BroadcastBookUpdate
    @Transactional
    public List<Book> releaseFromStaging(Set<Long> bookIds) {
        FableUser user = authenticationService.getAuthenticatedUser();
        if (user == null || user.getPermissions() == null
                || (!user.getPermissions().isAdmin() && !UserPermission.CAN_EDIT_METADATA.isGranted(user.getPermissions()))) {
            throw ApiError.PERMISSION_DENIED.createException(UserPermission.CAN_EDIT_METADATA);
        }

        Set<Long> accessibleLibraryIds = libraryVisibilityService.getAccessibleLibraryIds(user);
        List<BookEntity> bookEntities = bookQueryService.findAllWithMetadataByIds(bookIds);
        validateBooksInAccessibleLibraries(bookEntities, accessibleLibraryIds);

        List<BookEntity> changed = new ArrayList<>();
        for (BookEntity bookEntity : bookEntities) {
            if (!Boolean.TRUE.equals(bookEntity.getStaged())) {
                continue;
            }
            bookEntity.setStaged(false);
            bookEntity.setStagingCompletedAt(null);
            changed.add(bookEntity);
        }
        if (changed.isEmpty()) {
            return List.of();
        }
        bookRepository.saveAll(changed);

        Set<Long> releasedIds = changed.stream().map(BookEntity::getId).collect(Collectors.toSet());
        proposalRepository.updateStatusByBookIdsAndStatus(
                releasedIds,
                FetchedMetadataProposalStatus.FETCHED,
                FetchedMetadataProposalStatus.REJECTED,
                Instant.now(),
                user.getId()
        );
        return buildBooksWithProgress(changed, user.getId());
    }

    @BroadcastBookUpdate
    @Transactional
    public List<Book> stageForReview(Set<Long> bookIds) {
        FableUser user = authenticationService.getAuthenticatedUser();
        if (user != null) {
            if (user.getPermissions() == null
                    || (!user.getPermissions().isAdmin() && !UserPermission.CAN_EDIT_METADATA.isGranted(user.getPermissions()))) {
                throw ApiError.PERMISSION_DENIED.createException(UserPermission.CAN_EDIT_METADATA);
            }
        }
        List<BookEntity> bookEntities = bookQueryService.findAllWithMetadataByIds(bookIds);
        if (user != null) {
            Set<Long> accessibleLibraryIds = libraryVisibilityService.getAccessibleLibraryIds(user);
            validateBooksInAccessibleLibraries(bookEntities, accessibleLibraryIds);
        }

        List<BookEntity> changed = new ArrayList<>();
        for (BookEntity bookEntity : bookEntities) {
            if (Boolean.TRUE.equals(bookEntity.getStaged())) {
                continue;
            }
            bookEntity.setStaged(true);
            bookEntity.setStagingCompletedAt(null);
            changed.add(bookEntity);
        }
        if (changed.isEmpty()) {
            return List.of();
        }
        bookRepository.saveAll(changed);
        if (user == null) {
            return changed.stream().map(bookMapper::toBook).collect(Collectors.toList());
        }
        return buildBooksWithProgress(changed, user.getId());
    }

    private void validateBooksInAccessibleLibraries(List<BookEntity> bookEntities, Set<Long> accessibleLibraryIds) {
        for (BookEntity bookEntity : bookEntities) {
            Long libraryId = bookEntity.getLibrary() != null ? bookEntity.getLibrary().getId() : null;
            if (libraryId == null || !accessibleLibraryIds.contains(libraryId)) {
                throw ApiError.PERMISSION_DENIED.createException(UserPermission.CAN_EDIT_METADATA);
            }
        }
    }

    private void updatePdfViewerSettings(long bookId, Long userId, BookViewerSettings settings) {
        if (settings.getPdfSettings() != null) {
            PdfViewerPreferencesEntity prefs = findOrCreatePdfPreferences(bookId, userId);
            PdfViewerPreferences pdfSettings = settings.getPdfSettings();
            prefs.setZoom(pdfSettings.getZoom());
            prefs.setSpread(pdfSettings.getSpread());
            pdfViewerPreferencesRepository.save(prefs);
        }

        if (settings.getNewPdfSettings() != null) {
            NewPdfViewerPreferencesEntity prefs = findOrCreateNewPdfPreferences(bookId, userId);
            NewPdfViewerPreferences pdfSettings = settings.getNewPdfSettings();
            prefs.setPageSpread(pdfSettings.getPageSpread());
            prefs.setPageViewMode(pdfSettings.getPageViewMode());
            prefs.setFitMode(pdfSettings.getFitMode());
            prefs.setScrollMode(pdfSettings.getScrollMode());
            prefs.setBackgroundColor(pdfSettings.getBackgroundColor());
            newPdfViewerPreferencesRepository.save(prefs);
        }
    }

    private void updateEbookViewerSettings(long bookId, Long userId, BookViewerSettings settings) {
        EbookViewerPreferenceEntity prefs = findOrCreateEbookPreferences(bookId, userId);
        EbookViewerPreferences epubSettings = settings.getEbookSettings();

        prefs.setUserId(userId);
        prefs.setBookId(bookId);
        prefs.setFontFamily(epubSettings.getFontFamily());
        prefs.setFontSize(epubSettings.getFontSize());
        prefs.setGap(epubSettings.getGap());
        prefs.setHyphenate(epubSettings.getHyphenate());
        prefs.setIsDark(epubSettings.getIsDark());
        prefs.setJustify(epubSettings.getJustify());
        prefs.setLineHeight(epubSettings.getLineHeight());
        prefs.setMaxBlockSize(epubSettings.getMaxBlockSize());
        prefs.setMaxColumnCount(epubSettings.getMaxColumnCount());
        prefs.setMaxInlineSize(epubSettings.getMaxInlineSize());
        prefs.setTheme(epubSettings.getTheme());
        prefs.setFlow(epubSettings.getFlow());

        ebookViewerPreferenceRepository.save(prefs);
    }

    private void updateCbxViewerSettings(long bookId, Long userId, BookViewerSettings settings) {
        CbxViewerPreferencesEntity prefs = findOrCreateCbxPreferences(bookId, userId);
        CbxViewerPreferences cbxSettings = settings.getCbxSettings();

        prefs.setPageSpread(cbxSettings.getPageSpread());
        prefs.setPageViewMode(cbxSettings.getPageViewMode());
        prefs.setFitMode(cbxSettings.getFitMode());
        prefs.setScrollMode(cbxSettings.getScrollMode());
        prefs.setBackgroundColor(cbxSettings.getBackgroundColor());

        cbxViewerPreferencesRepository.save(prefs);
    }

    private PdfViewerPreferencesEntity findOrCreatePdfPreferences(long bookId, Long userId) {
        return pdfViewerPreferencesRepository
                .findByBookIdAndUserId(bookId, userId)
                .orElseGet(() -> pdfViewerPreferencesRepository.save(
                        PdfViewerPreferencesEntity.builder()
                                .bookId(bookId)
                                .userId(userId)
                                .build()
                ));
    }

    private NewPdfViewerPreferencesEntity findOrCreateNewPdfPreferences(long bookId, Long userId) {
        return newPdfViewerPreferencesRepository
                .findByBookIdAndUserId(bookId, userId)
                .orElseGet(() -> newPdfViewerPreferencesRepository.save(
                        NewPdfViewerPreferencesEntity.builder()
                                .bookId(bookId)
                                .userId(userId)
                                .build()
                ));
    }

    private EbookViewerPreferenceEntity findOrCreateEbookPreferences(long bookId, Long userId) {
        return ebookViewerPreferenceRepository
                .findByBookIdAndUserId(bookId, userId)
                .orElseGet(() -> ebookViewerPreferenceRepository.save(
                        EbookViewerPreferenceEntity.builder()
                                .bookId(bookId)
                                .userId(userId)
                                .build()
                ));
    }

    private CbxViewerPreferencesEntity findOrCreateCbxPreferences(long bookId, Long userId) {
        return cbxViewerPreferencesRepository
                .findByBookIdAndUserId(bookId, userId)
                .orElseGet(() -> cbxViewerPreferencesRepository.save(
                        CbxViewerPreferencesEntity.builder()
                                .bookId(bookId)
                                .userId(userId)
                                .build()
                ));
    }

    private void updateExistingProgress(Long userId, Set<Long> bookIds, ReadStatus status, Instant now, Instant dateFinished) {
        if (!bookIds.isEmpty()) {
            userBookProgressRepository.bulkUpdateReadStatus(userId, new ArrayList<>(bookIds), status, now, dateFinished);
        }
    }

    private void createNewProgress(Long userId, List<Long> allBookIds, Set<Long> existingBookIds, ReadStatus status, Instant now, Instant dateFinished) {
        Set<Long> newProgressBookIds = allBookIds.stream()
                .filter(id -> !existingBookIds.contains(id))
                .collect(Collectors.toSet());

        if (newProgressBookIds.isEmpty()) return;

        FableUserEntity userEntity = findUserOrThrow(userId);
        List<UserBookProgressEntity> newProgressEntities = newProgressBookIds.stream()
                .map(bookId -> createProgressEntity(userEntity, bookId, status, now, dateFinished))
                .collect(Collectors.toList());

        userBookProgressRepository.saveAll(newProgressEntities);
    }

    private UserBookProgressEntity createProgressEntity(FableUserEntity user, Long bookId, ReadStatus status, Instant now, Instant dateFinished) {
        UserBookProgressEntity progress = new UserBookProgressEntity();
        progress.setUser(user);

        BookEntity bookEntity = new BookEntity();
        bookEntity.setId(bookId);
        progress.setBook(bookEntity);

        progress.setReadStatus(status);
        progress.setReadStatusModifiedTime(now);
        progress.setDateFinished(dateFinished);
        return progress;
    }

    private void createProgressForRating(Long userId, List<Long> allBookIds, Set<Long> existingBookIds, Integer rating) {
        Set<Long> newProgressBookIds = allBookIds.stream()
                .filter(id -> !existingBookIds.contains(id))
                .collect(Collectors.toSet());

        if (newProgressBookIds.isEmpty()) return;

        FableUserEntity userEntity = findUserOrThrow(userId);
        List<UserBookProgressEntity> newProgressEntities = newProgressBookIds.stream()
                .map(bookId -> createProgressEntityWithRating(userEntity, bookId, rating))
                .collect(Collectors.toList());

        userBookProgressRepository.saveAll(newProgressEntities);
    }

    private UserBookProgressEntity createProgressEntityWithRating(FableUserEntity user, Long bookId, Integer rating) {
        UserBookProgressEntity progress = new UserBookProgressEntity();
        progress.setUser(user);

        BookEntity bookEntity = new BookEntity();
        bookEntity.setId(bookId);
        progress.setBook(bookEntity);

        progress.setPersonalRating(rating);
        return progress;
    }

    private void validateShelfOwnership(FableUserEntity user, Set<Long> shelfIdsToAssign, Set<Long> shelfIdsToUnassign) {
        Set<Long> userShelfIds = user.getShelves().stream()
                .map(ShelfEntity::getId)
                .collect(Collectors.toSet());

        if (!userShelfIds.containsAll(shelfIdsToAssign)) {
            throw ApiError.GENERIC_UNAUTHORIZED.createException("Cannot assign shelves that do not belong to the user.");
        }
        if (!userShelfIds.containsAll(shelfIdsToUnassign)) {
            throw ApiError.GENERIC_UNAUTHORIZED.createException("Cannot unassign shelves that do not belong to the user.");
        }
    }

    private void updateBookShelves(List<BookEntity> books, List<ShelfEntity> shelvesToAssign, Set<Long> shelfIdsToUnassign) {
        for (BookEntity book : books) {
            book.getShelves().removeIf(shelf -> shelfIdsToUnassign.contains(shelf.getId()));
            book.getShelves().addAll(shelvesToAssign);
        }
    }

    private List<Book> buildBooksWithProgress(List<BookEntity> bookEntities, Long userId) {
        Set<Long> bookIds = bookEntities.stream().map(BookEntity::getId).collect(Collectors.toSet());
        Map<Long, UserBookProgressEntity> progressMap = readingProgressService.fetchUserProgress(userId, bookIds);
        Map<Long, UserBookFileProgressEntity> fileProgressMap = readingProgressService.fetchUserFileProgress(userId, bookIds);

        return bookEntities.stream()
                .map(bookEntity -> buildBook(bookEntity, userId, progressMap, fileProgressMap))
                .collect(Collectors.toList());
    }

    private Book buildBook(BookEntity bookEntity, Long userId,
                           Map<Long, UserBookProgressEntity> progressMap,
                           Map<Long, UserBookFileProgressEntity> fileProgressMap) {
        Book book = bookMapper.toBook(bookEntity);
        book.setShelves(filterShelvesByUserId(book.getShelves(), userId));
        readingProgressService.enrichBookWithProgress(
                book,
                progressMap.get(bookEntity.getId()),
                fileProgressMap.get(bookEntity.getId())
        );
        return book;
    }

    private FableUserEntity findUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with ID: " + userId));
    }

    private void validateBulkOperationPermission(List<Long> bookIds, FableUser user, UserPermission permission) {
        if (bookIds.size() > 1 && !permission.isGranted(user.getPermissions())) {
            throw ApiError.PERMISSION_DENIED.createException(permission);
        }
    }

    private Set<Long> validateBooksAndGetExistingProgress(Long userId, List<Long> bookIds) {
        long existingBooksCount = bookRepository.countByIdIn(bookIds);
        if (existingBooksCount != bookIds.size()) {
            throw ApiError.BOOK_NOT_FOUND.createException("One or more books not found");
        }

        return userBookProgressRepository.findExistingProgressBookIds(userId, new HashSet<>(bookIds));
    }

    private List<BookStatusUpdateResponse> buildStatusUpdateResponses(List<Long> bookIds, ReadStatus status, Instant now, Instant dateFinished) {
        return bookIds.stream()
                .map(bookId -> BookStatusUpdateResponse.builder()
                        .bookId(bookId)
                        .readStatus(status)
                        .readStatusModifiedTime(now)
                        .dateFinished(dateFinished)
                        .build())
                .collect(Collectors.toList());
    }

    private List<PersonalRatingUpdateResponse> buildRatingUpdateResponses(List<Long> bookIds, Integer rating) {
        return bookIds.stream()
                .map(bookId -> PersonalRatingUpdateResponse.builder()
                        .bookId(bookId)
                        .personalRating(rating)
                        .build())
                .collect(Collectors.toList());
    }

    private Set<Shelf> filterShelvesByUserId(Set<Shelf> shelves, Long userId) {
        if (shelves == null) return Collections.emptySet();
        return shelves.stream()
                .filter(shelf -> userId.equals(shelf.getUserId()))
                .collect(Collectors.toSet());
    }

    @BroadcastBookUpdate
    @Transactional
    public Book updateCurrentlyReadingStatus(long bookId, boolean isCurrentlyReading) {
        BookEntity book = bookRepository.findById(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        
        book.setCurrentlyReading(isCurrentlyReading);
        bookRepository.save(book);
        return bookMapper.toBook(book);
    }
}
