package org.fable.service.library;

import org.fable.exception.ApiError;
import org.fable.model.dto.settings.LibraryFile;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.BookFileEntity;
import org.fable.model.entity.LibraryEntity;
import org.fable.model.entity.LibraryPathEntity;
import org.fable.model.enums.TaskType;
import org.fable.model.websocket.TaskProgressPayload;
import org.fable.model.websocket.Topic;
import org.fable.repository.BookAdditionalFileRepository;
import org.fable.repository.BookRepository;
import org.fable.repository.LibraryRepository;
import org.fable.service.FailureNotificationService;
import org.fable.service.NotificationService;
import org.fable.service.book.PhysicalBookService;
import org.fable.service.file.FileFingerprint;
import org.fable.task.TaskStatus;
import org.fable.task.options.RescanLibraryContext;
import org.fable.util.FileUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
@Slf4j
public class LibraryProcessingService {

    private final LibraryRepository libraryRepository;
    private final BookRepository bookRepository;
    private final NotificationService notificationService;
    private final FailureNotificationService failureNotificationService;
    private final BookAdditionalFileRepository bookAdditionalFileRepository;
    private final FileAsBookProcessor fileAsBookProcessor;
    private final BookRestorationService bookRestorationService;
    private final BookDeletionService bookDeletionService;
    private final LibraryFileHelper libraryFileHelper;
    private final BookGroupingService bookGroupingService;
    private final DirectoryTagTaskStarter directoryTagTaskStarter;
    private final PhysicalBookService physicalBookService;
    @PersistenceContext
    private final EntityManager entityManager;

    @Transactional
    public void processLibrary(long libraryId) {
        LibraryEntity libraryEntity = libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        try {
            importPhysicalBookSidecarsSafely(libraryEntity, libraryEntity.getLibraryPaths());
            List<LibraryFile> libraryFiles = libraryFileHelper.getLibraryFiles(libraryEntity);
            int importedCount = importLibraryFiles(libraryEntity, libraryFiles);
            String dirNames = getDirectoryNames(libraryEntity.getLibraryPaths());
            log.info("{} assets imported from {} to {}", importedCount, dirNames, libraryEntity.getName());
        } catch (IOException e) {
            log.error("Failed to process library {}: {}", libraryEntity.getName(), e.getMessage(), e);
            failureNotificationService.reportError("Failed to process library: " + libraryEntity.getName() + " - " + e.getMessage());
            throw new UncheckedIOException("Library processing failed", e);
        }
    }

    @Transactional
    public void processLibraryPaths(long libraryId, Set<String> targetPaths) {
        LibraryEntity libraryEntity = libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        List<LibraryPathEntity> pathEntities = libraryEntity.getLibraryPaths().stream()
                .filter(pathEntity -> matchesTargetPaths(pathEntity, targetPaths))
                .toList();

        if (pathEntities.isEmpty()) {
            log.info("No matching new library paths found for library {}", libraryId);
            return;
        }
        try {
            importPhysicalBookSidecarsSafely(libraryEntity, pathEntities);
            List<LibraryFile> libraryFiles = libraryFileHelper.getLibraryFiles(libraryEntity, pathEntities);
            int importedCount = importLibraryFiles(libraryEntity, libraryFiles);
            String dirNames = getDirectoryNames(pathEntities);
            log.info("{} assets imported from {} to {}", importedCount, dirNames, libraryEntity.getName());
        } catch (IOException e) {
            log.error("Failed to process new library paths for {}: {}", libraryEntity.getName(), e.getMessage(), e);
            failureNotificationService.reportError("Failed to process new library path(s): " + libraryEntity.getName() + " - " + e.getMessage());
            throw new UncheckedIOException("Library path processing failed", e);
        }
    }

    @Transactional
    public void scanLibraryDirectoriesForNewFiles(long libraryId, Set<String> targetPaths) throws IOException {
        LibraryEntity libraryEntity = libraryRepository.findById(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));

        List<LibraryPathEntity> pathEntities = libraryEntity.getLibraryPaths().stream()
                .filter(pathEntity -> matchesTargetPaths(pathEntity, targetPaths))
                .toList();

        if (pathEntities.isEmpty()) {
            log.info("No matching library paths found for explicit scan in library {}", libraryId);
            return;
        }
        List<LibraryPathEntity> accessiblePaths = filterAccessibleLibraryPaths(pathEntities, libraryEntity.getName());
        importPhysicalBookSidecarsSafely(libraryEntity, accessiblePaths);

        List<LibraryFile> libraryFiles = libraryFileHelper.getLibraryFiles(libraryEntity, accessiblePaths);
        int importedCount = importLibraryFiles(libraryEntity, libraryFiles);
        String dirNames = getDirectoryNames(accessiblePaths);
        log.info("{} assets imported from {} to {}", importedCount, dirNames, libraryEntity.getName());
    }

    @Transactional
    public void scanLibraryForNewFiles(long libraryId) throws IOException {
        LibraryEntity libraryEntity = libraryRepository.findById(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        List<LibraryPathEntity> configuredPaths = libraryEntity.getLibraryPaths();
        List<LibraryPathEntity> accessiblePaths = filterAccessibleLibraryPaths(configuredPaths, libraryEntity.getName());
        importPhysicalBookSidecarsSafely(libraryEntity, accessiblePaths);

        List<LibraryFile> libraryFiles = (configuredPaths != null && accessiblePaths.size() < configuredPaths.size())
                ? libraryFileHelper.getLibraryFiles(libraryEntity, accessiblePaths)
                : libraryFileHelper.getLibraryFiles(libraryEntity);
        int importedCount = importLibraryFiles(libraryEntity, libraryFiles);
        String dirNames = getDirectoryNames(accessiblePaths);
        log.info("{} assets imported from {} to {}", importedCount, dirNames, libraryEntity.getName());
    }

    @Transactional
    public void rescanLibrary(RescanLibraryContext context) throws IOException {
        LibraryEntity libraryEntity = libraryRepository.findById(context.getLibraryId()).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(context.getLibraryId()));

        validateLibraryPathsAccessible(libraryEntity.getLibraryPaths());
        importPhysicalBookSidecarsSafely(libraryEntity, libraryEntity.getLibraryPaths());

        List<LibraryFile> allLibraryFiles = libraryFileHelper.getAllLibraryFiles(libraryEntity);
        List<LibraryFile> filteredFiles = libraryFileHelper.filterByAllowedFormats(
                allLibraryFiles, libraryEntity.getAllowedFormats());

        int existingBookCount = libraryEntity.getBookEntities().size();
        if (existingBookCount > 0 && allLibraryFiles.isEmpty()) {
            String paths = libraryEntity.getLibraryPaths().stream()
                    .map(LibraryPathEntity::getPath)
                    .collect(Collectors.joining(", "));
            log.error("Library '{}' has {} existing books but scan found 0 files. Paths may be offline: {}",
                    libraryEntity.getName(), existingBookCount, paths);
            throw ApiError.LIBRARY_PATH_NOT_ACCESSIBLE.createException(paths);
        }

        List<Long> additionalFileIds = detectDeletedAdditionalFiles(allLibraryFiles, libraryEntity);
        if (!additionalFileIds.isEmpty()) {
            log.info("Detected {} removed additional files in library: {}", additionalFileIds.size(), libraryEntity.getName());
            bookDeletionService.deleteRemovedAdditionalFiles(additionalFileIds);
        }
        List<Long> bookIds = detectDeletedBookIds(allLibraryFiles, libraryEntity);
        if (!bookIds.isEmpty()) {
            log.info("Detected {} removed books in library: {}", bookIds.size(), libraryEntity.getName());
            bookDeletionService.processDeletedLibraryFiles(bookIds, allLibraryFiles);
        }
        List<Long> unexpectedFilelessBookIds = detectUnexpectedFilelessBookIds(libraryEntity.getId());
        if (!unexpectedFilelessBookIds.isEmpty()) {
            log.info("Removing {} non-physical fileless books in library: {}", unexpectedFilelessBookIds.size(), libraryEntity.getName());
            bookDeletionService.deleteRemovedBooks(unexpectedFilelessBookIds);
        }
        bookRestorationService.restoreDeletedBooks(allLibraryFiles);
        bookDeletionService.purgeDisallowedFormats(libraryEntity);
        entityManager.clear();
        // Re-fetch library entity to get fresh state after entity manager was cleared
        libraryEntity = libraryRepository.findById(context.getLibraryId())
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(context.getLibraryId()));

        List<LibraryFile> newFiles = detectNewBookPaths(filteredFiles, libraryEntity);

        // Use BookGroupingService to determine what to attach vs create new
        BookGroupingService.GroupingResult groupingResult = bookGroupingService.groupForRescan(newFiles, libraryEntity);

        // Auto-attach files to existing books
        for (Map.Entry<BookEntity, List<LibraryFile>> entry : groupingResult.filesToAttach().entrySet()) {
            for (LibraryFile file : entry.getValue()) {
                autoAttachFile(entry.getKey(), file);
            }
        }

        // Process new book groups
        Map<String, List<LibraryFile>> newBookGroups = groupingResult.newBookGroups();
        int total = newBookGroups.size();
        if (total > 0) {
            String taskId = UUID.randomUUID().toString();
            sendSyncProgress(taskId, 0, "Preparing 0 of " + total, TaskStatus.IN_PROGRESS);
            fileAsBookProcessor.processLibraryFilesGrouped(newBookGroups, libraryEntity, (current, t) -> {
                int pct = t > 0 ? (current * 100) / t : 100;
                sendSyncProgress(taskId, pct, "Importing " + current + " of " + t, TaskStatus.IN_PROGRESS);
            });
            sendSyncProgress(taskId, 100, buildImportCompletionMessage(total, libraryEntity.isTagByDirectory()), TaskStatus.COMPLETED);
        } else {
            fileAsBookProcessor.processLibraryFilesGrouped(newBookGroups, libraryEntity);
        }
        scheduleLibraryDirectoryTaggingIfEnabled(libraryEntity);

        String dirNames = getDirectoryNames(libraryEntity.getLibraryPaths());
        log.info("{} assets imported from {} to {}", total, dirNames, libraryEntity.getName());
    }

    public void processLibraryFiles(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        fileAsBookProcessor.processLibraryFiles(libraryFiles, libraryEntity);
    }

    private int importLibraryFiles(LibraryEntity libraryEntity, List<LibraryFile> libraryFiles) {
        String taskId = UUID.randomUUID().toString();
        List<LibraryFile> newFiles = detectNewBookPaths(libraryFiles, libraryEntity);

        Map<String, List<LibraryFile>> groups = bookGroupingService.groupForInitialScan(newFiles, libraryEntity);
        int total = groups.size();
        if (total > 0) {
            sendSyncProgress(taskId, 0, "Preparing 0 of " + total, TaskStatus.IN_PROGRESS);
            fileAsBookProcessor.processLibraryFilesGrouped(groups, libraryEntity, (current, t) -> {
                int pct = t > 0 ? (current * 100) / t : 100;
                sendSyncProgress(taskId, pct, "Importing " + current + " of " + t, TaskStatus.IN_PROGRESS);
            });
            sendSyncProgress(taskId, 100, buildImportCompletionMessage(total, libraryEntity.isTagByDirectory()), TaskStatus.COMPLETED);
        } else {
            fileAsBookProcessor.processLibraryFilesGrouped(groups, libraryEntity);
        }
        scheduleImportedBookTaggingIfEnabled(libraryEntity, newFiles);
        return total;
    }

    private String getDirectoryNames(List<LibraryPathEntity> paths) {
        if (paths == null || paths.isEmpty()) {
            return "";
        }
        return paths.stream()
                .filter(p -> p != null && p.getPath() != null)
                .map(p -> {
                    Path path = Path.of(p.getPath());
                    return path.getFileName() != null ? path.getFileName().toString() : path.toString();
                })
                .collect(Collectors.joining(", "));
    }

    private void validateLibraryPathsAccessible(List<LibraryPathEntity> pathEntities) {
        for (var pathEntity : pathEntities) {
            Path path = Path.of(pathEntity.getPath());
            if (!Files.exists(path) || !Files.isDirectory(path) || !Files.isReadable(path)) {
                log.error("Library path not accessible: {}", path);
                throw ApiError.LIBRARY_PATH_NOT_ACCESSIBLE.createException(path.toString());
            }
        }
    }

    private List<LibraryPathEntity> filterAccessibleLibraryPaths(List<LibraryPathEntity> pathEntities, String libraryName) {
        if (pathEntities == null || pathEntities.isEmpty()) {
            return List.of();
        }
        List<LibraryPathEntity> accessiblePaths = pathEntities.stream()
                .filter(pathEntity -> {
                    if (pathEntity == null || pathEntity.getPath() == null) {
                        return false;
                    }
                    Path path = Path.of(pathEntity.getPath());
                    if (!Files.exists(path) || !Files.isDirectory(path) || !Files.isReadable(path)) {
                        log.warn("Library path not accessible in library '{}', skipping: {}", libraryName, path);
                        failureNotificationService.reportError(
                                "Skipping inaccessible path in library '" + libraryName + "': " + path);
                        return false;
                    }
                    return true;
                })
                .toList();

        if (accessiblePaths.isEmpty()) {
            String paths = pathEntities.stream()
                    .filter(Objects::nonNull)
                    .map(LibraryPathEntity::getPath)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(", "));
            log.error("No library paths are accessible for library '{}': {}", libraryName, paths);
            throw ApiError.LIBRARY_PATH_NOT_ACCESSIBLE.createException(paths);
        }
        return accessiblePaths;
    }

    private void importPhysicalBookSidecarsSafely(LibraryEntity libraryEntity, List<LibraryPathEntity> pathEntities) {
        try {
            physicalBookService.importPhysicalBooksFromSidecars(libraryEntity, pathEntities);
        } catch (Exception e) {
            log.warn("Failed to import physical book sidecars for library '{}': {}", libraryEntity.getName(), e.getMessage(), e);
        }
    }

    private boolean matchesTargetPaths(LibraryPathEntity pathEntity, Set<String> targetPaths) {
        if (pathEntity == null || pathEntity.getPath() == null || targetPaths == null || targetPaths.isEmpty()) {
            return false;
        }
        if (targetPaths.contains(pathEntity.getPath())) {
            return true;
        }
        try {
            Path entityPath = Path.of(pathEntity.getPath()).normalize();
            for (String target : targetPaths) {
                if (target != null && Path.of(target).normalize().equals(entityPath)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to normalize path during directory matching: {}", e.getMessage());
        }
        return false;
    }

    protected static List<Long> detectDeletedBookIds(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        Set<Path> currentFullPaths = libraryFiles.stream()
                .map(LibraryFile::getFullPath)
                .collect(Collectors.toSet());

        return libraryEntity.getBookEntities().stream()
                .filter(book -> (book.getDeleted() == null || !book.getDeleted()))
                .filter(book -> {
                    // Don't mark fileless books as deleted - they're intentionally without files
                    if (!book.hasFiles()) {
                        return false;
                    }
                    return !currentFullPaths.contains(book.getFullFilePath());
                })
                .map(BookEntity::getId)
                .collect(Collectors.toList());
    }

    protected List<LibraryFile> detectNewBookPaths(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        Set<Path> existingPaths = bookRepository.findAllByLibraryIdWithFilesAndPathIncludingRemoved(libraryEntity.getId()).stream()
                .filter(book -> book.getBookFiles() != null && !book.getBookFiles().isEmpty())
                .flatMap(book -> book.getBookFiles().stream())
                .map(file -> {
                    try { return file.getFullFilePath(); } catch (Exception e) { return null; }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Additional files (non-book files like covers) also need dedup
        bookAdditionalFileRepository.findByLibraryId(libraryEntity.getId()).stream()
                .map(file -> {
                    try { return file.getFullFilePath(); } catch (Exception e) { return null; }
                })
                .filter(Objects::nonNull)
                .forEach(existingPaths::add);

        return libraryFiles.stream()
                .filter(file -> !existingPaths.contains(file.getFullPath()))
                .collect(Collectors.toList());
    }

    private List<Long> detectUnexpectedFilelessBookIds(Long libraryId) {
        return bookRepository.findFilelessBooksByLibraryId(libraryId).stream()
                .filter(book -> !Boolean.TRUE.equals(book.getIsPhysical()))
                .map(BookEntity::getId)
                .toList();
    }

    private void autoAttachFile(BookEntity book, LibraryFile file) {
        // Check if file already exists to prevent duplicates during concurrent rescans
        var existing = bookAdditionalFileRepository.findByLibraryPath_IdAndFileSubPathAndFileName(
                file.getLibraryPathEntity().getId(), file.getFileSubPath(), file.getFileName());
        if (existing.isPresent()) {
            log.debug("Additional file already exists, skipping: {}", file.getFileName());
            return;
        }

        // Set libraryPath if not set (fileless books like physical books don't have one)
        if (book.getLibraryPath() == null) {
            book.setLibraryPath(file.getLibraryPathEntity());
        } else if (!book.getLibraryPath().getId().equals(file.getLibraryPathEntity().getId())) {
            // Book already has a different libraryPath - cannot attach files from different paths
            log.warn("Cannot attach file '{}' to book id={}: file is in libraryPath {} but book is in libraryPath {}",
                    file.getFileName(), book.getId(), file.getLibraryPathEntity().getId(), book.getLibraryPath().getId());
            return;
        }

        String hash = file.isFolderBased()
                ? FileFingerprint.generateFolderHash(file.getFullPath())
                : FileFingerprint.generateHash(file.getFullPath());
        Long fileSizeKb = file.isFolderBased()
                ? FileUtils.getFolderSizeInKb(file.getFullPath())
                : FileUtils.getFileSizeInKb(file.getFullPath());
        BookFileEntity additionalFile = BookFileEntity.builder()
                .book(book)
                .fileName(file.getFileName())
                .fileSubPath(file.getFileSubPath())
                .isBookFormat(true)
                .bookType(file.getBookFileType())
                .folderBased(file.isFolderBased())
                .fileSizeKb(fileSizeKb)
                .initialHash(hash)
                .currentHash(hash)
                .addedOn(Instant.now())
                .build();

        try {
            bookAdditionalFileRepository.save(additionalFile);
            String primaryFileName = book.hasFiles() ? book.getPrimaryBookFile().getFileName() : "book#" + book.getId();
            log.info("Auto-attached new format {} to existing book: {}", file.getFileName(), primaryFileName);
            fileAsBookProcessor.generateCoverFromAdditionalFile(book, file);
        } catch (Exception e) {
            log.error("Error auto-attaching file {}: {}", file.getFileName(), e.getMessage());
        }
    }

    private String generateUniqueKey(BookEntity book) {
        BookFileEntity primaryFile = book.getPrimaryBookFile();
        if (primaryFile == null) {
            // Fileless book - use a unique key that won't match any file
            return "fileless:" + book.getId();
        }
        return generateKey(book.getLibraryPath().getId(), primaryFile.getFileSubPath(), primaryFile.getFileName());
    }

    private String generateUniqueKey(BookFileEntity file) {
        return generateKey(file.getBook().getLibraryPath().getId(), file.getFileSubPath(), file.getFileName());
    }

    private String generateUniqueKey(LibraryFile file) {
        return generateKey(file.getLibraryPathEntity().getId(), file.getFileSubPath(), file.getFileName());
    }

    private String generateKey(Long libraryPathId, String subPath, String fileName) {
        String safeSubPath = (subPath == null) ? "" : subPath;
        return libraryPathId + ":" + safeSubPath + ":" + fileName;
    }

    protected List<Long> detectDeletedAdditionalFiles(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        Set<String> currentFileKeys = libraryFiles.stream()
                .map(this::generateUniqueKey)
                .collect(Collectors.toSet());

        List<BookFileEntity> allAdditionalFiles = bookAdditionalFileRepository.findByLibraryId(libraryEntity.getId());

        return allAdditionalFiles.stream()
                .filter(BookFileEntity::isBookFormat)
                .filter(additionalFile -> !currentFileKeys.contains(generateUniqueKey(additionalFile)))
                .map(BookFileEntity::getId)
                .collect(Collectors.toList());
    }

    private void sendSyncProgress(String taskId, int progress, String message, TaskStatus status) {
        try {
            TaskProgressPayload payload = TaskProgressPayload.builder()
                    .taskId(taskId)
                    .taskType(TaskType.SYNC_LIBRARY_FILES)
                    .message(message)
                    .progress(progress)
                    .taskStatus(status)
                    .build();
            if (status == TaskStatus.COMPLETED && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                notificationService.sendMessage(Topic.TASK_PROGRESS, payload);
                            }
                        }
                );
            } else {
                notificationService.sendMessage(Topic.TASK_PROGRESS, payload);
            }
        } catch (Exception e) {
            log.error("Failed to send sync progress notification: {}", e.getMessage(), e);
        }
    }
    private void scheduleImportedBookTaggingIfEnabled(LibraryEntity libraryEntity, List<LibraryFile> importedFiles) {
        if (!libraryEntity.isTagByDirectory()) {
            return;
        }

        Set<Long> importedBookIds = findImportedBookIds(libraryEntity, importedFiles);
        if (importedBookIds.isEmpty()) {
            return;
        }

        directoryTagTaskStarter.scheduleBooks(libraryEntity.getId(), importedBookIds);
        log.info("Queued background directory tagging for {} imported books in library {}", importedBookIds.size(), libraryEntity.getId());
    }

    private void scheduleLibraryDirectoryTaggingIfEnabled(LibraryEntity libraryEntity) {
        if (!libraryEntity.isTagByDirectory()) {
            return;
        }

        directoryTagTaskStarter.scheduleLibrary(libraryEntity.getId());
        log.info("Queued background directory tagging for library {}", libraryEntity.getId());
    }

    private Set<Long> findImportedBookIds(LibraryEntity libraryEntity, List<LibraryFile> importedFiles) {
        if (importedFiles == null || importedFiles.isEmpty()) {
            return Set.of();
        }

        Set<String> importedKeys = importedFiles.stream()
                .map(this::generateUniqueKey)
                .collect(Collectors.toSet());

        return bookRepository.findAllByLibraryIdWithFilesAndPathIncludingRemoved(libraryEntity.getId()).stream()
                .filter(book -> book.getPrimaryBookFile() != null)
                .filter(book -> importedKeys.contains(generateUniqueKey(book)))
                .map(BookEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String buildImportCompletionMessage(int importedGroups, boolean tagByDirectory) {
        if (!tagByDirectory) {
            return "Imported " + importedGroups + " books";
        }
        return "Imported " + importedGroups + " books. Directory tagging continues in the background.";
    }
}
