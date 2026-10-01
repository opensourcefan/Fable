package org.fable.service.metadata.sidecar;

import org.fable.model.entity.BookEntity;
import org.fable.model.entity.LibraryEntity;
import org.fable.model.entity.AuditLogEntity;
import org.fable.model.enums.AuditAction;
import org.fable.repository.AuditLogRepository;
import org.fable.repository.BookRepository;
import org.fable.repository.LibraryRepository;
import org.fable.service.audit.AuditService;
import org.fable.service.library.BookDeletionService;
import org.fable.service.metadata.BookMetadataUpdater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SidecarServiceTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private LibraryRepository libraryRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private SidecarMetadataReader sidecarReader;

    @Mock
    private SidecarMetadataWriter sidecarWriter;

    @Mock
    private SidecarMetadataMapper sidecarMapper;

    @Mock
    private BookMetadataUpdater bookMetadataUpdater;

    @Mock
    private AuditService auditService;

    @Mock
    private BookDeletionService bookDeletionService;

    @InjectMocks
    private SidecarService sidecarService;

    @Test
    void bulkExport_countsOnlySuccessfulWrites() {
        LibraryEntity library = new LibraryEntity();
        library.setId(5L);
        library.setName("Library");

        BookEntity firstBook = new BookEntity();
        firstBook.setId(10L);
        BookEntity secondBook = new BookEntity();
        secondBook.setId(11L);

        when(libraryRepository.findById(5L)).thenReturn(Optional.of(library));
        when(bookRepository.findAllForMetadataFlushByLibraryId(5L)).thenReturn(List.of(firstBook, secondBook));
        when(sidecarWriter.writeSidecarMetadata(firstBook, false)).thenReturn(true);
        when(sidecarWriter.writeSidecarMetadata(secondBook, false)).thenReturn(false);

        int exported = sidecarService.bulkExport(5L);

        assertEquals(1, exported);
        verify(sidecarWriter).writeSidecarMetadata(firstBook, false);
        verify(sidecarWriter).writeSidecarMetadata(secondBook, false);
    }

    @Test
    void backupLibrarySidecars_forcesWritesRegardlessOfAutomaticSettingState() {
        LibraryEntity library = new LibraryEntity();
        library.setId(7L);
        library.setName("Backup Library");

        BookEntity firstBook = new BookEntity();
        firstBook.setId(20L);
        BookEntity secondBook = new BookEntity();
        secondBook.setId(21L);

        when(libraryRepository.findById(7L)).thenReturn(Optional.of(library));
        when(bookRepository.findAllForMetadataFlushByLibraryId(7L)).thenReturn(List.of(firstBook, secondBook));
        when(sidecarWriter.writeSidecarMetadataWithResult(firstBook, true)).thenReturn(SidecarMetadataWriter.SidecarWriteResult.succeeded());
        when(sidecarWriter.writeSidecarMetadataWithResult(secondBook, true)).thenReturn(SidecarMetadataWriter.SidecarWriteResult.succeeded());

        SidecarService.SidecarBatchResult result = sidecarService.backupLibrarySidecars(7L);

        assertEquals(2, result.exported());
        assertEquals(2, result.attempted());
        assertEquals(0, result.failed());
        verify(sidecarWriter).writeSidecarMetadataWithResult(firstBook, true);
        verify(sidecarWriter).writeSidecarMetadataWithResult(secondBook, true);
        verify(auditService).log(
            AuditAction.SIDECAR_BACKUP_COMPLETED,
            "Library",
            7L,
            "Sidecar backup for library 'Backup Library' (attempted=2, exported=2, failed=0)"
        );
    }

    @Test
    void backupLibrarySidecars_reportsFailedWrites() {
        LibraryEntity library = new LibraryEntity();
        library.setId(9L);
        library.setName("Failure Library");

        BookEntity firstBook = new BookEntity();
        firstBook.setId(40L);
        BookEntity secondBook = new BookEntity();
        secondBook.setId(41L);

        when(libraryRepository.findById(9L)).thenReturn(Optional.of(library));
        when(bookRepository.findAllForMetadataFlushByLibraryId(9L)).thenReturn(List.of(firstBook, secondBook));
        when(sidecarWriter.writeSidecarMetadataWithResult(firstBook, true)).thenReturn(SidecarMetadataWriter.SidecarWriteResult.failure("Permission denied while writing /library/book.metadata.json"));
        when(sidecarWriter.writeSidecarMetadataWithResult(secondBook, true)).thenReturn(SidecarMetadataWriter.SidecarWriteResult.succeeded());

        SidecarService.SidecarBatchResult result = sidecarService.backupLibrarySidecars(9L);

        assertEquals(2, result.attempted());
        assertEquals(1, result.exported());
        assertEquals(1, result.failed());
        assertEquals("Permission denied while writing /library/book.metadata.json", result.firstError());
        verify(auditService).log(
            AuditAction.SIDECAR_BACKUP_PARTIAL,
            "Library",
            9L,
            "Sidecar backup for library 'Failure Library' (attempted=2, exported=1, failed=1). First error: Permission denied while writing /library/book.metadata.json"
        );
    }

    @Test
    void backupLibrarySidecars_prunesMissingBooksAndReportsPrunedCount() {
        LibraryEntity library = new LibraryEntity();
        library.setId(10L);
        library.setName("Prune Library");

        BookEntity activeBook = new BookEntity();
        activeBook.setId(50L);
        BookEntity missingBook = new BookEntity();
        missingBook.setId(51L);

        when(libraryRepository.findById(10L)).thenReturn(Optional.of(library));
        when(bookRepository.findAllForMetadataFlushByLibraryId(10L)).thenReturn(List.of(activeBook, missingBook));
        when(sidecarWriter.writeSidecarMetadataWithResult(activeBook, true)).thenReturn(SidecarMetadataWriter.SidecarWriteResult.succeeded());
        when(sidecarWriter.writeSidecarMetadataWithResult(missingBook, true)).thenReturn(SidecarMetadataWriter.SidecarWriteResult.missingSource("The source book file does not exist on disk."));

        SidecarService.SidecarBatchResult result = sidecarService.backupLibrarySidecars(10L);

        assertEquals(2, result.attempted());
        assertEquals(1, result.exported());
        assertEquals(1, result.pruned());
        assertEquals(0, result.failed());
        assertNull(result.firstError());
        verify(bookDeletionService).deleteRemovedBooks(List.of(51L));
        verify(auditService).log(
            AuditAction.SIDECAR_BACKUP_COMPLETED,
            "Library",
            10L,
            "Sidecar backup for library 'Prune Library' (attempted=2, exported=1, pruned=1, failed=0)"
        );
    }

    @Test
    void getBackupHistory_mapsRecentAuditEntries() {
        LibraryEntity library = new LibraryEntity();
        library.setId(12L);
        library.setName("History Library");

        AuditLogEntity partialRun = AuditLogEntity.builder()
            .action(AuditAction.SIDECAR_BACKUP_PARTIAL)
            .entityType("Library")
            .entityId(12L)
            .description("Sidecar backup for library 'History Library' (attempted=5, exported=4, failed=1). First error: Disk full")
            .username("admin")
            .createdAt(LocalDateTime.of(2026, 4, 15, 12, 30))
            .build();

        AuditLogEntity completedRun = AuditLogEntity.builder()
            .action(AuditAction.SIDECAR_BACKUP_COMPLETED)
            .entityType("Library")
            .entityId(12L)
            .description("Sidecar backup for library 'History Library' (attempted=3, exported=3, failed=0)")
            .username("operator")
            .createdAt(LocalDateTime.of(2026, 4, 14, 9, 0))
            .build();

        when(libraryRepository.findById(12L)).thenReturn(Optional.of(library));
        when(auditLogRepository.findByEntityTypeAndEntityIdAndActionInOrderByCreatedAtDesc(any(), any(), any(), any()))
            .thenReturn(List.of(partialRun, completedRun));

        var history = sidecarService.getBackupHistory(12L, 10);

        assertEquals(2, history.size());
        assertEquals("PARTIAL", history.get(0).status());
        assertEquals(5, history.get(0).attempted());
        assertEquals(4, history.get(0).exported());
        assertEquals(1, history.get(0).failed());
        assertEquals("Disk full", history.get(0).firstError());
        assertEquals("admin", history.get(0).username());
        assertEquals(LocalDateTime.of(2026, 4, 15, 12, 30), history.get(0).createdAt());
        assertEquals("COMPLETED", history.get(1).status());
        assertEquals(3, history.get(1).attempted());
        assertEquals(3, history.get(1).exported());
        assertEquals(0, history.get(1).failed());
        assertNull(history.get(1).firstError());
    }

    @Test
    void bulkImport_usesBooksWithResolvedLibraryPaths() {
        LibraryEntity library = new LibraryEntity();
        library.setId(8L);
        library.setName("Import Library");

        BookEntity book = new BookEntity();
        book.setId(30L);

        when(libraryRepository.findById(8L)).thenReturn(Optional.of(library));
        when(bookRepository.findAllForMetadataFlushByLibraryId(8L)).thenReturn(List.of(book));
        when(sidecarReader.readSidecarMetadata(book)).thenReturn(Optional.empty());

        int imported = sidecarService.bulkImport(8L);

        assertEquals(0, imported);
        verify(bookRepository).findAllForMetadataFlushByLibraryId(8L);
    }
}