package org.fable.repository;

import org.fable.model.entity.BookEntity;
import org.fable.model.entity.LibraryPathEntity;
import org.fable.model.enums.BookFileType;
import org.fable.repository.projection.BookCoverUpdateProjection;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface BookRepository extends JpaRepository<BookEntity, Long>, JpaSpecificationExecutor<BookEntity> {
    Optional<BookEntity> findBookByIdAndLibraryId(long id, long libraryId);

    @EntityGraph(attributePaths = { "metadata", "metadata.comicMetadata", "metadata.authors", "metadata.categories", "metadata.moods", "metadata.tags", "shelves", "libraryPath", "library", "bookFiles" })
    @Query("SELECT b FROM BookEntity b LEFT JOIN FETCH b.bookFiles bf WHERE b.id = :id AND (b.deleted IS NULL OR b.deleted = false)")
    Optional<BookEntity> findByIdWithBookFiles(@Param("id") Long id);

    @Query("SELECT b FROM BookEntity b JOIN b.bookFiles bf WHERE bf.currentHash = :currentHash AND bf.isBookFormat = true AND (b.deleted IS NULL OR b.deleted = false)")
    Optional<BookEntity> findByCurrentHash(@Param("currentHash") String currentHash);

    @Query("SELECT b FROM BookEntity b JOIN FETCH b.bookFiles bf WHERE bf.currentHash = :currentHash AND bf.isBookFormat = true AND (b.deleted IS NULL OR b.deleted = false OR b.deletedAt > :cutoff OR b.removedFromLibrary = true)")
    Optional<BookEntity> findByCurrentHashIncludingRecentlyDeleted(@Param("currentHash") String currentHash, @Param("cutoff") Instant cutoff);

    Optional<BookEntity> findByBookCoverHash(String bookCoverHash);

    @Query("SELECT b.id FROM BookEntity b WHERE b.library.id = :libraryId AND (b.deleted IS NULL OR b.deleted = false)")
    Set<Long> findBookIdsByLibraryId(@Param("libraryId") long libraryId);

    @Query("SELECT b.id FROM BookEntity b WHERE b.id IN :bookIds AND (b.deleted IS NULL OR b.deleted = false) AND b.lastMetadataFetchAt IS NULL")
    Set<Long> findBookIdsByIdInAndLastMetadataFetchAtIsNull(@Param("bookIds") Collection<Long> bookIds);

    @Query("SELECT b.id FROM BookEntity b WHERE b.id IN :bookIds AND (b.deleted IS NULL OR b.deleted = false) AND (b.lastMetadataFetchAt IS NULL OR b.lastMetadataFetchAt < :cutoff)")
    Set<Long> findBookIdsByIdInAndLastMetadataFetchAtBeforeOrNull(@Param("bookIds") Collection<Long> bookIds, @Param("cutoff") Instant cutoff);

    @Query("SELECT b.id FROM BookEntity b WHERE b.id IN :bookIds AND (b.deleted IS NULL OR b.deleted = false) AND b.lastMetadataFetchAt >= :cutoff")
    Set<Long> findBookIdsByIdInAndLastMetadataFetchAtOnOrAfter(@Param("bookIds") Collection<Long> bookIds, @Param("cutoff") Instant cutoff);

    @EntityGraph(attributePaths = {"metadata"})
    @Query("""
            SELECT b FROM BookEntity b
            WHERE b.staged = true
              AND (b.deleted IS NULL OR b.deleted = false)
            """)
    List<BookEntity> findAllStagedWithMetadata();

    @EntityGraph(attributePaths = {"metadata"})
    @Query("""
            SELECT b FROM BookEntity b
            WHERE b.staged = true
              AND b.library.id IN :libraryIds
              AND (b.deleted IS NULL OR b.deleted = false)
            """)
    List<BookEntity> findAllStagedWithMetadataByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds);

    @Query("""
            SELECT b.id FROM BookEntity b
            WHERE b.id IN :bookIds
              AND b.library.id IN :libraryIds
              AND (b.deleted IS NULL OR b.deleted = false)
            """)
    Set<Long> findBookIdsByIdInAndLibraryIdIn(@Param("bookIds") Collection<Long> bookIds,
                                              @Param("libraryIds") Collection<Long> libraryIds);

    @Query("SELECT DISTINCT b FROM BookEntity b JOIN b.bookFiles bf WHERE b.libraryPath.id = :libraryPathId AND (bf.fileSubPath = :fileSubPathPrefix OR bf.fileSubPath LIKE CONCAT(:fileSubPathPrefix, '/%')) AND bf.isBookFormat = true AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllByLibraryPathIdAndFileSubPathStartingWith(@Param("libraryPathId") Long libraryPathId, @Param("fileSubPathPrefix") String fileSubPathPrefix);

    @Query("SELECT DISTINCT b FROM BookEntity b JOIN b.bookFiles bf WHERE b.libraryPath.id = :libraryPathId AND bf.fileSubPath = :fileSubPath AND bf.isBookFormat = true AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllByLibraryPathIdAndFileSubPath(@Param("libraryPathId") Long libraryPathId, @Param("fileSubPath") String fileSubPath);

    @Query("SELECT b FROM BookEntity b JOIN b.bookFiles bf WHERE b.libraryPath.id = :libraryPathId AND bf.fileSubPath = :fileSubPath AND bf.fileName = :fileName AND bf.isBookFormat = true AND (b.deleted IS NULL OR b.deleted = false)")
    Optional<BookEntity> findByLibraryPath_IdAndFileSubPathAndFileName(@Param("libraryPathId") Long libraryPathId,
                                                                       @Param("fileSubPath") String fileSubPath,
                                                                       @Param("fileName") String fileName);

    @Query("SELECT b.id FROM BookEntity b WHERE b.libraryPath.id IN :libraryPathIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<Long> findAllBookIdsByLibraryPathIdIn(@Param("libraryPathIds") Collection<Long> libraryPathIds);

        @Query("""
                        SELECT DISTINCT b.id FROM BookEntity b
                        JOIN b.bookFiles bf
                        WHERE b.libraryPath.id IN :libraryPathIds
                            AND bf.isBookFormat = true
                            AND bf.bookType = :bookType
                            AND (b.deleted IS NULL OR b.deleted = false)
                        """)
        List<Long> findAllBookIdsByLibraryPathIdInAndBookType(@Param("libraryPathIds") Collection<Long> libraryPathIds,
                                                                                                                    @Param("bookType") BookFileType bookType);

    @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.categories", "metadata.moods", "metadata.tags", "shelves", "libraryPath", "library", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithSummaryMetadata();

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "metadata.authors", "metadata.categories", "metadata.moods", "metadata.tags", "shelves", "libraryPath", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadata();

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "libraryPath", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllForMetadataFlush();

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "libraryPath", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE b.library.id = :libraryId AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllForMetadataFlushByLibraryId(@Param("libraryId") Long libraryId);

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "metadata.comicMetadata.characters", "metadata.comicMetadata.teams", "metadata.comicMetadata.locations", "metadata.comicMetadata.creatorMappings", "metadata.comicMetadata.creatorMappings.creator", "metadata.authors", "metadata.categories", "metadata.moods", "metadata.tags", "shelves", "libraryPath", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE b.id IN :bookIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByIds(@Param("bookIds") Set<Long> bookIds);

    @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.categories", "metadata.moods", "metadata.tags", "shelves", "libraryPath", "library", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE b.id IN :bookIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithSummaryMetadataByIds(@Param("bookIds") Set<Long> bookIds);

    /**
     * Lightweight variant of {@link #findAllWithSummaryMetadataByIds(Set)} for grid summaries.
     * Omits {@code metadata.moods}, {@code metadata.tags}, and {@code shelves} from the
     * EntityGraph — none of which are used by
     * {@link org.fable.service.book.BookQueryService#mapBookToGridSummary(BookEntity)}.
     * Reducing the fetch graph from 9 to 6 associations cuts the cartesian product in the
     * entity fetch, improving paged query performance.
     */
    @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.categories", "libraryPath", "library", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE b.id IN :bookIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllGridSummaryByIds(@Param("bookIds") Set<Long> bookIds);

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "metadata.authors", "metadata.categories", "metadata.moods", "metadata.tags", "shelves", "libraryPath", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE b.library.id = :libraryId AND b.id IN :bookIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByLibraryIdAndIds(@Param("libraryId") Long libraryId, @Param("bookIds") Collection<Long> bookIds);

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "metadata.authors", "metadata.categories", "metadata.moods", "metadata.tags", "shelves", "libraryPath", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE b.id IN :bookIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findWithMetadataByIdsWithPagination(@Param("bookIds") Set<Long> bookIds, Pageable pageable);

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "metadata.authors", "metadata.categories", "metadata.moods", "metadata.tags", "shelves", "libraryPath", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE b.library.id = :libraryId AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByLibraryId(@Param("libraryId") Long libraryId);

    @EntityGraph(attributePaths = {"metadata", "metadata.tags", "libraryPath", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE b.library.id = :libraryId AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllByLibraryIdWithFiles(@Param("libraryId") Long libraryId);

    @EntityGraph(attributePaths = {"bookFiles", "libraryPath"})
    @Query("SELECT b FROM BookEntity b WHERE b.library.id = :libraryId AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllByLibraryIdWithFilesAndPath(@Param("libraryId") Long libraryId);

    @Query("""
            SELECT DISTINCT b FROM BookEntity b
            LEFT JOIN FETCH b.bookFiles
            LEFT JOIN FETCH b.libraryPath
            WHERE b.library.id = :libraryId
            AND ((b.deleted IS NULL OR b.deleted = false) OR b.removedFromLibrary = true)
            """)
    List<BookEntity> findAllByLibraryIdWithFilesAndPathIncludingRemoved(@Param("libraryId") Long libraryId);

    @Query("""
            SELECT DISTINCT b FROM BookEntity b
            LEFT JOIN FETCH b.metadata m
            LEFT JOIN FETCH m.authors
            LEFT JOIN FETCH b.bookFiles
            LEFT JOIN FETCH b.libraryPath
            WHERE b.library.id = :libraryId
            AND (b.deleted IS NULL OR b.deleted = false)
            """)
    List<BookEntity> findAllForDuplicateDetection(@Param("libraryId") Long libraryId);

        @Query("""
            SELECT DISTINCT b FROM BookEntity b
            LEFT JOIN FETCH b.metadata m
            LEFT JOIN FETCH m.authors
            LEFT JOIN FETCH b.bookFiles
            LEFT JOIN FETCH b.libraryPath
            WHERE b.library.id = :libraryId
            AND ((b.deleted IS NULL OR b.deleted = false) OR b.removedFromLibrary = true)
            """)
        List<BookEntity> findAllForDuplicateDetectionIncludingRemoved(@Param("libraryId") Long libraryId);

            @Query("""
                SELECT DISTINCT b FROM BookEntity b
                LEFT JOIN FETCH b.metadata m
                LEFT JOIN FETCH m.authors
                LEFT JOIN FETCH b.bookFiles
                LEFT JOIN FETCH b.libraryPath
                WHERE b.id IN :bookIds
                AND (b.deleted IS NULL OR b.deleted = false)
                """)
            List<BookEntity> findAllForDuplicateDetectionByBookIds(@Param("bookIds") Collection<Long> bookIds);

            @Query("""
                SELECT DISTINCT b FROM BookEntity b
                LEFT JOIN FETCH b.metadata m
                LEFT JOIN FETCH m.authors
                LEFT JOIN FETCH b.bookFiles
                LEFT JOIN FETCH b.libraryPath
                WHERE (b.deleted IS NULL OR b.deleted = false)
                """)
            List<BookEntity> findAllForDuplicateDetectionAcrossLibraries();

    @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.categories", "metadata.moods", "metadata.tags", "shelves", "libraryPath", "library", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithSummaryMetadataByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds);

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "metadata.tags", "shelves", "libraryPath", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds);

    @EntityGraph(attributePaths = {"metadata", "bookFiles", "library"})
    @Query("SELECT DISTINCT b FROM BookEntity b WHERE (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllForCoverRegeneration();

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "metadata.tags", "shelves", "libraryPath", "bookFiles"})
    @Query("SELECT DISTINCT b FROM BookEntity b JOIN b.shelves s WHERE s.id = :shelfId AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByShelfId(@Param("shelfId") Long shelfId);

    @EntityGraph(attributePaths = { "metadata", "metadata.comicMetadata", "shelves", "libraryPath", "bookFiles" })
    @Query("SELECT DISTINCT b FROM BookEntity b JOIN b.bookFiles bf WHERE bf.isBookFormat = true AND bf.fileSizeKb IS NULL AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByFileSizeKbIsNull();

    @Query("""
                SELECT DISTINCT b FROM BookEntity b
                LEFT JOIN FETCH b.metadata m
                LEFT JOIN FETCH m.authors
                LEFT JOIN FETCH m.categories
                LEFT JOIN FETCH b.shelves
                WHERE (b.deleted IS NULL OR b.deleted = false)
            """)
    List<BookEntity> findAllFullBooks();

    @Query(value = """
                SELECT DISTINCT b.* FROM book b
                LEFT JOIN book_metadata m ON b.id = m.book_id
                WHERE (b.deleted IS NULL OR b.deleted = false)
                ORDER BY b.id
                LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<BookEntity> findBooksForMigrationBatch(@Param("offset") int offset, @Param("limit") int limit);

    @Query("""
                SELECT DISTINCT b FROM BookEntity b
                LEFT JOIN FETCH b.metadata m
                LEFT JOIN FETCH m.authors
                WHERE b.id IN :bookIds
            """)
    List<BookEntity> findBooksWithMetadataAndAuthors(@Param("bookIds") List<Long> bookIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM BookEntity b WHERE b.deleted IS TRUE AND (b.removedFromLibrary IS NULL OR b.removedFromLibrary = false)")
    int deleteAllSoftDeleted();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM BookEntity b WHERE b.deleted IS TRUE AND b.deletedAt < :cutoffDate AND (b.removedFromLibrary IS NULL OR b.removedFromLibrary = false)")
    int deleteSoftDeletedBefore(@Param("cutoffDate") Instant cutoffDate);

    @Query("SELECT COUNT(b) FROM BookEntity b WHERE b.deleted = TRUE AND (b.removedFromLibrary IS NULL OR b.removedFromLibrary = false)")
    long countAllSoftDeleted();

    @Query("""
        SELECT DISTINCT b FROM BookEntity b
        JOIN FETCH b.bookFiles bf
        WHERE b.libraryPath.id = :libraryPathId
        AND (bf.fileSubPath = :folderPath
             OR bf.fileSubPath LIKE CONCAT(:folderPath, '/%')
             OR (bf.folderBased = true AND CONCAT(bf.fileSubPath, '/', bf.fileName) = :folderPath))
        AND bf.isBookFormat = true
        AND (b.deleted IS NULL OR b.deleted = false)
        """)
    List<BookEntity> findBooksWithFilesUnderPath(@Param("libraryPathId") Long libraryPathId,
                                                  @Param("folderPath") String folderPath);

    @Query(value = """
        SELECT b.*
        FROM book b
        JOIN book_file bf ON bf.book_id = b.id
        WHERE b.library_id = :libraryId
          AND b.library_path_id = :libraryPathId
          AND bf.file_sub_path = :fileSubPath
          AND bf.file_name = :fileName
          AND bf.is_book = true
        LIMIT 1
    """, nativeQuery = true)
    Optional<BookEntity> findByLibraryIdAndLibraryPathIdAndFileSubPathAndFileName(
            @Param("libraryId") Long libraryId,
            @Param("libraryPathId") Long libraryPathId,
            @Param("fileSubPath") String fileSubPath,
            @Param("fileName") String fileName);

    @Query("SELECT COUNT(b.id) FROM BookEntity b WHERE b.id IN :bookIds AND (b.deleted IS NULL OR b.deleted = false)")
    long countByIdIn(@Param("bookIds") List<Long> bookIds);

    @Query("""
            SELECT COUNT(DISTINCT b) FROM BookEntity b
            JOIN b.bookFiles bf
            WHERE bf.isBookFormat = true
              AND bf.bookType = :type
              AND (b.deleted IS NULL OR b.deleted = false)
            """)
    long countByBookType(@Param("type") BookFileType type);

    @Query("""
            SELECT COUNT(DISTINCT b) FROM BookEntity b
            JOIN b.bookFiles bf
            WHERE b.library.id = :libraryId
              AND bf.isBookFormat = true
              AND bf.bookType = :type
              AND (b.deleted IS NULL OR b.deleted = false)
            """)
    long countByLibraryIdAndBookType(@Param("libraryId") Long libraryId, @Param("type") BookFileType type);

    @Query("SELECT COUNT(b) FROM BookEntity b WHERE b.library.id = :libraryId AND (b.deleted IS NULL OR b.deleted = false)")
    long countByLibraryId(@Param("libraryId") Long libraryId);

        @Query("""
            SELECT DISTINCT b FROM BookEntity b
            LEFT JOIN FETCH b.metadata m
            LEFT JOIN FETCH m.authors
            WHERE b.library.id = :libraryId
            AND b.isPhysical = true
            AND (b.deleted IS NULL OR b.deleted = false)
            AND (b.libraryPath.id = :libraryPathId OR b.libraryPath IS NULL)
            """)
        List<BookEntity> findActivePhysicalBooksByLibraryIdAndLibraryPathId(
            @Param("libraryId") Long libraryId,
            @Param("libraryPathId") Long libraryPathId);

    @Query("""
            SELECT b FROM BookEntity b
            LEFT JOIN b.bookFiles bf
            WHERE b.library.id = :libraryId
            AND (b.deleted IS NULL OR b.deleted = false)
            GROUP BY b
            HAVING COUNT(bf) = 0
            """)
    List<BookEntity> findFilelessBooksByLibraryId(@Param("libraryId") Long libraryId);

        @Query("""
            SELECT b FROM BookEntity b
            LEFT JOIN b.bookFiles bf
            WHERE b.library.id = :libraryId
            AND b.isPhysical = true
            AND (b.deleted IS NULL OR b.deleted = false)
            GROUP BY b
            HAVING COUNT(bf) = 0
            """)
        List<BookEntity> findPhysicalFilelessBooksByLibraryId(@Param("libraryId") Long libraryId);

    @Query("SELECT b.id as id, m.coverUpdatedOn as coverUpdatedOn FROM BookEntity b LEFT JOIN b.metadata m WHERE b.id IN :bookIds")
    List<BookCoverUpdateProjection> findCoverUpdateInfoByIds(@Param("bookIds") Collection<Long> bookIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE BookEntity b SET
                b.library.id = :libraryId,
                b.libraryPath = :libraryPath
            WHERE b.id = :bookId
            """)
    void updateLibrary(
            @Param("bookId") Long bookId,
            @Param("libraryId") Long libraryId,
            @Param("libraryPath") LibraryPathEntity libraryPath);

    /**
     * Get distinct series names for a library when groupUnknown=true.
     * Books without series name are grouped as "Unknown Series".
     */
    @Query("""
            SELECT DISTINCT 
                CASE 
                    WHEN m.seriesName IS NOT NULL THEN m.seriesName
                    ELSE :unknownSeriesName
                END as seriesName
            FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE b.library.id = :libraryId 
            AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY seriesName
            """)
    List<String> findDistinctSeriesNamesGroupedByLibraryId(
            @Param("libraryId") Long libraryId,
            @Param("unknownSeriesName") String unknownSeriesName);

    /**
     * Get distinct series names across all libraries when groupUnknown=true.
     * Books without series name are grouped as "Unknown Series".
     */
    @Query("""
            SELECT DISTINCT 
                CASE 
                    WHEN m.seriesName IS NOT NULL THEN m.seriesName
                    ELSE :unknownSeriesName
                END as seriesName
            FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE (b.deleted IS NULL OR b.deleted = false)
            ORDER BY seriesName
            """)
    List<String> findDistinctSeriesNamesGrouped(@Param("unknownSeriesName") String unknownSeriesName);

    /**
     * Get distinct series names for a library when groupUnknown=false.
     * Each book without series gets its own entry (title or filename).
     */
    @Query("""
            SELECT DISTINCT 
                CASE 
                    WHEN m.seriesName IS NOT NULL THEN m.seriesName
                    WHEN m.title IS NOT NULL THEN m.title
                    ELSE (
                        SELECT bf2.fileName FROM BookFileEntity bf2
                        WHERE bf2.book = b
                          AND bf2.isBookFormat = true
                          AND bf2.id = (
                              SELECT MIN(bf3.id) FROM BookFileEntity bf3
                              WHERE bf3.book = b AND bf3.isBookFormat = true
                          )
                    )
                END as seriesName
            FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE b.library.id = :libraryId 
            AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY seriesName
            """)
    List<String> findDistinctSeriesNamesUngroupedByLibraryId(@Param("libraryId") Long libraryId);

    /**
     * Get distinct series names across all libraries when groupUnknown=false.
     * Each book without series gets its own entry (title or filename).
     */
    @Query("""
            SELECT DISTINCT 
                CASE 
                    WHEN m.seriesName IS NOT NULL THEN m.seriesName
                    WHEN m.title IS NOT NULL THEN m.title
                    ELSE (
                        SELECT bf2.fileName FROM BookFileEntity bf2
                        WHERE bf2.book = b
                          AND bf2.isBookFormat = true
                          AND bf2.id = (
                              SELECT MIN(bf3.id) FROM BookFileEntity bf3
                              WHERE bf3.book = b AND bf3.isBookFormat = true
                          )
                    )
                END as seriesName
            FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE (b.deleted IS NULL OR b.deleted = false)
            ORDER BY seriesName
            """)
    List<String> findDistinctSeriesNamesUngrouped();

    /**
     * Find books by series name for a library when groupUnknown=true.
     * Uses the first bookFile.fileName as fallback when metadata.seriesName is null.
     */
    @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.categories", "metadata.tags", "libraryPath", "library", "bookFiles"})
    @Query("""
            SELECT DISTINCT b FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE b.library.id = :libraryId
            AND (
                (m.seriesName = :seriesName)
                OR (m.seriesName IS NULL AND :seriesName = :unknownSeriesName)
            )
            AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY COALESCE(m.seriesNumber, 0), b.id
            """)
    List<BookEntity> findBooksBySeriesNameGroupedByLibraryId(
            @Param("seriesName") String seriesName,
            @Param("libraryId") Long libraryId,
            @Param("unknownSeriesName") String unknownSeriesName);

    @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.categories", "metadata.tags", "libraryPath", "library", "bookFiles"})
    @Query("""
            SELECT DISTINCT b FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE (
                (m.seriesName = :seriesName)
                OR (m.seriesName IS NULL AND :seriesName = :unknownSeriesName)
            )
            AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY COALESCE(m.seriesNumber, 0), b.id
            """)
    List<BookEntity> findBooksBySeriesNameGrouped(
            @Param("seriesName") String seriesName,
            @Param("unknownSeriesName") String unknownSeriesName);

    /**
     * Find books by series name for a library when groupUnknown=false.
     * Matches by series name, or by title/filename for books without series.
     */
        @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.categories", "metadata.tags", "libraryPath", "library", "bookFiles"})
    @Query("""
            SELECT DISTINCT b FROM BookEntity b
            LEFT JOIN b.metadata m
            LEFT JOIN b.bookFiles bf
            WHERE b.library.id = :libraryId
            AND (
                (m.seriesName = :seriesName)
                OR (m.seriesName IS NULL AND m.title = :seriesName)
                OR (
                    m.seriesName IS NULL AND m.title IS NULL
                    AND bf.isBookFormat = true
                    AND bf.id = (
                        SELECT MIN(bf2.id) FROM BookFileEntity bf2
                        WHERE bf2.book = b AND bf2.isBookFormat = true
                    )
                    AND bf.fileName = :seriesName
                )
            )
            AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY COALESCE(m.seriesNumber, 0), b.id
            """)
    List<BookEntity> findBooksBySeriesNameUngroupedByLibraryId(
            @Param("seriesName") String seriesName,
            @Param("libraryId") Long libraryId);

    @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.categories", "metadata.tags", "libraryPath", "library", "bookFiles"})
    @Query("""
            SELECT DISTINCT b FROM BookEntity b
            LEFT JOIN b.metadata m
            LEFT JOIN b.bookFiles bf
            WHERE (
                (m.seriesName = :seriesName)
                OR (m.seriesName IS NULL AND m.title = :seriesName)
                OR (
                    m.seriesName IS NULL AND m.title IS NULL
                    AND bf.isBookFormat = true
                    AND bf.id = (
                        SELECT MIN(bf2.id) FROM BookFileEntity bf2
                        WHERE bf2.book = b AND bf2.isBookFormat = true
                    )
                    AND bf.fileName = :seriesName
                )
            )
            AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY COALESCE(m.seriesNumber, 0), b.id
            """)
    List<BookEntity> findBooksBySeriesNameUngrouped(@Param("seriesName") String seriesName);

    @Query("""
            SELECT DISTINCT b FROM BookEntity b
            LEFT JOIN FETCH b.metadata m
            LEFT JOIN FETCH m.authors
            LEFT JOIN FETCH m.categories
            WHERE (b.deleted IS NULL OR b.deleted = false)
            AND b.id <> :excludeBookId
            """)
    List<BookEntity> findAllForRecommendation(@Param("excludeBookId") Long excludeBookId);

    @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.categories", "metadata.moods", "metadata.tags", "libraryPath", "library", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE (b.deleted IS NULL OR b.deleted = false)")
    org.springframework.data.domain.Page<BookEntity> findAllWithSummaryMetadataPage(Pageable pageable);

    /**
     * Overrides {@link JpaSpecificationExecutor#findAll(Specification, Pageable)} with an
     * {@link EntityGraph} that eagerly fetches all relationships needed by
     * {@link org.fable.app.mapper.AppBookMapper#toSummary(BookEntity, UserBookProgressEntity)}.
     * Eliminates N+1 lazy-load queries for metadata, authors, categories, moods, tags,
     * shelves, libraryPath, library, and bookFiles during paginated book listing.
     */
    @EntityGraph(attributePaths = {"metadata", "libraryPath", "library"})
    @Override
    Page<BookEntity> findAll(Specification<BookEntity> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "libraryPath", "library"})
    @Query("SELECT b FROM BookEntity b WHERE (b.deleted IS NULL OR b.deleted = false)")
    org.springframework.data.domain.Page<BookEntity> findAllWithMetadataPage(Pageable pageable);

    @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.categories", "metadata.moods", "metadata.tags", "libraryPath", "library", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false)")
    org.springframework.data.domain.Page<BookEntity> findAllWithSummaryMetadataByLibraryIdsPage(@Param("libraryIds") Collection<Long> libraryIds, Pageable pageable);

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "metadata.authors", "metadata.categories", "metadata.moods", "metadata.tags", "libraryPath", "library"})
    @Query("SELECT b FROM BookEntity b WHERE b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false)")
    org.springframework.data.domain.Page<BookEntity> findAllWithMetadataByLibraryIdsPage(@Param("libraryIds") Collection<Long> libraryIds, Pageable pageable);

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata"})
    @Query("SELECT b FROM BookEntity b WHERE (b.deleted IS NULL OR b.deleted = false) ORDER BY b.id")
    List<BookEntity> findAllFullBooksBatch(Pageable pageable);

    @Query("""
            SELECT b.id as bookId, m.embeddingVector as embeddingVector, m.seriesName as seriesName
            FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE (b.deleted IS NULL OR b.deleted = false)
            AND b.id <> :excludeBookId
            AND m.embeddingVector IS NOT NULL
            """)
    List<org.fable.repository.projection.BookEmbeddingProjection> findAllEmbeddingsForRecommendation(@Param("excludeBookId") Long excludeBookId);

    @Query(value = "SELECT DISTINCT be.book_id as bookId, be.embedding_model as embeddingModel FROM book_embeddings be WHERE be.user_id = :userId AND be.book_id IN :bookIds", nativeQuery = true)
    List<org.fable.repository.projection.AiSearchBookStatusProjection> findBookIdsWithAiSearchEmbeddings(@Param("userId") Long userId, @Param("bookIds") Collection<Long> bookIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @jakarta.transaction.Transactional
    @Query(value = "UPDATE book_embeddings SET embedding_model = :model WHERE book_id = :bookId AND user_id = :userId", nativeQuery = true)
    void updateAiSearchEmbeddingModel(@Param("bookId") Long bookId, @Param("userId") Long userId, @Param("model") String model);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @jakarta.transaction.Transactional
    @Query(value = "DELETE FROM book_embeddings WHERE book_id IN :bookIds AND user_id = :userId", nativeQuery = true)
    int deleteBookEmbeddings(@Param("bookIds") Collection<Long> bookIds, @Param("userId") Long userId);

    @Query(value = "SELECT e.embedding_model as model, count(distinct e.book_id) as count FROM book_embeddings e WHERE e.user_id = :userId GROUP BY e.embedding_model", nativeQuery = true)
    List<org.fable.repository.projection.EmbeddingStatsProjection> getEmbeddingStats(@Param("userId") Long userId);

    @Query(value = "SELECT e.embedding_model as model, count(distinct e.book_id) as count FROM book_embeddings e JOIN book b ON e.book_id = b.id WHERE e.user_id = :userId AND b.library_id = :libraryId GROUP BY e.embedding_model", nativeQuery = true)
    List<org.fable.repository.projection.EmbeddingStatsProjection> getEmbeddingStatsByLibraryId(@Param("userId") Long userId, @Param("libraryId") Long libraryId);

    @Query(value = "SELECT COUNT(*) FROM book_embeddings WHERE user_id = :userId", nativeQuery = true)
    long countTotalChunksByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT COUNT(*) FROM book_embeddings e JOIN book b ON e.book_id = b.id WHERE e.user_id = :userId AND b.library_id = :libraryId", nativeQuery = true)
    long countTotalChunksByUserIdAndLibraryId(@Param("userId") Long userId, @Param("libraryId") Long libraryId);

    @Query(value = "SELECT COALESCE(SUM(LENGTH(chunk_text) + LENGTH(embedding_vector)), 0) FROM book_embeddings WHERE user_id = :userId", nativeQuery = true)
    long sumStoredBytesByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT COALESCE(SUM(LENGTH(e.chunk_text) + LENGTH(e.embedding_vector)), 0) FROM book_embeddings e JOIN book b ON e.book_id = b.id WHERE e.user_id = :userId AND b.library_id = :libraryId", nativeQuery = true)
    long sumStoredBytesByUserIdAndLibraryId(@Param("userId") Long userId, @Param("libraryId") Long libraryId);

    @Query("SELECT COUNT(b) FROM BookEntity b WHERE b.markedForAiSearch = true AND (b.deleted IS NULL OR b.deleted = false)")
    long countMarkedForAiSearch();

    @Query("SELECT COUNT(b) FROM BookEntity b WHERE b.markedForAiSearch = true AND b.library.id = :libraryId AND (b.deleted IS NULL OR b.deleted = false)")
    long countMarkedForAiSearchByLibraryId(@Param("libraryId") Long libraryId);

    @Query("SELECT COUNT(b) FROM BookEntity b WHERE (b.deleted IS NULL OR b.deleted = false)")
    long countNonDeleted();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @jakarta.transaction.Transactional
    @Query("UPDATE BookEntity b SET b.markedForAiSearch = :marked WHERE b.id IN :bookIds")
    void updateMarkedForAiSearch(@Param("bookIds") Collection<Long> bookIds, @Param("marked") boolean marked);

    @Query("SELECT b.id FROM BookEntity b WHERE b.markedForAiSearch = true AND (b.deleted IS NULL OR b.deleted = false)")
    List<Long> findBookIdsByMarkedForAiSearchTrue();

    @Query("SELECT b.id as id, m.title as title, l.name as libraryName FROM BookEntity b JOIN b.library l LEFT JOIN b.metadata m WHERE b.markedForAiSearch = true AND (b.deleted IS NULL OR b.deleted = false)")
    List<org.fable.repository.projection.MarkedBookProjection> findMarkedBooksInfo();

    @Query("SELECT COALESCE(m.title, '') FROM BookEntity b LEFT JOIN b.metadata m WHERE b.id = :bookId")
    String findBookTitleById(@Param("bookId") Long bookId);
}
