package org.fable.service.library;

import org.fable.exception.APIException;
import org.fable.model.dto.settings.LibraryFile;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.BookFileEntity;
import org.fable.model.entity.LibraryEntity;
import org.fable.model.entity.LibraryPathEntity;
import org.fable.repository.BookAdditionalFileRepository;
import org.fable.repository.BookRepository;
import org.fable.repository.LibraryRepository;
import org.fable.service.book.PhysicalBookService;
import org.fable.service.NotificationService;
import org.fable.task.options.RescanLibraryContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LibraryProcessingServiceTest {

    @Mock
    private LibraryRepository libraryRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private org.fable.service.FailureNotificationService failureNotificationService;
    @Mock
    private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock
    private FileAsBookProcessor fileAsBookProcessor;
    @Mock
    private BookRestorationService bookRestorationService;
    @Mock
    private BookDeletionService bookDeletionService;
    @Mock
    private LibraryFileHelper libraryFileHelper;
    @Mock
    private BookGroupingService bookGroupingService;
    @Mock
        private DirectoryTagTaskStarter directoryTagTaskStarter;
        @Mock
        private PhysicalBookService physicalBookService;
    @Mock
    private EntityManager entityManager;

    private LibraryProcessingService libraryProcessingService;

    @BeforeEach
    void setUp() {
        libraryProcessingService = new LibraryProcessingService(
                libraryRepository,
                bookRepository,
                notificationService,
                failureNotificationService,
                bookAdditionalFileRepository,
                fileAsBookProcessor,
                bookRestorationService,
                bookDeletionService,
                libraryFileHelper,
                bookGroupingService,
                                directoryTagTaskStarter,
                                physicalBookService,
                entityManager
        );
    }

    @Test
    void processLibrary_shouldOnlyProcessNewFiles() throws IOException {
        long libraryId = 1L;
        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Test Library");

        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(10L);
        pathEntity.setPath("/library");

        BookEntity existingBook = new BookEntity();
        existingBook.setLibraryPath(pathEntity);
        BookFileEntity existingBookFile = new BookFileEntity();
        existingBookFile.setBook(existingBook);
        existingBook.setBookFiles(List.of(existingBookFile));
        existingBook.getPrimaryBookFile().setFileSubPath("");
        existingBook.getPrimaryBookFile().setFileName("book1.epub");
        libraryEntity.setBookEntities(List.of(existingBook));

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));
        when(bookRepository.findAllByLibraryIdWithFilesAndPathIncludingRemoved(libraryId)).thenReturn(List.of(existingBook));

        LibraryFile existingFile = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(pathEntity)
                .fileSubPath("")
                .fileName("book1.epub")
                .build();

        LibraryFile newFile = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(pathEntity)
                .fileSubPath("")
                .fileName("book2.epub")
                .build();

        when(libraryFileHelper.getLibraryFiles(libraryEntity)).thenReturn(List.of(existingFile, newFile));
        when(bookAdditionalFileRepository.findByLibraryId(libraryId)).thenReturn(Collections.emptyList());

        // Mock grouping service to pass through the files
        when(bookGroupingService.groupForInitialScan(anyList(), eq(libraryEntity)))
                .thenAnswer(invocation -> {
                    List<LibraryFile> files = invocation.getArgument(0);
                    Map<String, List<LibraryFile>> result = new LinkedHashMap<>();
                    for (LibraryFile f : files) {
                        result.put(f.getFileName(), List.of(f));
                    }
                    return result;
                });

        libraryProcessingService.processLibrary(libraryId);

        ArgumentCaptor<List<LibraryFile>> captor = libraryFileListCaptor();
        verify(bookGroupingService).groupForInitialScan(captor.capture(), eq(libraryEntity));

        List<LibraryFile> processedFiles = captor.getValue();

        assertThat(processedFiles).hasSize(1);
        assertThat(processedFiles.getFirst().getFileName()).isEqualTo("book2.epub");
    }

    @Test
    void processLibrary_noNewFiles_shouldProcessNothing() throws IOException {
        long libraryId = 1L;
        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Test Library");

        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(10L);
        pathEntity.setPath("/library");

        BookEntity existingBook = new BookEntity();
        existingBook.setLibraryPath(pathEntity);
        BookFileEntity existingBookFile = new BookFileEntity();
        existingBookFile.setBook(existingBook);
        existingBook.setBookFiles(List.of(existingBookFile));
        existingBook.getPrimaryBookFile().setFileSubPath("");
        existingBook.getPrimaryBookFile().setFileName("book1.epub");
        libraryEntity.setBookEntities(List.of(existingBook));

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));
        when(bookRepository.findAllByLibraryIdWithFilesAndPathIncludingRemoved(libraryId)).thenReturn(List.of(existingBook));

        LibraryFile existingFile = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(pathEntity)
                .fileSubPath("")
                .fileName("book1.epub")
                .build();

        when(libraryFileHelper.getLibraryFiles(libraryEntity)).thenReturn(List.of(existingFile));
        when(bookAdditionalFileRepository.findByLibraryId(libraryId)).thenReturn(Collections.emptyList());
        when(bookGroupingService.groupForInitialScan(anyList(), eq(libraryEntity))).thenReturn(Collections.emptyMap());

        libraryProcessingService.processLibrary(libraryId);

        ArgumentCaptor<List<LibraryFile>> captor = libraryFileListCaptor();
        verify(bookGroupingService).groupForInitialScan(captor.capture(), eq(libraryEntity));

        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    void processLibrary_allNewFiles_shouldProcessAll() throws IOException {
        long libraryId = 1L;
        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Test Library");
        libraryEntity.setBookEntities(Collections.emptyList());

        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(10L);
        pathEntity.setPath("/library");

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));

        LibraryFile newFile1 = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(pathEntity)
                .fileSubPath("")
                .fileName("book1.epub")
                .build();
        LibraryFile newFile2 = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(pathEntity)
                .fileSubPath("")
                .fileName("book2.epub")
                .build();

        when(libraryFileHelper.getLibraryFiles(libraryEntity)).thenReturn(List.of(newFile1, newFile2));
        when(bookAdditionalFileRepository.findByLibraryId(libraryId)).thenReturn(Collections.emptyList());
        when(bookGroupingService.groupForInitialScan(anyList(), eq(libraryEntity)))
                .thenAnswer(invocation -> {
                    List<LibraryFile> files = invocation.getArgument(0);
                    Map<String, List<LibraryFile>> result = new LinkedHashMap<>();
                    for (LibraryFile f : files) {
                        result.put(f.getFileName(), List.of(f));
                    }
                    return result;
                });

        libraryProcessingService.processLibrary(libraryId);

        ArgumentCaptor<List<LibraryFile>> captor = libraryFileListCaptor();
        verify(bookGroupingService).groupForInitialScan(captor.capture(), eq(libraryEntity));

        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    void processLibrary_shouldQueueScopedDirectoryTaggingForImportedBooks() throws IOException {
        long libraryId = 1L;

        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Tagged Library");
        libraryEntity.setTagByDirectory(true);

        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(10L);
        pathEntity.setPath("/library");
        libraryEntity.setLibraryPaths(List.of(pathEntity));

        BookEntity existingBook = new BookEntity();
        existingBook.setId(11L);
        existingBook.setLibraryPath(pathEntity);
        BookFileEntity existingBookFile = new BookFileEntity();
        existingBookFile.setBook(existingBook);
        existingBook.setBookFiles(List.of(existingBookFile));
        existingBook.getPrimaryBookFile().setFileSubPath("");
        existingBook.getPrimaryBookFile().setFileName("known.epub");

        BookEntity importedBook = new BookEntity();
        importedBook.setId(99L);
        importedBook.setLibraryPath(pathEntity);
        BookFileEntity importedBookFile = new BookFileEntity();
        importedBookFile.setBook(importedBook);
        importedBook.setBookFiles(List.of(importedBookFile));
        importedBook.getPrimaryBookFile().setFileSubPath("incoming");
        importedBook.getPrimaryBookFile().setFileName("new.epub");

        LibraryFile existingFile = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(pathEntity)
                .fileSubPath("")
                .fileName("known.epub")
                .build();
        LibraryFile newFile = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(pathEntity)
                .fileSubPath("incoming")
                .fileName("new.epub")
                .build();

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));
        when(bookRepository.findAllByLibraryIdWithFilesAndPathIncludingRemoved(libraryId))
                .thenReturn(List.of(existingBook))
                .thenReturn(List.of(existingBook, importedBook));
        when(libraryFileHelper.getLibraryFiles(libraryEntity)).thenReturn(List.of(existingFile, newFile));
        when(bookAdditionalFileRepository.findByLibraryId(libraryId)).thenReturn(Collections.emptyList());
        when(bookGroupingService.groupForInitialScan(anyList(), eq(libraryEntity)))
                .thenReturn(Map.of("new.epub", List.of(newFile)));

        libraryProcessingService.processLibrary(libraryId);

        verify(directoryTagTaskStarter).scheduleBooks(libraryId, Set.of(99L));
        verify(directoryTagTaskStarter, never()).scheduleLibrary(libraryId);
    }

        @Test
        void scanLibraryForNewFiles_shouldImportOnlyNewFilesWithoutDeletionFlow(@TempDir Path tempDir) throws IOException {
                long libraryId = 7L;

                LibraryPathEntity pathEntity = new LibraryPathEntity();
                pathEntity.setId(10L);
                pathEntity.setPath(tempDir.toString());

                LibraryEntity libraryEntity = new LibraryEntity();
                libraryEntity.setId(libraryId);
                libraryEntity.setName("Incremental Library");
                libraryEntity.setLibraryPaths(List.of(pathEntity));
                libraryEntity.setBookEntities(new ArrayList<>());

                BookEntity existingBook = new BookEntity();
                existingBook.setLibraryPath(pathEntity);
                BookFileEntity existingBookFile = new BookFileEntity();
                existingBookFile.setBook(existingBook);
                existingBook.setBookFiles(List.of(existingBookFile));
                existingBook.getPrimaryBookFile().setFileSubPath("");
                existingBook.getPrimaryBookFile().setFileName("known.epub");

                LibraryFile existingFile = LibraryFile.builder()
                                .libraryEntity(libraryEntity)
                                .libraryPathEntity(pathEntity)
                                .fileSubPath("")
                                .fileName("known.epub")
                                .build();

                LibraryFile newFile = LibraryFile.builder()
                                .libraryEntity(libraryEntity)
                                .libraryPathEntity(pathEntity)
                                .fileSubPath("")
                                .fileName("new.epub")
                                .build();

                when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));
                when(bookRepository.findAllByLibraryIdWithFilesAndPathIncludingRemoved(libraryId)).thenReturn(List.of(existingBook));
                when(bookAdditionalFileRepository.findByLibraryId(libraryId)).thenReturn(Collections.emptyList());
                when(libraryFileHelper.getLibraryFiles(libraryEntity)).thenReturn(List.of(existingFile, newFile));
                when(bookGroupingService.groupForInitialScan(anyList(), eq(libraryEntity)))
                                .thenAnswer(invocation -> {
                                        List<LibraryFile> files = invocation.getArgument(0);
                                        Map<String, List<LibraryFile>> result = new LinkedHashMap<>();
                                        for (LibraryFile file : files) {
                                                result.put(file.getFileName(), List.of(file));
                                        }
                                        return result;
                                });

                libraryProcessingService.scanLibraryForNewFiles(libraryId);

                ArgumentCaptor<List<LibraryFile>> captor = libraryFileListCaptor();
                verify(bookGroupingService).groupForInitialScan(captor.capture(), eq(libraryEntity));
                assertThat(captor.getValue()).containsExactly(newFile);

                verify(bookDeletionService, never()).deleteRemovedAdditionalFiles(anyList());
                verify(bookDeletionService, never()).processDeletedLibraryFiles(anyList(), anyList());
                verify(bookDeletionService, never()).deleteRemovedBooks(anyList());
                verify(bookDeletionService, never()).purgeDisallowedFormats(any(LibraryEntity.class));
                verify(bookRestorationService, never()).restoreDeletedBooks(anyList());
        }

        @Test
        void scanLibraryForNewFiles_shouldSkipDirectoryTagTaskWhenNothingImported(@TempDir Path tempDir) throws IOException {
                long libraryId = 8L;

                LibraryPathEntity pathEntity = new LibraryPathEntity();
                pathEntity.setId(20L);
                pathEntity.setPath(tempDir.toString());

                LibraryEntity libraryEntity = new LibraryEntity();
                libraryEntity.setId(libraryId);
                libraryEntity.setName("Already Synced");
                libraryEntity.setTagByDirectory(true);
                libraryEntity.setLibraryPaths(List.of(pathEntity));

                BookEntity existingBook = new BookEntity();
                existingBook.setId(12L);
                existingBook.setLibraryPath(pathEntity);
                BookFileEntity existingBookFile = new BookFileEntity();
                existingBookFile.setBook(existingBook);
                existingBook.setBookFiles(List.of(existingBookFile));
                existingBook.getPrimaryBookFile().setFileSubPath("");
                existingBook.getPrimaryBookFile().setFileName("known.epub");

                LibraryFile existingFile = LibraryFile.builder()
                                .libraryEntity(libraryEntity)
                                .libraryPathEntity(pathEntity)
                                .fileSubPath("")
                                .fileName("known.epub")
                                .build();

                when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));
                when(bookRepository.findAllByLibraryIdWithFilesAndPathIncludingRemoved(libraryId)).thenReturn(List.of(existingBook));
                when(bookAdditionalFileRepository.findByLibraryId(libraryId)).thenReturn(Collections.emptyList());
                when(libraryFileHelper.getLibraryFiles(libraryEntity)).thenReturn(List.of(existingFile));
                when(bookGroupingService.groupForInitialScan(anyList(), eq(libraryEntity))).thenReturn(Collections.emptyMap());

                libraryProcessingService.scanLibraryForNewFiles(libraryId);

                verify(directoryTagTaskStarter, never()).scheduleBooks(anyLong(), anySet());
                verify(directoryTagTaskStarter, never()).scheduleLibrary(anyLong());
        }

    @Test
    void processLibrary_newFileInSubdirectory_shouldProcess() throws IOException {
        long libraryId = 1L;
        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Test Library");
        libraryEntity.setBookEntities(Collections.emptyList());

        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(10L);
        pathEntity.setPath("/library");

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));

        LibraryFile newFileInSub = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(pathEntity)
                .fileSubPath("fantasy")
                .fileName("book1.epub")
                .build();

        when(libraryFileHelper.getLibraryFiles(libraryEntity)).thenReturn(List.of(newFileInSub));
        when(bookAdditionalFileRepository.findByLibraryId(libraryId)).thenReturn(Collections.emptyList());
        when(bookGroupingService.groupForInitialScan(anyList(), eq(libraryEntity)))
                .thenAnswer(invocation -> {
                    List<LibraryFile> files = invocation.getArgument(0);
                    Map<String, List<LibraryFile>> result = new LinkedHashMap<>();
                    for (LibraryFile f : files) {
                        result.put(f.getFileName(), List.of(f));
                    }
                    return result;
                });

        libraryProcessingService.processLibrary(libraryId);

        ArgumentCaptor<List<LibraryFile>> captor = libraryFileListCaptor();
        verify(bookGroupingService).groupForInitialScan(captor.capture(), eq(libraryEntity));

        List<LibraryFile> processedFiles = captor.getValue();
        assertThat(processedFiles).hasSize(1);
        assertThat(processedFiles.getFirst().getFileName()).isEqualTo("book1.epub");
        assertThat(processedFiles.getFirst().getFileSubPath()).isEqualTo("fantasy");
    }

    @Test
    void processLibrary_additionalFile_shouldNotProcess() throws IOException {
        long libraryId = 1L;
        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Test Library");
        libraryEntity.setBookEntities(Collections.emptyList());

        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(10L);
        pathEntity.setPath("/library");

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));

        LibraryFile additionalFileAsLibraryFile = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(pathEntity)
                .fileSubPath("")
                .fileName("extra.pdf")
                .build();

        BookEntity parentBook = new BookEntity();
        parentBook.setLibraryPath(pathEntity);

        BookFileEntity additionalFileEntity = new BookFileEntity();
        additionalFileEntity.setBook(parentBook);
        additionalFileEntity.setFileSubPath("");
        additionalFileEntity.setFileName("extra.pdf");

        when(libraryFileHelper.getLibraryFiles(libraryEntity)).thenReturn(List.of(additionalFileAsLibraryFile));
        when(bookAdditionalFileRepository.findByLibraryId(libraryId)).thenReturn(List.of(additionalFileEntity));
        when(bookGroupingService.groupForInitialScan(anyList(), eq(libraryEntity))).thenReturn(Collections.emptyMap());

        libraryProcessingService.processLibrary(libraryId);

        ArgumentCaptor<List<LibraryFile>> captor = libraryFileListCaptor();
        verify(bookGroupingService).groupForInitialScan(captor.capture(), eq(libraryEntity));

        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    void rescanLibrary_shouldDeleteRemovedBookFormatAdditionalFiles(@TempDir Path tempDir) throws IOException {
        long libraryId = 1L;
        Path accessiblePath = tempDir.resolve("library");
        Files.createDirectory(accessiblePath);

        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Test Library");

        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(10L);
        pathEntity.setPath(accessiblePath.toString());
        libraryEntity.setLibraryPaths(List.of(pathEntity));

        BookEntity book = new BookEntity();
        book.setId(11L);
        book.setLibrary(libraryEntity);
        book.setLibraryPath(pathEntity);

        BookFileEntity epub = new BookFileEntity();
        epub.setId(1L);
        epub.setBook(book);
        epub.setFileSubPath("author/title");
        epub.setFileName("book.epub");
        epub.setBookFormat(true);

        BookFileEntity pdf = new BookFileEntity();
        pdf.setId(2L);
        pdf.setBook(book);
        pdf.setFileSubPath("author/title");
        pdf.setFileName("book.pdf");
        pdf.setBookFormat(true);

        BookFileEntity image = new BookFileEntity();
        image.setId(3L);
        image.setBook(book);
        image.setFileSubPath("author/title");
        image.setFileName("image.png");
        image.setBookFormat(false);

        book.setBookFiles(List.of(epub, pdf, image));
        libraryEntity.setBookEntities(List.of(book));

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));

        // Only epub exists on disk, pdf was removed
        LibraryFile epubOnDisk = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(pathEntity)
                .fileSubPath("author/title")
                .fileName("book.epub")
                .build();

        when(libraryFileHelper.getAllLibraryFiles(libraryEntity)).thenReturn(List.of(epubOnDisk));
        when(libraryFileHelper.filterByAllowedFormats(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookAdditionalFileRepository.findByLibraryId(libraryId)).thenReturn(List.of(epub, pdf, image));
        when(bookGroupingService.groupForRescan(anyList(), eq(libraryEntity)))
                .thenReturn(new BookGroupingService.GroupingResult(Collections.emptyMap(), Collections.emptyMap()));

        libraryProcessingService.rescanLibrary(RescanLibraryContext.builder().libraryId(libraryId).build());

        // pdf (book format) should be deleted; image (non-book format) should NOT be deleted
        ArgumentCaptor<List<Long>> captor = longListCaptor();
        verify(bookDeletionService).deleteRemovedAdditionalFiles(captor.capture());
        assertThat(captor.getValue()).containsExactly(2L);
    }

    @Test
    void rescanLibrary_shouldAbortWhenPathNotAccessible(@TempDir Path tempDir) {
        long libraryId = 1L;
        Path nonExistentPath = tempDir.resolve("non_existent_path");

        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Test Library");

        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(10L);
        pathEntity.setPath(nonExistentPath.toString());
        libraryEntity.setLibraryPaths(List.of(pathEntity));
        libraryEntity.setBookEntities(Collections.emptyList());

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));

        RescanLibraryContext context = RescanLibraryContext.builder().libraryId(libraryId).build();

        assertThatThrownBy(() -> libraryProcessingService.rescanLibrary(context))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("not accessible");

        verify(bookDeletionService, never()).processDeletedLibraryFiles(any(), any());
        verify(bookDeletionService, never()).deleteRemovedAdditionalFiles(any());
    }

    @Test
    void rescanLibrary_shouldAbortWhenLibraryHasBooksButScanReturnsEmpty(@TempDir Path tempDir) throws IOException {
        long libraryId = 1L;
        Path accessiblePath = tempDir.resolve("accessible");
        Files.createDirectory(accessiblePath);

        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Test Library");

        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(10L);
        pathEntity.setPath(accessiblePath.toString());
        libraryEntity.setLibraryPaths(List.of(pathEntity));

        BookEntity existingBook = new BookEntity();
        existingBook.setId(1L);
        existingBook.setLibraryPath(pathEntity);
        BookFileEntity existingBookFile = new BookFileEntity();
        existingBookFile.setBook(existingBook);
        existingBook.setBookFiles(List.of(existingBookFile));
        existingBook.getPrimaryBookFile().setFileSubPath("");
        existingBook.getPrimaryBookFile().setFileName("book1.epub");
        libraryEntity.setBookEntities(List.of(existingBook));

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));
        when(libraryFileHelper.getAllLibraryFiles(libraryEntity)).thenReturn(Collections.emptyList());

        RescanLibraryContext context = RescanLibraryContext.builder().libraryId(libraryId).build();

        assertThatThrownBy(() -> libraryProcessingService.rescanLibrary(context))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("not accessible");

        verify(bookDeletionService, never()).processDeletedLibraryFiles(any(), any());
        verify(bookDeletionService, never()).deleteRemovedAdditionalFiles(any());
    }

    @Test
    void rescanLibrary_shouldProceedWhenLibraryHasBooksAndScanFindsThem(@TempDir Path tempDir) throws IOException {
        long libraryId = 1L;
        Path accessiblePath = tempDir.resolve("accessible");
        Files.createDirectory(accessiblePath);

        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Test Library");

        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(10L);
        pathEntity.setPath(accessiblePath.toString());
        libraryEntity.setLibraryPaths(List.of(pathEntity));

        BookEntity existingBook = new BookEntity();
        existingBook.setId(1L);
        existingBook.setLibraryPath(pathEntity);
        BookFileEntity existingBookFile = new BookFileEntity();
        existingBookFile.setBook(existingBook);
        existingBook.setBookFiles(List.of(existingBookFile));
        existingBook.getPrimaryBookFile().setFileSubPath("");
        existingBook.getPrimaryBookFile().setFileName("book1.epub");
        libraryEntity.setBookEntities(List.of(existingBook));

        LibraryFile fileOnDisk = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(pathEntity)
                .fileSubPath("")
                .fileName("book1.epub")
                .build();

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));
        when(libraryFileHelper.getAllLibraryFiles(libraryEntity)).thenReturn(List.of(fileOnDisk));
        when(libraryFileHelper.filterByAllowedFormats(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookAdditionalFileRepository.findByLibraryId(libraryId)).thenReturn(Collections.emptyList());
        when(bookGroupingService.groupForRescan(anyList(), eq(libraryEntity)))
                .thenReturn(new BookGroupingService.GroupingResult(Collections.emptyMap(), Collections.emptyMap()));

        RescanLibraryContext context = RescanLibraryContext.builder().libraryId(libraryId).build();

        libraryProcessingService.rescanLibrary(context);

        verify(bookDeletionService, never()).processDeletedLibraryFiles(any(), any());
    }

    @Test
    void rescanLibrary_shouldProceedForEmptyLibraryWithNoFilesFound(@TempDir Path tempDir) throws IOException {
        long libraryId = 1L;
        Path accessiblePath = tempDir.resolve("accessible");
        Files.createDirectory(accessiblePath);

        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Test Library");

        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(10L);
        pathEntity.setPath(accessiblePath.toString());
        libraryEntity.setLibraryPaths(List.of(pathEntity));
        libraryEntity.setBookEntities(Collections.emptyList());

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));
        when(libraryFileHelper.getAllLibraryFiles(libraryEntity)).thenReturn(Collections.emptyList());
        when(libraryFileHelper.filterByAllowedFormats(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookAdditionalFileRepository.findByLibraryId(libraryId)).thenReturn(Collections.emptyList());
        when(bookGroupingService.groupForRescan(anyList(), eq(libraryEntity)))
                .thenReturn(new BookGroupingService.GroupingResult(Collections.emptyMap(), Collections.emptyMap()));

        RescanLibraryContext context = RescanLibraryContext.builder().libraryId(libraryId).build();

        libraryProcessingService.rescanLibrary(context);

        verify(bookDeletionService, never()).processDeletedLibraryFiles(any(), any());
    }

    @Test
    void rescanLibrary_shouldRefetchLibraryAfterEntityManagerClear(@TempDir Path tempDir) throws IOException {

        long libraryId = 1L;
        Path accessiblePath = tempDir.resolve("accessible");
        Files.createDirectory(accessiblePath);

        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(10L);
        pathEntity.setPath(accessiblePath.toString());

        BookEntity existingBook = new BookEntity();
        existingBook.setId(1L);
        existingBook.setLibraryPath(pathEntity);
        BookFileEntity existingBookFile = new BookFileEntity();
        existingBookFile.setBook(existingBook);
        existingBook.setBookFiles(new ArrayList<>(List.of(existingBookFile)));
        existingBook.getPrimaryBookFile().setFileSubPath("");
        existingBook.getPrimaryBookFile().setFileName("book (with parens).epub");

        // First call returns entity with one book, second call returns fresh entity with same book
        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Test Library");
        libraryEntity.setLibraryPaths(new ArrayList<>(List.of(pathEntity)));
        libraryEntity.setBookEntities(new ArrayList<>(List.of(existingBook)));

        LibraryEntity freshLibraryEntity = new LibraryEntity();
        freshLibraryEntity.setId(libraryId);
        freshLibraryEntity.setName("Test Library");
        freshLibraryEntity.setLibraryPaths(new ArrayList<>(List.of(pathEntity)));
        freshLibraryEntity.setBookEntities(new ArrayList<>(List.of(existingBook)));

        LibraryFile fileOnDisk = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(pathEntity)
                .fileSubPath("")
                .fileName("book (with parens).epub")
                .build();

        when(libraryRepository.findById(libraryId))
                .thenReturn(Optional.of(libraryEntity))
                .thenReturn(Optional.of(freshLibraryEntity)); // Second call returns fresh entity
        when(bookRepository.findAllByLibraryIdWithFilesAndPathIncludingRemoved(libraryId)).thenReturn(List.of(existingBook));
        when(libraryFileHelper.getAllLibraryFiles(any(LibraryEntity.class))).thenReturn(List.of(fileOnDisk));
        when(libraryFileHelper.filterByAllowedFormats(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookAdditionalFileRepository.findByLibraryId(libraryId)).thenReturn(Collections.emptyList());
        when(bookGroupingService.groupForRescan(anyList(), any(LibraryEntity.class)))
                .thenReturn(new BookGroupingService.GroupingResult(Collections.emptyMap(), Collections.emptyMap()));

        RescanLibraryContext context = RescanLibraryContext.builder().libraryId(libraryId).build();

        libraryProcessingService.rescanLibrary(context);

        verify(libraryRepository, times(2)).findById(libraryId);

        verify(bookGroupingService).groupForRescan(eq(Collections.emptyList()), any(LibraryEntity.class));
    }

    @Test
    void scanLibraryForNewFiles_shouldSkipInaccessiblePathAndScanRemainingAccessiblePaths(@TempDir Path tempDir) throws IOException {
        long libraryId = 9L;
        Path missingPath = tempDir.resolve("offline_mount");
        Path accessiblePath = tempDir.resolve("comics_online");
        Files.createDirectory(accessiblePath);

        LibraryPathEntity offlinePathEntity = new LibraryPathEntity();
        offlinePathEntity.setId(10L);
        offlinePathEntity.setPath(missingPath.toString());

        LibraryPathEntity onlinePathEntity = new LibraryPathEntity();
        onlinePathEntity.setId(11L);
        onlinePathEntity.setPath(accessiblePath.toString());

        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Comics");
        libraryEntity.setLibraryPaths(List.of(offlinePathEntity, onlinePathEntity));

        LibraryFile newComic = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(onlinePathEntity)
                .fileSubPath("")
                .fileName("issue1.cbz")
                .build();

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));
        when(bookRepository.findAllByLibraryIdWithFilesAndPathIncludingRemoved(libraryId)).thenReturn(Collections.emptyList());
        when(bookAdditionalFileRepository.findByLibraryId(libraryId)).thenReturn(Collections.emptyList());
        when(libraryFileHelper.getLibraryFiles(libraryEntity, List.of(onlinePathEntity))).thenReturn(List.of(newComic));
        when(bookGroupingService.groupForInitialScan(anyList(), eq(libraryEntity)))
                .thenReturn(Map.of("issue1.cbz", List.of(newComic)));

        libraryProcessingService.scanLibraryForNewFiles(libraryId);

        verify(failureNotificationService).reportError(contains("Skipping inaccessible path"));
        verify(libraryFileHelper).getLibraryFiles(libraryEntity, List.of(onlinePathEntity));
        verify(fileAsBookProcessor).processLibraryFilesGrouped(eq(Map.of("issue1.cbz", List.of(newComic))), eq(libraryEntity), any());
    }

    @Test
    void scanLibraryForNewFiles_shouldThrowWhenAllPathsInaccessible(@TempDir Path tempDir) {
        long libraryId = 10L;
        Path missingPath = tempDir.resolve("offline_mount");

        LibraryPathEntity offlinePathEntity = new LibraryPathEntity();
        offlinePathEntity.setId(10L);
        offlinePathEntity.setPath(missingPath.toString());

        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Comics");
        libraryEntity.setLibraryPaths(List.of(offlinePathEntity));

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));

        assertThatThrownBy(() -> libraryProcessingService.scanLibraryForNewFiles(libraryId))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("not accessible");
    }

    @Test
    void scanLibraryForNewFiles_shouldSkipPermanentlyRemovedAndImportTempDeletedOrRenamed(@TempDir Path tempDir) throws IOException {
        long libraryId = 11L;
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(10L);
        pathEntity.setPath(tempDir.toString());

        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Comics");
        libraryEntity.setLibraryPaths(List.of(pathEntity));

        // Permanently deleted book (deleted=true, removedFromLibrary=true) is returned by findAllByLibraryIdWithFilesAndPathIncludingRemoved
        BookEntity permanentlyRemovedBook = new BookEntity();
        permanentlyRemovedBook.setId(100L);
        permanentlyRemovedBook.setDeleted(true);
        permanentlyRemovedBook.setRemovedFromLibrary(true);
        permanentlyRemovedBook.setLibraryPath(pathEntity);
        BookFileEntity permanentFile = new BookFileEntity();
        permanentFile.setBook(permanentlyRemovedBook);
        permanentFile.setFileSubPath("");
        permanentFile.setFileName("perm_deleted.cbz");
        permanentlyRemovedBook.setBookFiles(List.of(permanentFile));

        LibraryFile permDeletedOnDisk = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(pathEntity)
                .fileSubPath("")
                .fileName("perm_deleted.cbz")
                .build();

        LibraryFile tempDeletedSameNameOnDisk = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(pathEntity)
                .fileSubPath("")
                .fileName("temp_deleted.cbz")
                .build();

        LibraryFile tempDeletedRenamedOnDisk = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(pathEntity)
                .fileSubPath("")
                .fileName("renamed_issue.cbz")
                .build();

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));
        when(bookRepository.findAllByLibraryIdWithFilesAndPathIncludingRemoved(libraryId))
                .thenReturn(List.of(permanentlyRemovedBook));
        when(bookAdditionalFileRepository.findByLibraryId(libraryId)).thenReturn(Collections.emptyList());
        when(libraryFileHelper.getLibraryFiles(libraryEntity))
                .thenReturn(List.of(permDeletedOnDisk, tempDeletedSameNameOnDisk, tempDeletedRenamedOnDisk));
        when(bookGroupingService.groupForInitialScan(anyList(), eq(libraryEntity))).thenReturn(Collections.emptyMap());

        libraryProcessingService.scanLibraryForNewFiles(libraryId);

        ArgumentCaptor<List<LibraryFile>> captor = libraryFileListCaptor();
        verify(bookGroupingService).groupForInitialScan(captor.capture(), eq(libraryEntity));

        assertThat(captor.getValue())
                .containsExactly(tempDeletedSameNameOnDisk, tempDeletedRenamedOnDisk)
                .doesNotContain(permDeletedOnDisk);
    }

    @Test
    void scanLibraryDirectoriesForNewFiles_shouldMatchNormalizedPathsWithTrailingSlash(@TempDir Path tempDir) throws IOException {
        long libraryId = 12L;
        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(10L);
        pathEntity.setPath(tempDir.toString());

        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Comics");
        libraryEntity.setLibraryPaths(List.of(pathEntity));

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));
        when(bookRepository.findAllByLibraryIdWithFilesAndPathIncludingRemoved(libraryId)).thenReturn(Collections.emptyList());
        when(bookAdditionalFileRepository.findByLibraryId(libraryId)).thenReturn(Collections.emptyList());
        when(libraryFileHelper.getLibraryFiles(libraryEntity, List.of(pathEntity))).thenReturn(Collections.emptyList());
        when(bookGroupingService.groupForInitialScan(anyList(), eq(libraryEntity))).thenReturn(Collections.emptyMap());

        libraryProcessingService.scanLibraryDirectoriesForNewFiles(libraryId, Set.of(tempDir.toString() + "/"));

        verify(libraryFileHelper).getLibraryFiles(libraryEntity, List.of(pathEntity));
    }

        @SuppressWarnings("unchecked")
        private ArgumentCaptor<List<LibraryFile>> libraryFileListCaptor() {
                return (ArgumentCaptor<List<LibraryFile>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        }

        @SuppressWarnings("unchecked")
        private ArgumentCaptor<List<Long>> longListCaptor() {
                return (ArgumentCaptor<List<Long>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        }
}
