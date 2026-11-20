package org.fable.service.book;

import org.fable.config.security.service.AuthenticationService;
import org.fable.exception.APIException;
import org.fable.mapper.BookMapper;
import org.fable.model.dto.*;
import org.fable.model.dto.response.BookStatusUpdateResponse;
import org.fable.model.dto.response.PersonalRatingUpdateResponse;
import org.fable.model.entity.*;
import org.fable.model.enums.BookFileType;
import org.fable.model.enums.FetchedMetadataProposalStatus;
import org.fable.model.enums.ReadStatus;
import org.fable.repository.*;
import org.fable.service.library.LibraryVisibilityService;
import org.fable.service.progress.ReadingProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookUpdateServiceTest {

    @Mock
    private BookRepository bookRepository;
    @Mock
    private PdfViewerPreferencesRepository pdfViewerPreferencesRepository;
    @Mock
    private CbxViewerPreferencesRepository cbxViewerPreferencesRepository;
    @Mock
    private NewPdfViewerPreferencesRepository newPdfViewerPreferencesRepository;
    @Mock
    private ShelfRepository shelfRepository;
    @Mock
    private BookMapper bookMapper;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserBookProgressRepository userBookProgressRepository;
    @Mock
    private AuthenticationService authenticationService;
    @Mock
    private BookQueryService bookQueryService;
    @Mock
    private ReadingProgressService readingProgressService;
    @Mock
    private EbookViewerPreferenceRepository ebookViewerPreferenceRepository;
    @Mock
    private LibraryVisibilityService libraryVisibilityService;
    @Mock
    private MetadataFetchProposalRepository proposalRepository;

    @InjectMocks
    private BookUpdateService bookUpdateService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        bookUpdateService = new BookUpdateService(
                bookRepository,
                pdfViewerPreferencesRepository,
                cbxViewerPreferencesRepository,
                newPdfViewerPreferencesRepository,
                shelfRepository,
                bookMapper,
                userRepository,
                userBookProgressRepository,
                authenticationService,
                bookQueryService,
                readingProgressService,
                ebookViewerPreferenceRepository,
                libraryVisibilityService,
                proposalRepository
        );
    }

    @Test
    void updateBookViewerSetting_pdf_shouldUpdatePdfPrefs() {
        long bookId = 1L;
        BookEntity book = new BookEntity();
        book.setId(bookId);
        BookFileEntity primaryFile = new BookFileEntity();
        primaryFile.setBook(book);
        primaryFile.setBookType(BookFileType.PDF);
        book.setBookFiles(List.of(primaryFile));
        FableUser user = mock(FableUser.class);
        when(bookRepository.findByIdWithBookFiles(bookId)).thenReturn(Optional.of(book));
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(user.getId()).thenReturn(2L);

        PdfViewerPreferencesEntity pdfPrefs = new PdfViewerPreferencesEntity();
        when(pdfViewerPreferencesRepository.findByBookIdAndUserId(bookId, 2L)).thenReturn(Optional.of(pdfPrefs));
        BookViewerSettings settings = BookViewerSettings.builder()
                .pdfSettings(PdfViewerPreferences.builder()
                        .zoom("1.5")
                        .spread("spread")
                        .build())
                .build();

        bookUpdateService.updateBookViewerSetting(bookId, settings);

        verify(pdfViewerPreferencesRepository).save(pdfPrefs);
        assertEquals("1.5", pdfPrefs.getZoom());
        assertEquals("spread", pdfPrefs.getSpread());
    }

    @Test
    void updateBookViewerSetting_epub_shouldUpdateEpubPrefsV2() {
        long bookId = 1L;
        BookEntity book = new BookEntity();
        book.setId(bookId);
        BookFileEntity primaryFile = new BookFileEntity();
        primaryFile.setBook(book);
        primaryFile.setBookType(BookFileType.EPUB);
        book.setBookFiles(List.of(primaryFile));
        FableUser user = mock(FableUser.class);
        when(bookRepository.findByIdWithBookFiles(bookId)).thenReturn(Optional.of(book));
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(user.getId()).thenReturn(2L);

        EbookViewerPreferenceEntity epubPrefs = new EbookViewerPreferenceEntity();
        when(ebookViewerPreferenceRepository.findByBookIdAndUserId(bookId, 2L)).thenReturn(Optional.of(epubPrefs));
        BookViewerSettings settings = BookViewerSettings.builder()
                .ebookSettings(EbookViewerPreferences.builder()
                        .fontFamily("serif")
                        .fontSize(18)
                        .gap(0.1f)
                        .hyphenate(true)
                        .isDark(true)
                        .justify(true)
                        .lineHeight(1.7f)
                        .maxBlockSize(800)
                        .maxColumnCount(3)
                        .maxInlineSize(1200)
                        .theme("dark")
                        .flow("paginated")
                        .build())
                .build();

        bookUpdateService.updateBookViewerSetting(bookId, settings);

        verify(ebookViewerPreferenceRepository).save(epubPrefs);
        assertEquals("serif", epubPrefs.getFontFamily());
        assertEquals(18, epubPrefs.getFontSize());
        assertEquals(0.1f, epubPrefs.getGap());
        assertTrue(epubPrefs.getHyphenate());
        assertTrue(epubPrefs.getIsDark());
        assertTrue(epubPrefs.getJustify());
        assertEquals(1.7f, epubPrefs.getLineHeight());
        assertEquals(800, epubPrefs.getMaxBlockSize());
        assertEquals(3, epubPrefs.getMaxColumnCount());
        assertEquals(1200, epubPrefs.getMaxInlineSize());
        assertEquals("dark", epubPrefs.getTheme());
        assertEquals("paginated", epubPrefs.getFlow());
    }

    @Test
    void updateBookViewerSetting_unsupportedType_shouldThrow() {
        long bookId = 1L;
        BookEntity book = new BookEntity();
        book.setId(bookId);
        BookFileEntity primaryFile = new BookFileEntity();
        primaryFile.setBook(book);
        primaryFile.setBookType(null);
        book.setBookFiles(List.of(primaryFile));
        when(bookRepository.findByIdWithBookFiles(bookId)).thenReturn(Optional.of(book));
        when(authenticationService.getAuthenticatedUser()).thenReturn(mock(FableUser.class));
        BookViewerSettings settings = BookViewerSettings.builder().build();

        assertThrows(APIException.class, () -> bookUpdateService.updateBookViewerSetting(bookId, settings));
    }

    @Test
    void updateReadStatus_shouldUpdateExistingAndCreateNew() {
        FableUser user = mock(FableUser.class);
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(user.getId()).thenReturn(1L);

        FableUser.UserPermissions permissions = mock(FableUser.UserPermissions.class);
        when(user.getPermissions()).thenReturn(permissions);
        when(permissions.isCanBulkResetBookReadStatus()).thenReturn(true);

        List<Long> bookIds = Arrays.asList(1L, 2L, 3L);
        when(bookRepository.countByIdIn(bookIds)).thenReturn(3L);
        Set<Long> existing = new HashSet<>(Arrays.asList(1L, 2L));
        when(userBookProgressRepository.findExistingProgressBookIds(1L, new HashSet<>(bookIds))).thenReturn(existing);

        FableUserEntity userEntity = new FableUserEntity();
        when(userRepository.findById(1L)).thenReturn(Optional.of(userEntity));

        List<BookStatusUpdateResponse> result = bookUpdateService.updateReadStatus(bookIds, "READ");
        verify(userBookProgressRepository).bulkUpdateReadStatus(eq(1L), eq(new ArrayList<>(existing)), eq(ReadStatus.READ), any(), any());
        verify(userBookProgressRepository).saveAll(anyList());
        assertEquals(3, result.size());
        assertEquals(ReadStatus.READ, result.getFirst().getReadStatus());
    }

    @Test
    void updateReadStatus_shouldThrowIfNoBulkPermission() {
        FableUser user = mock(FableUser.class);
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(user.getId()).thenReturn(1L);

        FableUser.UserPermissions permissions = mock(FableUser.UserPermissions.class);
        when(user.getPermissions()).thenReturn(permissions);
        when(permissions.isCanBulkResetBookReadStatus()).thenReturn(false);

        List<Long> bookIds = Arrays.asList(1L, 2L, 3L);
        when(bookRepository.countByIdIn(bookIds)).thenReturn(3L);

        assertThrows(APIException.class, () -> bookUpdateService.updateReadStatus(bookIds, "READ"));
    }

    @Test
    void updateReadStatus_shouldAllowSingleBookWithoutPermission() {
        FableUser user = mock(FableUser.class);
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(user.getId()).thenReturn(1L);

        FableUser.UserPermissions permissions = mock(FableUser.UserPermissions.class);
        when(user.getPermissions()).thenReturn(permissions);
        when(permissions.isCanBulkResetBookReadStatus()).thenReturn(false);

        List<Long> bookIds = Collections.singletonList(1L);
        when(bookRepository.countByIdIn(bookIds)).thenReturn(1L);
        Set<Long> existing = new HashSet<>(bookIds);
        when(userBookProgressRepository.findExistingProgressBookIds(1L, new HashSet<>(bookIds))).thenReturn(existing);

        FableUserEntity userEntity = new FableUserEntity();
        when(userRepository.findById(1L)).thenReturn(Optional.of(userEntity));

        assertDoesNotThrow(() -> bookUpdateService.updateReadStatus(bookIds, "READ"));
    }

    @Test
    void updatePersonalRating_shouldUpdateAndCreate() {
        FableUser user = mock(FableUser.class);
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(user.getId()).thenReturn(1L);

        List<Long> bookIds = Arrays.asList(1L, 2L, 3L);
        when(bookRepository.countByIdIn(bookIds)).thenReturn(3L);
        Set<Long> existing = new HashSet<>(Arrays.asList(1L, 2L));
        when(userBookProgressRepository.findExistingProgressBookIds(1L, new HashSet<>(bookIds))).thenReturn(existing);

        FableUserEntity userEntity = new FableUserEntity();
        when(userRepository.findById(1L)).thenReturn(Optional.of(userEntity));

        List<PersonalRatingUpdateResponse> result = bookUpdateService.updatePersonalRating(bookIds, 5);
        verify(userBookProgressRepository).bulkUpdatePersonalRating(eq(1L), eq(new ArrayList<>(existing)), eq(5));
        verify(userBookProgressRepository).saveAll(anyList());
        assertEquals(3, result.size());
        assertEquals(5, result.getFirst().getPersonalRating());
    }

    @Test
    void resetPersonalRating_shouldUpdateExisting() {
        FableUser user = mock(FableUser.class);
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(user.getId()).thenReturn(1L);

        List<Long> bookIds = Arrays.asList(1L, 2L);
        when(bookRepository.countByIdIn(bookIds)).thenReturn(2L);
        Set<Long> existing = new HashSet<>(bookIds);
        when(userBookProgressRepository.findExistingProgressBookIds(1L, new HashSet<>(bookIds))).thenReturn(existing);

        List<PersonalRatingUpdateResponse> result = bookUpdateService.resetPersonalRating(bookIds);
        verify(userBookProgressRepository).bulkUpdatePersonalRating(eq(1L), eq(new ArrayList<>(bookIds)), isNull());
        assertEquals(2, result.size());
        assertNull(result.getFirst().getPersonalRating());
    }

    @Test
    void assignShelvesToBooks_shouldAssignAndReturnBooks() {
        FableUser user = mock(FableUser.class);
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(user.getId()).thenReturn(1L);

        FableUserEntity userEntity = new FableUserEntity();
        ShelfEntity shelf1 = new ShelfEntity();
        shelf1.setId(10L);
        ShelfEntity shelf2 = new ShelfEntity();
        shelf2.setId(20L);
        userEntity.setShelves(new HashSet<>(Arrays.asList(shelf1, shelf2)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(userEntity));

        Set<Long> bookIds = new HashSet<>(Arrays.asList(1L, 2L));
        Set<Long> assignIds = new HashSet<>(Collections.singletonList(10L));
        Set<Long> unassignIds = new HashSet<>(Collections.singletonList(20L));

        BookEntity bookEntity1 = spy(new BookEntity());
        bookEntity1.setId(1L);
        bookEntity1.setShelves(new HashSet<>());
        LibraryPathEntity libraryPath1 = new LibraryPathEntity();
        libraryPath1.setPath("/mock/path/1");
        doReturn(libraryPath1).when(bookEntity1).getLibraryPath();
        BookFileEntity bookFileEntity1 = new BookFileEntity();
        bookFileEntity1.setBook(bookEntity1);
        bookFileEntity1.setFileSubPath("sub1");
        bookFileEntity1.setFileName("file1.pdf");
        bookEntity1.setBookFiles(List.of(bookFileEntity1));

        BookEntity bookEntity2 = spy(new BookEntity());
        bookEntity2.setId(2L);
        bookEntity2.setShelves(new HashSet<>());
        LibraryPathEntity libraryPath2 = new LibraryPathEntity();
        libraryPath2.setPath("/mock/path/2");
        doReturn(libraryPath2).when(bookEntity2).getLibraryPath();
        BookFileEntity bookFileEntity2 = new BookFileEntity();
        bookFileEntity2.setBook(bookEntity2);
        bookFileEntity2.setFileSubPath("sub2");
        bookFileEntity2.setFileName("file2.pdf");
        bookEntity2.setBookFiles(List.of(bookFileEntity2));

        when(bookQueryService.findAllWithMetadataByIds(bookIds)).thenReturn(Arrays.asList(bookEntity1, bookEntity2));
        ShelfEntity assignShelf = new ShelfEntity();
        assignShelf.setId(10L);
        when(shelfRepository.findAllById(assignIds)).thenReturn(Collections.singletonList(assignShelf));
        Book mockBook = mock(Book.class);
        when(bookMapper.toBook(any())).thenReturn(mockBook);

        when(readingProgressService.fetchUserProgress(eq(1L), anySet())).thenReturn(Collections.emptyMap());
        when(readingProgressService.fetchUserFileProgress(eq(1L), anySet())).thenReturn(Collections.emptyMap());

        List<Book> result = bookUpdateService.assignShelvesToBooks(bookIds, assignIds, unassignIds);
        verify(bookRepository).saveAll(anyList());
        assertEquals(2, result.size());
    }

    private FableUser.UserPermissions perms(boolean canEditMetadata, boolean admin) {
        FableUser.UserPermissions p = new FableUser.UserPermissions();
        p.setCanEditMetadata(canEditMetadata);
        p.setAdmin(admin);
        return p;
    }

    @Test
    void releaseFromStaging_clearsStagedFlagAndRejectsOrphanedProposals() {
        FableUser user = FableUser.builder()
                .id(1L)
                .permissions(perms(true, false))
                .build();
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(libraryVisibilityService.getAccessibleLibraryIds(user)).thenReturn(Set.of(10L));

        LibraryEntity library = new LibraryEntity();
        library.setId(10L);

        BookEntity bookEntity = new BookEntity();
        bookEntity.setId(1L);
        bookEntity.setLibrary(library);
        bookEntity.setStaged(true);
        bookEntity.setStagingCompletedAt(java.time.Instant.now());
        bookEntity.setBookFiles(List.of());

        when(bookQueryService.findAllWithMetadataByIds(Set.of(1L))).thenReturn(List.of(bookEntity));
        when(bookMapper.toBook(any())).thenReturn(mock(Book.class));
        when(readingProgressService.fetchUserProgress(eq(1L), anySet())).thenReturn(Collections.emptyMap());
        when(readingProgressService.fetchUserFileProgress(eq(1L), anySet())).thenReturn(Collections.emptyMap());

        List<Book> result = bookUpdateService.releaseFromStaging(Set.of(1L));

        assertFalse(Boolean.TRUE.equals(bookEntity.getStaged()));
        assertNull(bookEntity.getStagingCompletedAt());
        verify(bookRepository).saveAll(List.of(bookEntity));
        verify(proposalRepository).updateStatusByBookIdsAndStatus(
                eq(Set.of(1L)),
                eq(FetchedMetadataProposalStatus.FETCHED),
                eq(FetchedMetadataProposalStatus.REJECTED),
                any(),
                eq(1L)
        );
        assertEquals(1, result.size());
    }

    @Test
    void releaseFromStaging_rejectsUserWithoutEditMetadataPermission() {
        FableUser readOnlyUser = FableUser.builder()
                .id(2L)
                .permissions(perms(false, false))
                .build();
        when(authenticationService.getAuthenticatedUser()).thenReturn(readOnlyUser);

        assertThrows(APIException.class, () -> bookUpdateService.releaseFromStaging(Set.of(1L)));
        verify(bookRepository, never()).saveAll(any());
    }

    @Test
    void releaseFromStaging_rejectsBookOutsideAccessibleLibraries() {
        FableUser user = FableUser.builder()
                .id(3L)
                .permissions(perms(true, false))
                .build();
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(libraryVisibilityService.getAccessibleLibraryIds(user)).thenReturn(Set.of(10L));

        LibraryEntity forbiddenLib = new LibraryEntity();
        forbiddenLib.setId(99L);
        BookEntity bookEntity = new BookEntity();
        bookEntity.setId(1L);
        bookEntity.setLibrary(forbiddenLib);
        bookEntity.setStaged(true);

        when(bookQueryService.findAllWithMetadataByIds(Set.of(1L))).thenReturn(List.of(bookEntity));

        assertThrows(APIException.class, () -> bookUpdateService.releaseFromStaging(Set.of(1L)));
        verify(bookRepository, never()).saveAll(any());
    }

    @Test
    void stageForReview_allowsBackgroundSystemExecutionWhenUserIsNull() {
        when(authenticationService.getAuthenticatedUser()).thenReturn(null);

        BookEntity bookEntity = new BookEntity();
        bookEntity.setId(5L);
        bookEntity.setStaged(false);
        bookEntity.setStagingCompletedAt(java.time.Instant.now());

        when(bookQueryService.findAllWithMetadataByIds(Set.of(5L))).thenReturn(List.of(bookEntity));
        when(bookMapper.toBook(bookEntity)).thenReturn(Book.builder().id(5L).staged(true).build());

        List<Book> result = bookUpdateService.stageForReview(Set.of(5L));

        assertTrue(Boolean.TRUE.equals(bookEntity.getStaged()));
        assertNull(bookEntity.getStagingCompletedAt());
        verify(bookRepository).saveAll(List.of(bookEntity));
        assertEquals(1, result.size());
    }

    @Test
    void assignShelvesToBooks_shouldThrowIfUnauthorized() {
        FableUser user = mock(FableUser.class);
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(user.getId()).thenReturn(1L);

        FableUserEntity userEntity = new FableUserEntity();
        ShelfEntity shelf1 = new ShelfEntity();
        shelf1.setId(10L);
        userEntity.setShelves(new HashSet<>(Collections.singletonList(shelf1)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(userEntity));

        Set<Long> bookIds = new HashSet<>(Collections.singletonList(1L));
        Set<Long> assignIds = new HashSet<>(Collections.singletonList(99L));
        Set<Long> unassignIds = new HashSet<>();

        assertThrows(APIException.class, () -> bookUpdateService.assignShelvesToBooks(bookIds, assignIds, unassignIds));
    }
}
