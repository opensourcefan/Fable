package org.fable.service.bookdrop;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fable.exception.ApiError;
import org.fable.model.dto.Book;
import org.fable.model.dto.BookMetadata;
import org.fable.model.dto.metadata.IsbnDiscoveryResult;
import org.fable.model.dto.request.MetadataRefreshOptions;
import org.fable.model.dto.settings.AppSettings;
import org.fable.model.entity.BookdropFileEntity;
import org.fable.model.enums.BookFileExtension;
import org.fable.model.enums.MetadataProvider;
import org.fable.repository.BookdropFileRepository;
import org.fable.service.appsettings.AppSettingService;
import org.fable.service.metadata.IsbnDiscoveryService;
import org.fable.service.metadata.IsbnMetadataFillService;
import org.fable.service.metadata.MetadataRefreshService;
import org.fable.service.metadata.extractor.MetadataExtractorFactory;
import org.fable.util.FileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import static org.fable.model.entity.BookdropFileEntity.Status.PENDING_REVIEW;

@Slf4j
@AllArgsConstructor
@Service
public class BookdropMetadataService {

    private final BookdropFileRepository bookdropFileRepository;
    private final AppSettingService appSettingService;
    private final ObjectMapper objectMapper;
    private final MetadataExtractorFactory metadataExtractorFactory;
    private final MetadataRefreshService metadataRefreshService;
    private final FileService fileService;
    private final IsbnDiscoveryService isbnDiscoveryService;
    private final IsbnMetadataFillService isbnMetadataFillService;

    @Transactional
    public BookdropFileEntity attachInitialMetadata(Long bookdropFileId) throws JacksonException {
        BookdropFileEntity entity = getOrThrow(bookdropFileId);
        BookMetadata initial = extractInitialMetadata(entity);
        if (initial == null) {
            log.warn("Metadata extraction returned null for file: {}. Using filename as fallback.", entity.getFileName());
            initial = BookMetadata.builder()
                    .title(FilenameUtils.getBaseName(entity.getFileName()))
                    .build();
        }

        AppSettings appSettings = appSettingService.getAppSettings();
        if (appSettings.isIsbnDiscoveryEnabled()
                && appSettings.isIsbnDiscoveryOnBookdrop()
                && !hasIsbn(initial)) {
            File file = new File(entity.getFilePath());
            IsbnDiscoveryResult discovery = isbnDiscoveryService.discoverFromFile(file, initial);
            if (discovery.hasResolvedIsbn()) {
                isbnDiscoveryService.applyResolvedIsbn(initial, discovery);
                log.info("ISBN discovery resolved {} for bookdrop file '{}'", discovery.getIsbn13(), entity.getFileName());
            } else if (discovery.getStatus() == IsbnDiscoveryResult.Status.AMBIGUOUS) {
                log.info("ISBN discovery ambiguous for bookdrop file '{}': {}", entity.getFileName(), discovery.getMessage());
            } else if (discovery.getStatus() == IsbnDiscoveryResult.Status.OCR_UNAVAILABLE) {
                log.info("ISBN discovery OCR soft-fail for bookdrop file '{}': {}", entity.getFileName(), discovery.getMessage());
            }
        }

        extractAndSaveCover(entity);
        String initialJson = objectMapper.writeValueAsString(initial);
        entity.setOriginalMetadata(initialJson);
        entity.setUpdatedAt(Instant.now());
        return bookdropFileRepository.save(entity);
    }

    public BookdropFileEntity attachFetchedMetadata(Long bookdropFileId) throws JacksonException {
        BookdropFileEntity entity = getOrThrow(bookdropFileId);

        AppSettings appSettings = appSettingService.getAppSettings();

        MetadataRefreshOptions refreshOptions = appSettings.getDefaultMetadataRefreshOptions();

        BookMetadata initial = objectMapper.readValue(entity.getOriginalMetadata(), BookMetadata.class);

        if (!hasSearchableMetadata(initial, entity)) {
            log.info("Skipping online metadata fetch for '{}' — no reliable search data (title derived from filename, no ISBN or ASIN).", entity.getFileName());
            entity.setStatus(PENDING_REVIEW);
            entity.setUpdatedAt(Instant.now());
            return bookdropFileRepository.save(entity);
        }

        List<MetadataProvider> providers = metadataRefreshService.prepareProviders(refreshOptions);
        Book book = Book.builder()
                .metadata(initial)
                .build();

        if (providers.contains(MetadataProvider.GoodReads)) {
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(250, 1250));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        BookMetadata fetchedMetadata;
        boolean multiPass = appSettings.getIsbnFillMode() == null
                || "MULTI_PASS".equalsIgnoreCase(appSettings.getIsbnFillMode());
        String isbn = firstNonBlank(initial.getIsbn13(), initial.getIsbn10());
        if (multiPass && isbn != null && !isbn.isBlank()) {
            fetchedMetadata = isbnMetadataFillService.mergeByIsbn(isbn, initial);
            if (fetchedMetadata == null) {
                Map<MetadataProvider, BookMetadata> metadataMap = metadataRefreshService.fetchMetadataForBook(providers, book);
                fetchedMetadata = metadataMap.isEmpty()
                        ? null
                        : metadataRefreshService.buildFetchMetadata(initial, book.getId(), refreshOptions, metadataMap);
            }
        } else {
            Map<MetadataProvider, BookMetadata> metadataMap = metadataRefreshService.fetchMetadataForBook(providers, book);
            fetchedMetadata = metadataMap.isEmpty()
                    ? null
                    : metadataRefreshService.buildFetchMetadata(initial, book.getId(), refreshOptions, metadataMap);
        }
        BookdropFileEntity latestEntity = bookdropFileRepository.findById(bookdropFileId).orElse(null);
        if (latestEntity == null || latestEntity.getStatus() != PENDING_REVIEW) {
            log.info("Bookdrop file {} was finalized or removed during online fetch; skipping metadata write.", bookdropFileId);
            return latestEntity != null ? latestEntity : entity;
        }
        if (fetchedMetadata == null) {
            log.info("No online metadata found for bookdrop file '{}'", latestEntity.getFileName());
            latestEntity.setStatus(PENDING_REVIEW);
            latestEntity.setUpdatedAt(Instant.now());
            return bookdropFileRepository.save(latestEntity);
        }
        if (Boolean.TRUE.equals(initial.getIsbnVerified())) {
            fetchedMetadata.setIsbnVerified(Boolean.TRUE);
            if (fetchedMetadata.getIsbn13() == null || fetchedMetadata.getIsbn13().isBlank()) {
                fetchedMetadata.setIsbn13(initial.getIsbn13());
            }
            if (fetchedMetadata.getIsbn10() == null || fetchedMetadata.getIsbn10().isBlank()) {
                fetchedMetadata.setIsbn10(initial.getIsbn10());
            }
        }
        String fetchedJson = objectMapper.writeValueAsString(fetchedMetadata);

        latestEntity.setFetchedMetadata(fetchedJson);
        latestEntity.setStatus(PENDING_REVIEW);
        latestEntity.setUpdatedAt(Instant.now());

        return bookdropFileRepository.save(latestEntity);
    }

    private boolean hasIsbn(BookMetadata metadata) {
        return metadata != null
                && ((metadata.getIsbn13() != null && !metadata.getIsbn13().isBlank())
                || (metadata.getIsbn10() != null && !metadata.getIsbn10().isBlank()));
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    private boolean hasSearchableMetadata(BookMetadata metadata, BookdropFileEntity entity) {
        if (hasAnyKnownIdentifier(metadata)) {
            return true;
        }
        String title = metadata.getTitle();
        String filenameFallback = FilenameUtils.getBaseName(entity.getFileName());
        return title != null && !title.isBlank()
                && !title.strip().equalsIgnoreCase(filenameFallback.strip());
    }

    private boolean hasAnyKnownIdentifier(BookMetadata m) {
        // Keep in sync with identifier fields in BookMetadata when new providers are added.
        return Stream.of(
                m.getIsbn13(), m.getIsbn10(), m.getAsin(),
                m.getGoodreadsId(), m.getGoogleId(),
                m.getHardcoverId(), m.getHardcoverBookId(),
                m.getComicvineId(), m.getDoubanId(),
                m.getLubimyczytacId(), m.getRanobedbId(), m.getAudibleId()
        ).anyMatch(id -> id != null && !id.isBlank());
    }

    private BookdropFileEntity getOrThrow(Long id) {
        return bookdropFileRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Bookdrop file not found: " + id));
    }

    private BookMetadata extractInitialMetadata(BookdropFileEntity entity) {
        File file = new File(entity.getFilePath());
        BookFileExtension fileExt = BookFileExtension.fromFileName(file.getName())
            .orElseThrow(() -> ApiError.INVALID_FILE_FORMAT.createException("Unsupported file extension"));
        return metadataExtractorFactory.extractMetadata(fileExt, file);
    }

    private void extractAndSaveCover(BookdropFileEntity entity) {
        File file = new File(entity.getFilePath());
        BookFileExtension fileExt = BookFileExtension.fromFileName(file.getName())
            .orElseThrow(() -> ApiError.INVALID_FILE_FORMAT.createException("Unsupported file extension"));
        byte[] coverBytes = metadataExtractorFactory.extractCover(fileExt, file);
        if (coverBytes != null) {
            try {
                FileService.saveImage(coverBytes, fileService.getTempBookdropCoverImagePath(entity.getId()));
            } catch (IOException e) {
                log.warn("Failed to save extracted cover for file: {}", entity.getFilePath(), e);
            }
        }
    }
}
