package org.fable.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.fable.convertor.BookRecommendationIdsListConverter;
import org.fable.model.dto.BookRecommendationLite;
import org.fable.model.enums.BookFileType;
import org.fable.model.enums.IsbnDiscoveryStatus;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.LazyGroup;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "book")
public class BookEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metadata_match_score")
    private Float metadataMatchScore;

    @OneToOne(mappedBy = "book", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private BookMetadataEntity metadata;

    @Column(name = "metadata_updated_at")
    private Instant metadataUpdatedAt;

    @Column(name = "last_metadata_fetch_at")
    private Instant lastMetadataFetchAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "isbn_discovery_status", length = 32)
    private IsbnDiscoveryStatus isbnDiscoveryStatus;

    @Column(name = "isbn_discovery_checked_at")
    private Instant isbnDiscoveryCheckedAt;

    @Column(name = "isbn_discovery_detail", length = 1000)
    private String isbnDiscoveryDetail;

    @Column(name = "metadata_for_write_updated_at")
    private Instant metadataForWriteUpdatedAt;

    @ManyToOne
    @JoinColumn(name = "library_id", nullable = false)
    private LibraryEntity library;

    @ManyToOne
    @JoinColumn(name = "library_path_id")
    private LibraryPathEntity libraryPath;

    @Column(name = "is_physical")
    @Builder.Default
    private Boolean isPhysical = Boolean.FALSE;

    @Column(name = "is_currently_reading")
    @Builder.Default
    private Boolean isCurrentlyReading = Boolean.FALSE;

    @Column(name = "added_on")
    private Instant addedOn;

    @Column(name = "scanned_on")
    private Instant scannedOn;

    @Column(name = "book_cover_hash", length = 20)
    private String bookCoverHash;

    @Column(name = "audiobook_cover_hash", length = 20)
    private String audiobookCoverHash;

    @Column(name = "deleted")
    @Builder.Default
    private Boolean deleted = Boolean.FALSE;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "removed_from_library")
    @Builder.Default
    private Boolean removedFromLibrary = Boolean.FALSE;

    @Column(name = "marked_for_ai_search")
    @Builder.Default
    private Boolean markedForAiSearch = Boolean.FALSE;

    @Column(name = "staged")
    @Builder.Default
    private Boolean staged = Boolean.FALSE;

    @Column(name = "staging_completed_at")
    private Instant stagingCompletedAt;

    @Column(name = "file_type", length = 120)
    private String fileType;

    @ManyToMany
    @JoinTable(
            name = "book_shelf_mapping",
            joinColumns = @JoinColumn(name = "book_id"),
            inverseJoinColumns = @JoinColumn(name = "shelf_id")
    )
    @Builder.Default
    private Set<ShelfEntity> shelves = new HashSet<>();

    @Basic(fetch = FetchType.LAZY)
    @LazyGroup("recommendations")
    @Convert(converter = BookRecommendationIdsListConverter.class)
    @Column(name = "similar_books_json", columnDefinition = "TEXT")
    private Set<BookRecommendationLite> similarBooksJson;

    @OneToMany(mappedBy = "book", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    @BatchSize(size = 20)
    @Builder.Default
    private List<BookFileEntity> bookFiles = new ArrayList<>();

    @OneToMany(mappedBy = "book", fetch = FetchType.LAZY)
    @Builder.Default
    private List<UserBookProgressEntity> userBookProgress = new ArrayList<>();

    public Path getFullFilePath() {
        BookFileEntity primaryBookFile = getPrimaryBookFile();
        if (primaryBookFile == null || libraryPath == null || libraryPath.getPath() == null || primaryBookFile.getFileSubPath() == null || primaryBookFile.getFileName() == null) {
            return null;
        }

        return Paths.get(libraryPath.getPath(), primaryBookFile.getFileSubPath(), primaryBookFile.getFileName());
    }

    public BookFileEntity getPrimaryBookFile() {
        if (bookFiles == null) {
            bookFiles = new ArrayList<>();
        }
        if (bookFiles.isEmpty()) {
            return null;
        }
        List<BookFileEntity> bookFormats = bookFiles.stream()
                .filter(BookFileEntity::isBook)
                .toList();

        if (bookFormats.isEmpty()) {
            return bookFiles.getFirst();
        }

        if (library != null && library.getFormatPriority() != null && !library.getFormatPriority().isEmpty()) {
            for (BookFileType format : library.getFormatPriority()) {
                var match = bookFormats.stream()
                        .filter(bf -> bf.isBookFormat() && bf.getBookType() == format)
                        .findFirst();
                if (match.isPresent()) {
                    return match.get();
                }
            }
        }
        return bookFormats.getFirst();
    }

    public boolean hasFiles() {
        return bookFiles != null && !bookFiles.isEmpty();
    }

    public void setCurrentlyReading(Boolean currentlyReading) {
        this.isCurrentlyReading = currentlyReading;
    }
}
