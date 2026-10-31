package org.fable.service.library;

import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fable.config.security.service.AuthenticationService;
import org.fable.exception.ApiError;
import org.fable.mapper.BookMapper;
import org.fable.mapper.LibraryMapper;
import org.fable.model.dto.Book;
import org.fable.model.dto.FableUser;
import org.fable.model.dto.Library;
import org.fable.model.dto.LibraryPath;
import org.fable.model.dto.request.CreateLibraryRequest;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.FableUserEntity;
import org.fable.model.entity.LibraryEntity;
import org.fable.model.entity.LibraryPathEntity;
import org.fable.model.enums.AuditAction;
import org.fable.model.enums.BookFileType;
import org.fable.model.enums.DirectoryTagDepth;
import org.fable.model.websocket.Topic;
import org.fable.repository.BookRepository;
import org.fable.repository.LibraryPathRepository;
import org.fable.repository.LibraryRepository;
import org.fable.repository.UserRepository;
import org.fable.service.FailureNotificationService;
import org.fable.service.NotificationService;
import org.fable.service.audit.AuditService;
import org.fable.service.file.PathService;
import org.fable.service.monitoring.LibraryWatchService;
import org.fable.task.options.RescanLibraryContext;
import org.fable.util.FileService;
import org.fable.util.SecurityContextVirtualThread;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.event.EventListener;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
@DependsOnDatabaseInitialization
public class LibraryService {

    private static final Set<Long> scanningLibraries = ConcurrentHashMap.newKeySet();

    /**
     * Checks whether a library is currently being scanned.
     * Can be used by other components (e.g., file watcher) to avoid processing
     * files while a full scan is in progress.
     */
    public static boolean isLibraryScanning(long libraryId) {
        return scanningLibraries.contains(libraryId);
    }

    private final LibraryRepository libraryRepository;
    private final LibraryPathRepository libraryPathRepository;
    private final BookRepository bookRepository;
    private final LibraryProcessingService libraryProcessingService;
    private final BookMapper bookMapper;
    private final LibraryMapper libraryMapper;
    private final NotificationService notificationService;
    private final FileService fileService;
    private final LibraryWatchService libraryWatchService;
    private final AuthenticationService authenticationService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final PathService pathService;
    private final FailureNotificationService failureNotificationService;
    private final LibraryHealthService libraryHealthService;

    @Transactional
    @EventListener(ApplicationReadyEvent.class)
    public void initializeMonitoring() {
        List<Library> libraries = libraryRepository.findAll().stream().map(this::toLibraryWithPathHealth).collect(Collectors.toList());
        libraryWatchService.registerLibraries(libraries);
        log.info("Monitoring initialized with {} libraries", libraries.size());
    }

    @Transactional
    public Library updateLibrary(CreateLibraryRequest request, Long libraryId) {
        LibraryEntity library = libraryRepository.findById(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));

        assertRequestedPathsAllowed(request);

        library.setName(request.getName());
        library.setIcon(request.getIcon());
        library.setIconType(request.getIconType());
        library.setWatch(request.isWatch());
        library.setFormatPriority(request.getFormatPriority());
        library.setAllowedFormats(request.getAllowedFormats());
        library.setTagByDirectory(request.isTagByDirectory());
        library.setDirectoryTagDepth(request.getDirectoryTagDepth() != null ? request.getDirectoryTagDepth() : DirectoryTagDepth.LAST_ONLY);
        if (request.getMetadataSource() != null) {
            library.setMetadataSource(request.getMetadataSource());
        }
        if (request.getOrganizationMode() != null) {
            library.setOrganizationMode(request.getOrganizationMode());
        }

        Set<String> currentPaths = library.getLibraryPaths().stream()
                .map(LibraryPathEntity::getPath)
                .collect(Collectors.toSet());
        Set<String> updatedPaths = request.getPaths().stream()
                .map(LibraryPath::getPath)
                .collect(Collectors.toSet());

        Set<String> deletedPaths = currentPaths.stream()
                .filter(path -> !updatedPaths.contains(path))
                .collect(Collectors.toSet());
        Set<String> newPaths = updatedPaths.stream()
                .filter(path -> !currentPaths.contains(path))
                .collect(Collectors.toSet());

        if (!deletedPaths.isEmpty()) {
            Set<LibraryPathEntity> pathsToRemove = library.getLibraryPaths().stream()
                    .filter(pathEntity -> deletedPaths.contains(pathEntity.getPath()))
                    .collect(Collectors.toSet());

            library.getLibraryPaths().removeAll(pathsToRemove);
            List<Long> books = bookRepository.findAllBookIdsByLibraryPathIdIn(
                    pathsToRemove.stream().map(LibraryPathEntity::getId).collect(Collectors.toSet()));

            if (!books.isEmpty()) {
                notificationService.sendMessage(Topic.BOOKS_REMOVE, books);
            }

            libraryPathRepository.deleteAll(pathsToRemove);
        }

        if (!newPaths.isEmpty()) {
            Set<LibraryPathEntity> newPathEntities = newPaths.stream()
                    .map(path -> LibraryPathEntity.builder().path(path).library(library).build())
                    .collect(Collectors.toSet());

            library.getLibraryPaths().addAll(newPathEntities);
            libraryPathRepository.saveAll(library.getLibraryPaths());
        }

        LibraryEntity savedLibrary = libraryRepository.save(library);
        Library mappedLibrary = toLibraryWithPathHealth(savedLibrary);

        if (request.isWatch()) {
            libraryWatchService.registerLibraries(List.of(mappedLibrary));
        } else {
            libraryWatchService.unregisterLibrary(libraryId);
        }

        refreshLibraryHealthSafely();
        auditService.log(AuditAction.LIBRARY_UPDATED, "Library", libraryId, "Updated library: " + library.getName());
        return mappedLibrary;
    }

    public Library createLibrary(CreateLibraryRequest request) {
        FableUser fableUser = authenticationService.getAuthenticatedUser();
        Optional<FableUserEntity> user = userRepository.findById(fableUser.getId());

        assertRequestedPathsAllowed(request);

        List<LibraryPathEntity> paths = request.getPaths() == null || request.getPaths().isEmpty() ?
                new ArrayList<>() :
                request.getPaths().stream()
                        .map(LibraryPath::getPath)
                        .distinct()
                        .map(path -> LibraryPathEntity.builder().path(path).build())
                        .collect(Collectors.toList());

        LibraryEntity libraryEntity = LibraryEntity.builder()
                .name(request.getName())
                .libraryPaths(paths)
                .icon(request.getIcon())
                .iconType(request.getIconType())
                .watch(request.isWatch())
                .formatPriority(request.getFormatPriority())
                .allowedFormats(request.getAllowedFormats())
                .metadataSource(request.getMetadataSource())
                .organizationMode(request.getOrganizationMode())
                .tagByDirectory(request.isTagByDirectory())
                .directoryTagDepth(request.getDirectoryTagDepth() != null ? request.getDirectoryTagDepth() : DirectoryTagDepth.LAST_ONLY)
                .users(List.of(user.get()))
                .build();

        for (LibraryPathEntity pathEntity : paths) {
            pathEntity.setLibrary(libraryEntity);
        }

        libraryEntity = libraryRepository.save(libraryEntity);
        Long libraryId = libraryEntity.getId();

        if (request.isWatch()) {
            for (LibraryPathEntity pathEntity : libraryEntity.getLibraryPaths()) {
                Path path = Paths.get(pathEntity.getPath());
                libraryWatchService.registerPath(path, libraryId);
            }
        }

        refreshLibraryHealthSafely();

        SecurityContextVirtualThread.runWithSecurityContext(() -> {
            if (!scanningLibraries.add(libraryId)) {
                log.warn("Library {} is already being scanned, skipping duplicate process request", libraryId);
                return;
            }
            try {
                libraryProcessingService.processLibrary(libraryId);
            } catch (InvalidDataAccessApiUsageException e) {
                log.debug("InvalidDataAccessApiUsageException - Library id: {}", libraryId);
            } catch (Exception e) {
                log.error("Error while processing library {}", libraryId, e);
                failureNotificationService.reportError("Failed to process library: " + e.getMessage());
            } finally {
                scanningLibraries.remove(libraryId);
                refreshLibraryHealthSafely();
            }
            log.info("Parsing task completed!");
        });

        auditService.log(AuditAction.LIBRARY_CREATED, "Library", libraryEntity.getId(), "Created library: " + libraryEntity.getName());
        return toLibraryWithPathHealth(libraryEntity);
    }

    public void rescanLibrary(long libraryId) {
        LibraryEntity lib = libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        auditService.log(AuditAction.LIBRARY_SCANNED, "Library", libraryId, "Reconciled library: " + lib.getName());

        SecurityContextVirtualThread.runWithSecurityContext(() -> {
            if (!scanningLibraries.add(libraryId)) {
                log.warn("Library {} is already being scanned, skipping duplicate rescan request", libraryId);
                return;
            }
            try {
                RescanLibraryContext context = RescanLibraryContext.builder()
                        .libraryId(libraryId)
                        .build();
                libraryProcessingService.rescanLibrary(context);
            } catch (InvalidDataAccessApiUsageException e) {
                log.debug("InvalidDataAccessApiUsageException - Library id: {}", libraryId);
            } catch (Exception e) {
                log.error("Error while parsing library books for library {}", libraryId, e);
                failureNotificationService.reportError("Failed to reconcile library '" + lib.getName() + "': " + e.getMessage());
            } finally {
                scanningLibraries.remove(libraryId);
                refreshLibraryHealthSafely();
            }
            log.info("Parsing task completed!");
        });
    }

    public void scanLibraryForNewFiles(long libraryId) {
        LibraryEntity lib = libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        auditService.log(AuditAction.LIBRARY_SCANNED, "Library", libraryId, "Scanned library for new files: " + lib.getName());

        SecurityContextVirtualThread.runWithSecurityContext(() -> {
            if (!scanningLibraries.add(libraryId)) {
                log.warn("Library {} is already being scanned, skipping duplicate new-file scan request", libraryId);
                return;
            }
            try {
                libraryProcessingService.scanLibraryForNewFiles(libraryId);
            } catch (InvalidDataAccessApiUsageException e) {
                log.debug("InvalidDataAccessApiUsageException - Library id: {}", libraryId);
            } catch (Exception e) {
                log.error("Error while scanning library {} for new files", libraryId, e);
                failureNotificationService.reportError("Failed to scan library '" + lib.getName() + "' for new files: " + e.getMessage());
            } finally {
                scanningLibraries.remove(libraryId);
                refreshLibraryHealthSafely();
            }
            log.info("New-file scan task completed!");
        });
    }

    public void scanLibraryDirectoriesForNewFiles(long libraryId, Set<String> targetPaths) {
        LibraryEntity lib = libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        auditService.log(AuditAction.LIBRARY_SCANNED, "Library", libraryId,
                "Scanned new files in specific directories for library: " + lib.getName());

        SecurityContextVirtualThread.runWithSecurityContext(() -> {
            if (!scanningLibraries.add(libraryId)) {
                log.warn("Library {} is already being scanned, skipping duplicate directory scan request", libraryId);
                return;
            }
            try {
                libraryProcessingService.scanLibraryDirectoriesForNewFiles(libraryId, targetPaths);
            } catch (InvalidDataAccessApiUsageException e) {
                log.debug("InvalidDataAccessApiUsageException - Library id: {}", libraryId);
            } catch (Exception e) {
                log.error("Error while scanning library {} directories for new files", libraryId, e);
                failureNotificationService.reportError("Failed to scan directories in library '" + lib.getName() + "': " + e.getMessage());
            } finally {
                scanningLibraries.remove(libraryId);
                refreshLibraryHealthSafely();
            }
            log.info("Directory-scoped new-file scan task completed!");
        });
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Library getLibrary(long libraryId) {
        LibraryEntity libraryEntity = libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        return toLibraryWithPathHealth(libraryEntity);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Library> getAllLibraries() {
        List<LibraryEntity> libraries = libraryRepository.findAll();
        return libraries.stream().map(this::toLibraryWithPathHealth).toList();
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Library> getLibraries() {
        FableUser user = authenticationService.getAuthenticatedUser();
        FableUserEntity userEntity = userRepository.findById(user.getId())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        List<LibraryEntity> libraries;
        if (userEntity.getPermissions().isPermissionAdmin()) {
            // Admins receive all libraries (including hidden personal) for management UIs.
            // Working-catalog consumers (sidebar, books, AI) must filter via ownerUserId / showInAdminCatalog
            // or use LibraryVisibilityService on the server for book queries.
            libraries = libraryRepository.findAll();
        } else {
            List<Long> libraryIds = userEntity.getLibraries().stream().map(LibraryEntity::getId).toList();
            libraries = libraryRepository.findByIdIn(libraryIds);
        }
        return enrichLibraries(libraries);
    }

    private List<Library> enrichLibraries(List<LibraryEntity> libraries) {
        Set<Long> ownerIds = libraries.stream()
                .map(LibraryEntity::getOwnerUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> ownerUsernames = ownerIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(ownerIds).stream()
                .collect(Collectors.toMap(FableUserEntity::getId, FableUserEntity::getUsername, (a, b) -> a));

        return libraries.stream().map(entity -> {
            Library library = toLibraryWithPathHealth(entity);
            if (entity.getOwnerUserId() != null) {
                library.setOwnerUsername(ownerUsernames.get(entity.getOwnerUserId()));
            }
            library.setShowInAdminCatalog(entity.isShowInAdminCatalog());
            return library;
        }).toList();
    }

    private Library toLibraryWithPathHealth(LibraryEntity entity) {
        Library library = libraryMapper.toLibrary(entity);
        if (library != null && library.getPaths() != null) {
            for (LibraryPath lp : library.getPaths()) {
                if (lp != null && lp.getPath() != null) {
                    lp.setAccessible(LibraryHealthService.isPathAccessibleOnDisk(lp.getPath()));
                }
            }
        }
        return library;
    }

    private void refreshLibraryHealthSafely() {
        if (libraryHealthService == null) {
            return;
        }
        try {
            libraryHealthService.refreshAndBroadcast();
        } catch (Exception e) {
            log.debug("Failed to refresh library health: {}", e.getMessage());
        }
    }

    @Transactional
    public void deleteLibrary(long id) {
        LibraryEntity library = libraryRepository.findById(id)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(id));
        libraryWatchService.unregisterLibrary(id);
        Set<Long> bookIds = library.getBookEntities().stream().map(BookEntity::getId).collect(Collectors.toSet());
        fileService.deleteBookCovers(bookIds);
        String libraryName = library.getName();
        libraryRepository.deleteById(id);
        refreshLibraryHealthSafely();
        auditService.log(AuditAction.LIBRARY_DELETED, "Library", id, "Deleted library: " + libraryName);
        log.info("Library deleted successfully: {}", id);
    }

    public Book getBook(long libraryId, long bookId) {
        libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        BookEntity bookEntity = bookRepository.findBookByIdAndLibraryId(bookId, libraryId).orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        return bookMapper.toBook(bookEntity);
    }

    public List<Book> getBooks(long libraryId) {
        libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        List<BookEntity> bookEntities = bookRepository.findAllWithMetadataByLibraryId(libraryId);
        return bookEntities.stream().map(bookMapper::toBook).toList();
    }

    public Library setFileNamingPattern(long libraryId, String pattern) {
        LibraryEntity library = libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        library.setFileNamingPattern(pattern);
        Library result = libraryMapper.toLibrary(libraryRepository.save(library));
        auditService.log(AuditAction.NAMING_PATTERN_CHANGED, "Library", libraryId, "Changed naming pattern for library: " + library.getName() + " to: " + pattern);
        return result;
    }

    public Map<String, Long> getBookCountsByFormat(long libraryId) {
        libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        Map<String, Long> counts = new HashMap<>();
        for (BookFileType type : BookFileType.values()) {
            long count = bookRepository.countByLibraryIdAndBookType(libraryId, type);
            if (count > 0) {
                counts.put(type.name(), count);
            }
        }
        return counts;
    }

    public int scanLibraryPaths(CreateLibraryRequest request) {
        int count = 0;
        if (request.getPaths() == null || request.getPaths().isEmpty()) {
            return count;
        }
        Set<BookFileType> allowedFormats = request.getAllowedFormats() != null && !request.getAllowedFormats().isEmpty()
                ? Set.copyOf(request.getAllowedFormats())
                : null;
        for (LibraryPath libraryPath : request.getPaths()) {
            Path path = Paths.get(libraryPath.getPath());
            if (!Files.exists(path)) {
                log.warn("Path does not exist: {}", path);
                continue;
            }
            if (Files.isDirectory(path)) {
                count += scanDirectory(path, allowedFormats);
            } else if (Files.isRegularFile(path) && isProcessableFile(path, allowedFormats)) {
                count++;
            }
        }
        return count;
    }

    private int scanDirectory(Path directory, Set<BookFileType> allowedFormats) {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    count += scanDirectory(entry, allowedFormats);
                } else if (Files.isRegularFile(entry) && isProcessableFile(entry, allowedFormats)) {
                    count++;
                }
            }
        } catch (IOException e) {
            log.error("Error scanning directory: {}", directory, e);
        }
        return count;
    }

    private boolean isProcessableFile(Path file, Set<BookFileType> allowedFormats) {
        String fileName = file.getFileName().toString().toLowerCase();
        for (BookFileType fileType : BookFileType.values()) {
            if (allowedFormats != null && !allowedFormats.contains(fileType)) {
                continue;
            }
            for (String ext : fileType.getExtensions()) {
                if (fileName.endsWith("." + ext)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void assertRequestedPathsAllowed(CreateLibraryRequest request) {
        if (request.getPaths() == null) {
            return;
        }
        for (LibraryPath libraryPath : request.getPaths()) {
            if (libraryPath == null || libraryPath.getPath() == null || libraryPath.getPath().isBlank()) {
                continue;
            }
            pathService.assertPathAllowedForCurrentUser(libraryPath.getPath());
        }
    }
}

