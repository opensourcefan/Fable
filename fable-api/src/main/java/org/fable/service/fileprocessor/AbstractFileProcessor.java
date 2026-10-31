package org.fable.service.fileprocessor;

import org.fable.mapper.BookMapper;
import org.fable.model.FileProcessResult;
import org.fable.model.dto.Book;
import org.fable.model.dto.settings.LibraryFile;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.BookMetadataEntity;
import org.fable.model.enums.FileProcessStatus;
import org.fable.model.enums.LibraryOrganizationMode;
import org.fable.repository.BookAdditionalFileRepository;
import org.fable.repository.BookRepository;
import org.fable.service.book.BookCreatorService;
import org.fable.service.file.FileFingerprint;
import org.fable.service.metadata.MetadataMatchService;
import org.fable.service.metadata.sidecar.SidecarMetadataWriter;
import org.fable.util.FileService;
import org.fable.util.FileUtils;
import org.fable.model.enums.DirectoryTagDepth;
import org.fable.service.library.DirectoryTagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

@Slf4j
public abstract class AbstractFileProcessor implements BookFileProcessor {

    protected final BookRepository bookRepository;
    protected final BookAdditionalFileRepository bookAdditionalFileRepository;
    protected final BookCreatorService bookCreatorService;
    protected final BookMapper bookMapper;
    protected final MetadataMatchService metadataMatchService;
    protected final FileService fileService;
    protected final SidecarMetadataWriter sidecarMetadataWriter;


    protected AbstractFileProcessor(BookRepository bookRepository,
                                    BookAdditionalFileRepository bookAdditionalFileRepository,
                                    BookCreatorService bookCreatorService,
                                    BookMapper bookMapper,
                                    FileService fileService,
                                    MetadataMatchService metadataMatchService,
                                    SidecarMetadataWriter sidecarMetadataWriter) {
        this.bookRepository = bookRepository;
        this.bookAdditionalFileRepository = bookAdditionalFileRepository;
        this.bookCreatorService = bookCreatorService;
        this.bookMapper = bookMapper;
        this.metadataMatchService = metadataMatchService;
        this.fileService = fileService;
        this.sidecarMetadataWriter = sidecarMetadataWriter;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    @Override
    public FileProcessResult processFile(LibraryFile libraryFile) {
        Path path = libraryFile.getFullPath();
        String hash = libraryFile.isFolderBased()
                ? FileFingerprint.generateFolderHash(path)
                : FileFingerprint.generateHash(path);
        Book book = createAndMapBook(libraryFile, hash);
        if (book == null) {
            return null;
        }
        return new FileProcessResult(book, FileProcessStatus.NEW);
    }

    private Book createAndMapBook(LibraryFile libraryFile, String hash) {
        BookEntity entity = processNewFile(libraryFile);
        if (Boolean.TRUE.equals(entity.getRemovedFromLibrary())) {
            log.info("Skipping permanently removed book id={} for file '{}'", entity.getId(), libraryFile.getFileName());
            return null;
        }
        entity.getPrimaryBookFile().setCurrentHash(hash);
        applyDirectoryTag(entity, libraryFile);
        applyTitleFallback(entity, libraryFile);
        entity.setMetadataMatchScore(metadataMatchService.calculateMatchScore(entity));
        bookCreatorService.saveConnections(entity);

        if (sidecarMetadataWriter.isWriteOnScanEnabled()) {
            try {
                sidecarMetadataWriter.writeSidecarMetadata(entity);
            } catch (Exception e) {
                log.warn("Failed to write sidecar metadata for book ID {}: {}", entity.getId(), e.getMessage());
            }
        }

        return bookMapper.toBook(entity);
    }

    protected abstract BookEntity processNewFile(LibraryFile libraryFile);

    private void applyTitleFallback(BookEntity entity, LibraryFile libraryFile) {
        BookMetadataEntity metadata = entity.getMetadata();
        if (metadata == null) return;
        if (metadata.getTitle() != null && !metadata.getTitle().isBlank()) return;
        String filename = libraryFile.getFileName();
        if (filename == null || filename.isBlank()) return;
        String base;
        if (libraryFile.isFolderBased()) {
            base = filename.trim();
        } else {
            int lastDot = filename.lastIndexOf('.');
            base = (lastDot > 0 ? filename.substring(0, lastDot) : filename).trim();
        }
        if (!base.isBlank()) {
            metadata.setTitle(base);
        }
    }

    private void applyDirectoryTag(BookEntity entity, LibraryFile libraryFile) {
        var library = libraryFile.getLibraryEntity();
        if (library == null || !library.isTagByDirectory()) return;
        String subPath = libraryFile.getFileSubPath();
        if (subPath == null || subPath.isEmpty()) return;
        DirectoryTagDepth depth = library.getDirectoryTagDepth() != null
                ? library.getDirectoryTagDepth()
                : DirectoryTagDepth.LAST_ONLY;
        Set<String> tags = DirectoryTagService.extractDirectoryTags(subPath, depth);
        if (!tags.isEmpty()) {
            bookCreatorService.addTagsToBook(tags, entity);
        }
    }

    protected Path getBookFolderForCoverFallback(LibraryFile libraryFile) {
        if (libraryFile.isFolderBased()) {
            return libraryFile.getFullPath();
        }
        if (libraryFile.getLibraryEntity().getOrganizationMode() == LibraryOrganizationMode.BOOK_PER_FOLDER) {
            return libraryFile.getFullPath().getParent();
        }
        return null;
    }

    protected boolean generateCoverFromFolderImage(BookEntity bookEntity, Path bookFolder) {
        Optional<Path> coverImage = FileUtils.findCoverImageInFolder(bookFolder);
        if (coverImage.isEmpty()) return false;
        try {
            BufferedImage image = ImageIO.read(coverImage.get().toFile());
            if (image == null) return false;
            try {
                return fileService.saveCoverImages(image, bookEntity.getId());
            } finally {
                image.flush();
            }
        } catch (Exception e) {
            log.debug("Failed to use folder cover image {}: {}", coverImage.get(), e.getMessage());
            return false;
        }
    }

    protected boolean generateAudiobookCoverFromFolderImage(BookEntity bookEntity, Path bookFolder) {
        Optional<Path> coverImage = FileUtils.findCoverImageInFolder(bookFolder);
        if (coverImage.isEmpty()) return false;
        try {
            BufferedImage image = FileService.readImage(Files.readAllBytes(coverImage.get()));
            if (image == null) return false;
            try {
                return fileService.saveAudiobookCoverImages(image, bookEntity.getId());
            } finally {
                image.flush();
            }
        } catch (Exception e) {
            log.debug("Failed to use folder cover image {}: {}", coverImage.get(), e.getMessage());
            return false;
        }
    }
}