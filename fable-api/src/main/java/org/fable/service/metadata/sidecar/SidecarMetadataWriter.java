package org.fable.service.metadata.sidecar;

import lombok.extern.slf4j.Slf4j;
import org.fable.config.AppProperties;
import org.fable.model.dto.settings.MetadataPersistenceSettings;
import org.fable.model.dto.settings.SidecarSettings;
import org.fable.model.dto.sidecar.SidecarMetadata;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.BookMetadataEntity;
import org.fable.service.appsettings.AppSettingService;
import org.fable.util.FileService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Slf4j
@Service
public class SidecarMetadataWriter {

    private final AppProperties appProperties;
    private final SidecarMetadataMapper mapper;
    private final SidecarPathResolver sidecarPathResolver;
    private final FileService fileService;
    private final AppSettingService appSettingService;
    private final ObjectMapper objectMapper;

    public SidecarMetadataWriter(AppProperties appProperties, SidecarMetadataMapper mapper, SidecarPathResolver sidecarPathResolver, FileService fileService, AppSettingService appSettingService) {
        this.appProperties = appProperties;
        this.mapper = mapper;
        this.sidecarPathResolver = sidecarPathResolver;
        this.fileService = fileService;
        this.appSettingService = appSettingService;
        this.objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .configure(SerializationFeature.INDENT_OUTPUT, true)
                .build();
    }

    public void writeSidecarMetadata(BookEntity book) {
        writeSidecarMetadata(book, false);
    }

    public boolean writeSidecarMetadata(BookEntity book, boolean forceWrite) {
        return writeSidecarMetadataWithResult(book, forceWrite).completed();
    }

    public SidecarWriteResult writeSidecarMetadataWithResult(BookEntity book, boolean forceWrite) {
        if (!forceWrite && !appProperties.isLocalStorage()) {
            return SidecarWriteResult.failure("Local sidecar writes are disabled because DISK_TYPE is not LOCAL.");
        }
        if (book == null || book.getMetadata() == null) {
            log.warn("Cannot write sidecar metadata: book or metadata is null");
            return SidecarWriteResult.failure("Book metadata is missing.");
        }

        boolean physicalDirectorySidecar = sidecarPathResolver.isPhysicalDirectorySidecar(book);
        SidecarSettings settings = getSidecarSettings();
        if (!forceWrite && !physicalDirectorySidecar && (settings == null || !settings.isEnabled())) {
            log.debug("Sidecar metadata is disabled");
            return SidecarWriteResult.failure("Automatic sidecar writing is disabled.");
        }

        Path sidecarPath = null;
        try {
            sidecarPath = sidecarPathResolver.resolveSidecarPath(book);
            if (sidecarPath == null) {
                log.warn("Cannot write sidecar metadata: no sidecar target path available");
                return SidecarWriteResult.failure("No sidecar target path is available for this book.");
            }

            if (!physicalDirectorySidecar) {
                Path bookPath = book.getFullFilePath();
                if (bookPath == null || !Files.exists(bookPath)) {
                    log.warn("Cannot write sidecar metadata: book file does not exist for book ID {} at {}",
                            book.getId(), bookPath);
                    if (sidecarPath != null && Files.exists(sidecarPath)) {
                        try {
                            Files.deleteIfExists(sidecarPath);
                            log.info("Deleted orphaned sidecar file: {}", sidecarPath);
                        } catch (IOException e) {
                            log.warn("Failed to delete orphaned sidecar file: {}", sidecarPath, e);
                        }
                    }
                    return SidecarWriteResult.missingSource("The source book file does not exist on disk.");
                }
            }

            Files.createDirectories(sidecarPath.getParent());
            BookMetadataEntity metadata = book.getMetadata();

            String coverFileName = null;
            if (physicalDirectorySidecar || (settings != null && settings.isIncludeCoverFile())) {
                Path coverPath = sidecarPathResolver.resolveCoverPath(book);
                if (coverPath != null) {
                    coverFileName = coverPath.getFileName().toString();
                    writeCoverFile(book, coverPath);
                }
            }

            SidecarMetadata sidecarMetadata = mapper.toSidecarMetadata(metadata, coverFileName);
            String json = objectMapper.writeValueAsString(sidecarMetadata);
            json = json.replace(" : ", ": ").replace("[ ]", "[]");
            Files.writeString(sidecarPath, json);

            log.info("Wrote sidecar metadata to: {}", sidecarPath);
            return SidecarWriteResult.succeeded();
        } catch (IOException e) {
            String failureMessage = describeWriteFailure(sidecarPath, e);
            log.error("Failed to write sidecar metadata for book ID {} to {}", book.getId(), sidecarPath, e);
            return SidecarWriteResult.failure(failureMessage);
        }
    }

    private String describeWriteFailure(Path sidecarPath, IOException exception) {
        if (exception instanceof AccessDeniedException) {
            return "Permission denied while writing " + sidecarPath;
        }

        if (exception instanceof FileSystemException fileSystemException) {
            String reason = fileSystemException.getReason();
            if (reason != null && !reason.isBlank()) {
                return "Failed to write " + sidecarPath + ": " + reason;
            }
        }

        String message = exception.getMessage();
        if (message != null && !message.isBlank()) {
            return "Failed to write " + sidecarPath + ": " + message;
        }

        return "Failed to write " + sidecarPath + ".";
    }

    public void deleteSidecarFiles(Path bookPath) {
        if (bookPath == null) {
            return;
        }

        try {
            Path sidecarPath = sidecarPathResolver.resolveStandardSidecarPath(bookPath);
            if (Files.exists(sidecarPath)) {
                Files.delete(sidecarPath);
                log.info("Deleted sidecar file: {}", sidecarPath);
            }

            Path coverPath = sidecarPathResolver.resolveStandardCoverPath(bookPath);
            if (Files.exists(coverPath)) {
                Files.delete(coverPath);
                log.info("Deleted sidecar cover file: {}", coverPath);
            }
        } catch (IOException e) {
            log.warn("Failed to delete sidecar files for {}: {}", bookPath, e.getMessage());
        }
    }

    public void deleteSidecarFiles(BookEntity book) {
        if (book == null) {
            return;
        }

        Path sidecarPath = sidecarPathResolver.resolveSidecarPath(book);
        Path coverPath = sidecarPathResolver.resolveCoverPath(book);
        deleteResolvedSidecarFiles(sidecarPath, coverPath);
    }

    public void moveSidecarFiles(Path oldBookPath, Path newBookPath) {
        if (oldBookPath == null || newBookPath == null) {
            return;
        }

        try {
            Path oldSidecarPath = sidecarPathResolver.resolveStandardSidecarPath(oldBookPath);
            if (Files.exists(oldSidecarPath)) {
                Path newSidecarPath = sidecarPathResolver.resolveStandardSidecarPath(newBookPath);
                Files.createDirectories(newSidecarPath.getParent());
                Files.move(oldSidecarPath, newSidecarPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("Moved sidecar file from {} to {}", oldSidecarPath, newSidecarPath);
            }

            Path oldCoverPath = sidecarPathResolver.resolveStandardCoverPath(oldBookPath);
            if (Files.exists(oldCoverPath)) {
                Path newCoverPath = sidecarPathResolver.resolveStandardCoverPath(newBookPath);
                Files.move(oldCoverPath, newCoverPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("Moved sidecar cover from {} to {}", oldCoverPath, newCoverPath);
            }
        } catch (IOException e) {
            log.warn("Failed to move sidecar files from {} to {}: {}", oldBookPath, newBookPath, e.getMessage());
        }
    }

    private void writeCoverFile(BookEntity book, Path coverPath) {
        if (coverPath == null) {
            return;
        }

        try {
            String coverFile = fileService.getCoverFile(book.getId());
            Path sourceCoverPath = Path.of(coverFile);
            if (Files.exists(sourceCoverPath)) {
                Files.createDirectories(coverPath.getParent());
                Files.copy(sourceCoverPath, coverPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("Wrote cover file to: {}", coverPath);
            }
        } catch (IOException e) {
            log.warn("Failed to write cover file for book ID {}: {}", book.getId(), e.getMessage());
        }
    }

    private void deleteResolvedSidecarFiles(Path sidecarPath, Path coverPath) {
        try {
            if (sidecarPath != null && Files.exists(sidecarPath)) {
                Files.delete(sidecarPath);
                log.info("Deleted sidecar file: {}", sidecarPath);
            }

            if (coverPath != null && Files.exists(coverPath)) {
                Files.delete(coverPath);
                log.info("Deleted sidecar cover file: {}", coverPath);
            }
        } catch (IOException e) {
            log.warn("Failed to delete resolved sidecar files: {}", e.getMessage());
        }
    }

    private SidecarSettings getSidecarSettings() {
        MetadataPersistenceSettings settings = appSettingService.getAppSettings().getMetadataPersistenceSettings();
        return settings != null ? settings.getSidecarSettings() : null;
    }

    public boolean isWriteOnUpdateEnabled() {
        SidecarSettings settings = getSidecarSettings();
        return settings != null && settings.isEnabled() && settings.isWriteOnUpdate();
    }

    public boolean isWriteOnScanEnabled() {
        SidecarSettings settings = getSidecarSettings();
        return settings != null && settings.isEnabled() && settings.isWriteOnScan();
    }

    public record SidecarWriteResult(boolean completed, boolean missingSource, String errorMessage) {
        public static SidecarWriteResult succeeded() {
            return new SidecarWriteResult(true, false, null);
        }

        public static SidecarWriteResult missingSource(String errorMessage) {
            return new SidecarWriteResult(false, true, errorMessage);
        }

        public static SidecarWriteResult failure(String errorMessage) {
            return new SidecarWriteResult(false, false, errorMessage);
        }
    }
}
