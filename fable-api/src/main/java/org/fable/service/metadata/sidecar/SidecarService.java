package org.fable.service.metadata.sidecar;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fable.exception.ApiError;
import org.fable.model.MetadataUpdateContext;
import org.fable.model.MetadataUpdateWrapper;
import org.fable.model.dto.BookMetadata;
import org.fable.model.dto.response.SidecarBackupHistoryEntry;
import org.fable.model.dto.sidecar.SidecarMetadata;
import org.fable.model.entity.AuditLogEntity;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.LibraryEntity;
import org.fable.model.enums.AuditAction;
import org.fable.model.enums.MetadataReplaceMode;
import org.fable.model.enums.SidecarSyncStatus;
import org.fable.repository.AuditLogRepository;
import org.fable.repository.BookRepository;
import org.fable.repository.LibraryRepository;
import org.fable.service.library.BookDeletionService;
import org.fable.service.audit.AuditService;
import org.fable.service.metadata.BookMetadataUpdater;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@AllArgsConstructor
public class SidecarService {
    private static final String LIBRARY_ENTITY_TYPE = "Library";
    private static final int MAX_HISTORY_LIMIT = 10;
    private static final Pattern BACKUP_COUNTS_PATTERN = Pattern.compile("attempted=(\\d+), exported=(\\d+)(?:, pruned=(\\d+))?, failed=(\\d+)\\)");
    private static final Pattern FIRST_ERROR_PATTERN = Pattern.compile("\\. First error: (.*)$");
    private static final List<AuditAction> BACKUP_HISTORY_ACTIONS = List.of(
            AuditAction.SIDECAR_BACKUP_COMPLETED,
            AuditAction.SIDECAR_BACKUP_PARTIAL,
            AuditAction.SIDECAR_BACKUP_FAILED
    );

    private final BookRepository bookRepository;
    private final LibraryRepository libraryRepository;
    private final AuditLogRepository auditLogRepository;
    private final SidecarMetadataReader sidecarReader;
    private final SidecarMetadataWriter sidecarWriter;
    private final SidecarMetadataMapper sidecarMapper;
    private final BookMetadataUpdater bookMetadataUpdater;
    private final AuditService auditService;
    private final BookDeletionService bookDeletionService;

    public Optional<SidecarMetadata> getSidecarContent(Long bookId) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        return sidecarReader.readSidecarMetadata(book);
    }

    public SidecarSyncStatus getSyncStatus(Long bookId) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        return sidecarReader.getSyncStatus(book);
    }

    @Transactional
    public void exportToSidecar(Long bookId) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        sidecarWriter.writeSidecarMetadata(book);
    }

    @Transactional
    public void importFromSidecar(Long bookId) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        Optional<SidecarMetadata> sidecarOpt = sidecarReader.readSidecarMetadata(book);
        if (sidecarOpt.isEmpty()) {
            throw ApiError.FILE_NOT_FOUND.createException("No sidecar file found for book");
        }

        SidecarMetadata sidecar = sidecarOpt.get();
        BookMetadata bookMetadata = sidecarMapper.toBookMetadata(sidecar);

        if (bookMetadata != null) {
            MetadataUpdateWrapper wrapper = MetadataUpdateWrapper.builder()
                    .metadata(bookMetadata)
                    .build();

            MetadataUpdateContext context = MetadataUpdateContext.builder()
                    .bookEntity(book)
                    .metadataUpdateWrapper(wrapper)
                    .updateThumbnail(false)
                    .replaceMode(MetadataReplaceMode.REPLACE_WHEN_PROVIDED)
                    .build();

            bookMetadataUpdater.setBookMetadata(context);
        }

        byte[] coverBytes = sidecarReader.readSidecarCover(book);
        if (coverBytes != null) {
            log.info("Sidecar cover found for book ID {} - cover import is a separate operation", bookId);
        }
    }

    @Transactional(readOnly = true)
    public List<SidecarBackupHistoryEntry> getBackupHistory(Long libraryId, int limit) {
        libraryRepository.findById(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));

        int safeLimit = Math.max(1, Math.min(limit, MAX_HISTORY_LIMIT));

        return auditLogRepository.findByEntityTypeAndEntityIdAndActionInOrderByCreatedAtDesc(
                        LIBRARY_ENTITY_TYPE,
                        libraryId,
                        BACKUP_HISTORY_ACTIONS,
                        PageRequest.of(0, safeLimit)
                ).stream()
                .map(this::toBackupHistoryEntry)
                .toList();
    }

    @Transactional
    public int bulkExport(Long libraryId) {
        LibraryEntity library = libraryRepository.findById(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));

        List<BookEntity> books = bookRepository.findAllForMetadataFlushByLibraryId(libraryId);
        int exported = 0;

        for (BookEntity book : books) {
            try {
                if (sidecarWriter.writeSidecarMetadata(book, false)) {
                    exported++;
                }
            } catch (Exception e) {
                log.warn("Failed to export sidecar for book ID {}: {}", book.getId(), e.getMessage());
            }
        }

        log.info("Bulk exported {} sidecar files for library {}", exported, library.getName());
        return exported;
    }

    @Transactional
    public SidecarBatchResult backupLibrarySidecars(Long libraryId) {
        LibraryEntity library = libraryRepository.findById(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));

        List<BookEntity> books = bookRepository.findAllForMetadataFlushByLibraryId(libraryId);
        int exported = 0;
        int failed = 0;
        List<Long> orphanedBookIds = new ArrayList<>();
        String firstError = null;

        for (BookEntity book : books) {
            try {
                SidecarMetadataWriter.SidecarWriteResult result = sidecarWriter.writeSidecarMetadataWithResult(book, true);
                if (result.completed()) {
                    exported++;
                } else if (result.missingSource()) {
                    orphanedBookIds.add(book.getId());
                    log.info("Identified orphaned book ID {} with missing file on disk: {}",
                            book.getId(), book.getFullFilePath());
                } else {
                    failed++;
                    if (firstError == null) {
                        firstError = result.errorMessage();
                    }
                }
            } catch (Exception e) {
                failed++;
                if (firstError == null) {
                    firstError = e.getMessage();
                }
                log.warn("Failed to back up sidecar for book ID {}: {}", book.getId(), e.getMessage());
            }
        }

        int pruned = 0;
        if (!orphanedBookIds.isEmpty()) {
            log.info("Pruning {} orphaned book record(s) during sidecar sync for library '{}'",
                    orphanedBookIds.size(), library.getName());
            bookDeletionService.deleteRemovedBooks(orphanedBookIds);
            pruned = orphanedBookIds.size();
        }

        log.info("Backed up {} sidecar files for library {} (attempted={}, exported={}, pruned={}, failed={})",
                exported, library.getName(), books.size(), exported, pruned, failed);
        auditService.log(resolveBackupAuditAction(exported, failed), "Library", libraryId,
                buildBackupAuditDescription(library.getName(), books.size(), exported, pruned, failed, firstError));
        return new SidecarBatchResult(books.size(), exported, pruned, failed, firstError);
    }

    private AuditAction resolveBackupAuditAction(int exported, int failed) {
        if (failed == 0) {
            return AuditAction.SIDECAR_BACKUP_COMPLETED;
        }

        if (exported == 0) {
            return AuditAction.SIDECAR_BACKUP_FAILED;
        }

        return AuditAction.SIDECAR_BACKUP_PARTIAL;
    }

    private String buildBackupAuditDescription(String libraryName, int attempted, int exported, int pruned, int failed, String firstError) {
        String description = "Sidecar backup for library '" + libraryName + "' (attempted=" + attempted + ", exported=" + exported + (pruned > 0 ? ", pruned=" + pruned : "") + ", failed=" + failed + ")";
        if (firstError == null || firstError.isBlank()) {
            return description;
        }

        return description + ". First error: " + firstError;
    }

    @Transactional
    public int bulkImport(Long libraryId) {
        LibraryEntity library = libraryRepository.findById(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));

        List<BookEntity> books = bookRepository.findAllForMetadataFlushByLibraryId(libraryId);
        int imported = 0;

        for (BookEntity book : books) {
            try {
                Optional<SidecarMetadata> sidecarOpt = sidecarReader.readSidecarMetadata(book);
                if (sidecarOpt.isEmpty()) {
                    continue;
                }

                SidecarMetadata sidecar = sidecarOpt.get();
                BookMetadata bookMetadata = sidecarMapper.toBookMetadata(sidecar);

                if (bookMetadata != null) {
                    MetadataUpdateWrapper wrapper = MetadataUpdateWrapper.builder()
                            .metadata(bookMetadata)
                            .build();

                    MetadataUpdateContext context = MetadataUpdateContext.builder()
                            .bookEntity(book)
                            .metadataUpdateWrapper(wrapper)
                            .updateThumbnail(false)
                            .replaceMode(MetadataReplaceMode.REPLACE_WHEN_PROVIDED)
                            .build();

                    bookMetadataUpdater.setBookMetadata(context);
                    imported++;
                }
            } catch (Exception e) {
                log.warn("Failed to import sidecar for book ID {}: {}", book.getId(), e.getMessage());
            }
        }

        log.info("Bulk imported {} sidecar files for library {}", imported, library.getName());
        return imported;
    }

    private SidecarBackupHistoryEntry toBackupHistoryEntry(AuditLogEntity auditLog) {
        BackupCounts counts = parseBackupCounts(auditLog.getDescription());

        return new SidecarBackupHistoryEntry(
                mapAuditActionToStatus(auditLog.getAction()),
                counts.attempted(),
                counts.exported(),
                counts.pruned(),
                counts.failed(),
                parseFirstError(auditLog.getDescription()),
                auditLog.getDescription(),
                auditLog.getUsername(),
                auditLog.getCreatedAt()
        );
    }

    private BackupCounts parseBackupCounts(String description) {
        if (description == null || description.isBlank()) {
            return new BackupCounts(0, 0, 0, 0);
        }

        Matcher matcher = BACKUP_COUNTS_PATTERN.matcher(description);
        if (!matcher.find()) {
            return new BackupCounts(0, 0, 0, 0);
        }

        int attempted = Integer.parseInt(matcher.group(1));
        int exported = Integer.parseInt(matcher.group(2));
        int pruned = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
        int failed = Integer.parseInt(matcher.group(4));

        return new BackupCounts(attempted, exported, pruned, failed);
    }

    private String parseFirstError(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }

        Matcher matcher = FIRST_ERROR_PATTERN.matcher(description);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String mapAuditActionToStatus(AuditAction action) {
        return switch (action) {
            case SIDECAR_BACKUP_FAILED -> "FAILED";
            case SIDECAR_BACKUP_PARTIAL -> "PARTIAL";
            default -> "COMPLETED";
        };
    }

    public record SidecarBatchResult(int attempted, int exported, int pruned, int failed, String firstError) {
    }

    private record BackupCounts(int attempted, int exported, int pruned, int failed) {
    }
}
