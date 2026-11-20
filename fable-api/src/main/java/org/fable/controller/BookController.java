package org.fable.controller;

import org.fable.config.security.annotation.CheckBookAccess;
import org.fable.exception.ApiError;
import org.fable.app.dto.AppBookGridSummary;
import org.fable.app.dto.AppPageResponse;
import org.fable.model.dto.Book;
import org.fable.model.dto.BookRecommendation;
import org.fable.model.dto.BookViewerSettings;
import org.fable.model.dto.request.AttachBookFileRequest;
import org.fable.model.dto.request.CreatePhysicalBookRequest;
import org.fable.model.dto.request.DuplicateDetectionRequest;
import org.fable.model.dto.request.FileTypeAssignmentRequest;
import org.fable.model.dto.request.PersonalRatingUpdateRequest;
import org.fable.model.dto.request.ReadProgressRequest;
import org.fable.model.dto.request.ReadStatusUpdateRequest;
import org.fable.model.dto.request.ShelvesAssignmentRequest;
import org.fable.model.dto.request.StagingReleaseRequest;
import org.fable.model.dto.response.AttachBookFileResponse;
import org.fable.model.dto.response.BookDeletionResponse;
import org.fable.model.dto.response.BookStatusUpdateResponse;
import org.fable.model.dto.response.DuplicateGroup;
import org.fable.model.dto.response.PersonalRatingUpdateResponse;
import org.fable.model.enums.RemoveFromLibraryMode;
import org.fable.model.enums.ResetProgressType;
import org.fable.service.book.BookFileAttachmentService;
import org.fable.service.book.BookService;
import org.fable.service.book.BookUpdateService;
import org.fable.service.book.DuplicateDetectionService;
import org.fable.service.book.PhysicalBookService;
import org.fable.service.metadata.BookMetadataService;
import org.fable.service.progress.ReadingProgressService;
import org.fable.service.recommender.BookRecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@Tag(name = "Books", description = "Endpoints for managing books, their metadata, progress, and recommendations")
@RequestMapping("/api/v1/books")
@Validated
@RestController
@AllArgsConstructor
public class BookController {

    private final BookService bookService;
    private final BookUpdateService bookUpdateService;
    private final BookRecommendationService bookRecommendationService;
    private final BookFileAttachmentService bookFileAttachmentService;
    private final BookMetadataService bookMetadataService;
    private final ReadingProgressService readingProgressService;
    private final PhysicalBookService physicalBookService;
    private final DuplicateDetectionService duplicateDetectionService;

    @Operation(summary = "Get all books", description = "Retrieve a list of all books. Optionally include descriptions.")
    @ApiResponse(responseCode = "200", description = "List of books returned successfully")
    @GetMapping
    public ResponseEntity<List<Book>> getBooks(
            @Parameter(description = "Include book descriptions in the response")
            @RequestParam(required = false, defaultValue = "false") boolean withDescription,
            @Parameter(description = "Remove other metadata fields from the response")
            @RequestParam(required = false, defaultValue = "true") boolean stripForListView) {
        return ResponseEntity.ok(bookService.getBookDTOs(withDescription, stripForListView));
    }

    @Operation(summary = "Get books with pagination", description = "Retrieve a paginated list of books. Supports multi-field sorting, text search, and server-side filters.")
    @ApiResponse(responseCode = "200", description = "Paginated list of books returned successfully")
    @GetMapping("/paged")
    public ResponseEntity<AppPageResponse<AppBookGridSummary>> getBooksPaged(
            @Parameter(description = "Page number (zero-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "50") int size,
            @Parameter(description = "Sort fields as field,direction pairs (e.g. sorts=title,asc&sorts=addedOn,desc)") @RequestParam(required = false) List<String> sorts,
            @Parameter(description = "Single sort field (backward-compatible)") @RequestParam(required = false) String sort,
            @Parameter(description = "Single sort direction (backward-compatible, asc/desc)") @RequestParam(required = false) String dir,
            @Parameter(description = "Optional library ID filter") @RequestParam(required = false) Long libraryId,
            @Parameter(description = "Optional shelf ID filter") @RequestParam(required = false) Long shelfId,
            @Parameter(description = "Return only books without shelves owned by the current user") @RequestParam(required = false, defaultValue = "false") boolean unshelved,
            @Parameter(description = "Return only books currently in staging for metadata review") @RequestParam(required = false, defaultValue = "false") boolean staged,
            @Parameter(description = "Filter by custom media type labels") @RequestParam(required = false) List<String> mediaTypes,
            @Parameter(description = "Text search across normalized metadata plus file names") @RequestParam(required = false) String search,
            @Parameter(description = "Filter by authors (comma-separated, OR within, AND/OR between categories depending on filterMode)") @RequestParam(required = false) List<String> authors,
            @Parameter(description = "Filter by categories (comma-separated)") @RequestParam(required = false) List<String> categories,
            @Parameter(description = "Filter by series name") @RequestParam(required = false) String series,
            @Parameter(description = "Filter by publisher") @RequestParam(required = false) String publisher,
            @Parameter(description = "Filter by language code") @RequestParam(required = false) String language,
            @Parameter(description = "Filter by ISBN (10 or 13)") @RequestParam(required = false) String isbn,
            @Parameter(description = "Filter by read status") @RequestParam(required = false) String readStatus,
            @Parameter(description = "Filter by book type (ePub, PDF, CBZ, AUDIOBOOK, PHYSICAL)") @RequestParam(required = false) String bookType,
            @Parameter(description = "Filter by content rating") @RequestParam(required = false) String contentRating,
            @Parameter(description = "Filter: AND (all categories must match) or OR (any category matches)") @RequestParam(defaultValue = "and") String filterMode) {
        return ResponseEntity.ok(bookService.getBooksPaged(page, size, sorts, sort, dir, libraryId,
            shelfId, unshelved, staged, mediaTypes, search, authors, categories, series, publisher, language, isbn,
                readStatus, bookType, contentRating, filterMode));
    }

    @Operation(summary = "Get a book by ID", description = "Retrieve details of a specific book by its ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Book details returned successfully"),
            @ApiResponse(responseCode = "404", description = "Book not found")
    })
    @GetMapping("/{bookId}")
    @CheckBookAccess(bookIdParam = "bookId")
    public ResponseEntity<Book> getBook(
            @Parameter(description = "ID of the book to retrieve") @PathVariable long bookId,
            @Parameter(description = "Include book description in the response") @RequestParam(required = false, defaultValue = "false") boolean withDescription) {
        return ResponseEntity.ok(bookService.getBook(bookId, withDescription));
    }

    @Operation(summary = "Create a physical book", description = "Create a physical book without digital files. Requires library management permission or admin.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Physical book created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "403", description = "Forbidden"),
            @ApiResponse(responseCode = "404", description = "Library not found")
    })
    @PostMapping("/physical")
    @PreAuthorize("@securityUtil.canManageLibrary() or @securityUtil.isAdmin()")
    public ResponseEntity<Book> createPhysicalBook(
            @Parameter(description = "Physical book creation request") @RequestBody @Valid CreatePhysicalBookRequest request) {
        return ResponseEntity.status(201).body(physicalBookService.createPhysicalBook(request));
    }

    @Operation(summary = "Delete books", description = "Delete one or more books by their IDs. Requires admin or delete permission.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Books deleted successfully"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    @PreAuthorize("@securityUtil.canDeleteBook() or @securityUtil.isAdmin()")
    @DeleteMapping
    public ResponseEntity<BookDeletionResponse> deleteBooks(
            @Parameter(description = "Set of book IDs to delete") @RequestParam Set<Long> ids,
            @Parameter(description = "Whether to also delete the physical files from disk") @RequestParam(defaultValue = "true") boolean deleteFromDisk,
            @Parameter(description = "When deleteFromDisk is false, controls whether removal is permanent or only until next library scan")
            @RequestParam(defaultValue = "REMOVE_FOREVER") RemoveFromLibraryMode removeMode) {
        return bookService.deleteBooks(ids, deleteFromDisk, removeMode);
    }

    @Operation(summary = "Get books by IDs", description = "Retrieve multiple books by their IDs. Optionally include descriptions.")
    @ApiResponse(responseCode = "200", description = "Books returned successfully")
    @GetMapping("/batch")
    public ResponseEntity<List<Book>> getBooksByIds(
            @Parameter(description = "Set of book IDs to retrieve") @RequestParam Set<Long> ids,
            @Parameter(description = "Include book descriptions in the response") @RequestParam(required = false, defaultValue = "false") boolean withDescription) {
        return ResponseEntity.ok(bookService.getBooksByIds(ids, withDescription));
    }

    @Operation(summary = "Get ComicInfo metadata", description = "Retrieve ComicInfo metadata for a specific book.")
    @ApiResponse(responseCode = "200", description = "ComicInfo metadata returned successfully")
    @GetMapping("/{bookId}/cbx/metadata/comicinfo")
    public ResponseEntity<?> getComicInfoMetadata(
            @Parameter(description = "ID of the book") @PathVariable long bookId) {
        return ResponseEntity.ok(bookMetadataService.getComicInfoMetadata(bookId));
    }

    @Operation(summary = "Get file metadata", description = "Extract embedded metadata from the book file.")
    @ApiResponse(responseCode = "200", description = "File metadata returned successfully")
    @GetMapping("/{bookId}/file-metadata")
    public ResponseEntity<?> getFileMetadata(
            @Parameter(description = "ID of the book") @PathVariable long bookId) {
        return ResponseEntity.ok(bookMetadataService.getFileMetadata(bookId));
    }

    @Operation(summary = "Get book content", description = "Retrieve the binary content of a book for reading. Supports HTTP Range requests for partial content streaming.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Full book content returned"),
            @ApiResponse(responseCode = "206", description = "Partial content returned for Range request")
    })
    @GetMapping("/{bookId}/content")
    @CheckBookAccess(bookIdParam = "bookId")
    public void getBookContent(
            @Parameter(description = "ID of the book") @PathVariable long bookId,
            @Parameter(description = "Optional book type for alternative format (e.g., EPUB, PDF, MOBI)") @RequestParam(required = false) String bookType,
            HttpServletRequest request,
            HttpServletResponse response) throws java.io.IOException {
        bookService.streamBookContent(bookId, bookType, request, response);
    }

    @Operation(summary = "Download book", description = "Download the book file. Requires download permission or admin.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Book downloaded successfully"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    @GetMapping("/{bookId}/download")
    @PreAuthorize("@securityUtil.canDownload() or @securityUtil.isAdmin()")
    @CheckBookAccess(bookIdParam = "bookId")
    public ResponseEntity<Resource> downloadBook(@Parameter(description = "ID of the book to download") @PathVariable("bookId") Long bookId) {
        return bookService.downloadBook(bookId);
    }

    @Operation(summary = "Download all book files", description = "Download all files for a book as a ZIP archive. For single-file books, downloads the file directly. Requires download permission or admin.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Files downloaded successfully"),
            @ApiResponse(responseCode = "403", description = "Forbidden"),
            @ApiResponse(responseCode = "404", description = "Book not found")
    })
    @GetMapping("/{bookId}/download-all")
    @PreAuthorize("@securityUtil.canDownload() or @securityUtil.isAdmin()")
    @CheckBookAccess(bookIdParam = "bookId")
    public void downloadAllBookFiles(
            @Parameter(description = "ID of the book") @PathVariable("bookId") Long bookId,
            HttpServletResponse response) {
        bookService.downloadAllBookFiles(bookId, response);
    }

    @Operation(summary = "Get viewer settings", description = "Retrieve viewer settings for a specific book file.")
    @ApiResponse(responseCode = "200", description = "Viewer settings returned successfully")
    @GetMapping("/{bookId}/viewer-setting")
    @CheckBookAccess(bookIdParam = "bookId")
    public ResponseEntity<BookViewerSettings> getBookViewerSettings(
            @Parameter(description = "ID of the book") @PathVariable long bookId,
            @Parameter(description = "ID of the book file") @RequestParam long bookFileId) {
        return ResponseEntity.ok(bookService.getBookViewerSetting(bookId, bookFileId));
    }

    @Operation(summary = "Update viewer settings", description = "Update viewer settings for a specific book.")
    @ApiResponse(responseCode = "204", description = "Viewer settings updated successfully")
    @PutMapping("/{bookId}/viewer-setting")
    @CheckBookAccess(bookIdParam = "bookId")
    public ResponseEntity<Void> updateBookViewerSettings(
            @Parameter(description = "Viewer settings to update") @RequestBody @Valid BookViewerSettings bookViewerSettings,
            @Parameter(description = "ID of the book") @PathVariable long bookId) {
        bookService.updateBookViewerSetting(bookId, bookViewerSettings);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Assign books to shelves", description = "Assign or unassign books to/from shelves.")
    @ApiResponse(responseCode = "200", description = "Books assigned/unassigned to shelves successfully")
    @PostMapping("/shelves")
    public ResponseEntity<List<Book>> addBookToShelf(
            @Parameter(description = "Shelves assignment request") @RequestBody @Valid ShelvesAssignmentRequest request) {
        return ResponseEntity.ok(bookService.assignShelvesToBooks(request.getBookIds(), request.getShelvesToAssign(), request.getShelvesToUnassign()));
    }

    @Operation(summary = "Release books from staging", description = "Clear the staging flag on books after metadata review. Does not change library or shelf membership.")
    @ApiResponse(responseCode = "200", description = "Books released from staging successfully")
    @PostMapping("/staging/release")
    @PreAuthorize("@securityUtil.canEditMetadata() or @securityUtil.isAdmin()")
    public ResponseEntity<List<Book>> releaseFromStaging(
            @Parameter(description = "Staging release request") @RequestBody @Valid StagingReleaseRequest request) {
        return ResponseEntity.ok(bookService.releaseFromStaging(request.getBookIds()));
    }

    @Operation(summary = "Stage books for review", description = "Move one or more books back into the Staging queue for metadata review.")
    @ApiResponse(responseCode = "200", description = "Books staged successfully")
    @PostMapping("/staging/stage")
    @PreAuthorize("@securityUtil.canEditMetadata() or @securityUtil.isAdmin()")
    public ResponseEntity<List<Book>> stageForReview(
            @Parameter(description = "Staging request") @RequestBody @Valid StagingReleaseRequest request) {
        return ResponseEntity.ok(bookService.stageForReview(request.getBookIds()));
    }

    @Operation(summary = "Assign file type to books", description = "Assign a custom file type to one or more books.")
    @ApiResponse(responseCode = "200", description = "File types assigned successfully")
    @PostMapping("/file-types")
    public ResponseEntity<List<Book>> assignFileType(
            @Parameter(description = "File type assignment request") @RequestBody @Valid FileTypeAssignmentRequest request) {
        return ResponseEntity.ok(bookService.assignFileTypeToBooks(request.getBookIds(), request.getFileType()));
    }

    @Operation(summary = "Update read progress", description = "Update the read progress for a book.")
    @ApiResponse(responseCode = "204", description = "Read progress updated successfully")
    @PostMapping("/progress")
    public ResponseEntity<Void> addBookToProgress(
            @Parameter(description = "Read progress request") @RequestBody @Valid ReadProgressRequest request) {
        bookService.updateReadProgress(request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get book recommendations", description = "Get recommended books based on a specific book.")
    @ApiResponse(responseCode = "200", description = "Recommendations returned successfully")
    @GetMapping("/{id}/recommendations")
    @CheckBookAccess(bookIdParam = "id")
    public ResponseEntity<List<BookRecommendation>> getRecommendations(
            @Parameter(description = "ID of the book for recommendations") @PathVariable Long id,
            @Parameter(description = "Maximum number of recommendations to return (max 25)") @RequestParam(defaultValue = "25") @Max(25) @Min(1) int limit) {
        return ResponseEntity.ok(bookRecommendationService.getRecommendations(id, limit));
    }

    @Operation(summary = "Update read status", description = "Update the read status for one or more books.")
    @ApiResponse(responseCode = "200", description = "Read status updated successfully")
    @PostMapping("/status")
    public List<BookStatusUpdateResponse> updateReadStatus(@RequestBody @Valid ReadStatusUpdateRequest request) {
        return bookService.updateReadStatus(request.getBookIds(), request.getStatus());
    }

    @Operation(summary = "Reset reading progress", description = "Reset the reading progress for one or more books.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Progress reset successfully"),
            @ApiResponse(responseCode = "400", description = "No book IDs provided")
    })
    @PostMapping("/reset-progress")
    public ResponseEntity<List<BookStatusUpdateResponse>> resetProgress(
            @Parameter(description = "List of book IDs to reset progress for") @RequestBody @Size(max = 500) List<Long> bookIds,
            @Parameter(description = "Type of progress reset") @RequestParam ResetProgressType type) {
        if (bookIds == null || bookIds.isEmpty()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("No book IDs provided");
        }
        return ResponseEntity.ok(readingProgressService.resetProgress(bookIds, type));
    }

    @Operation(summary = "Update personal rating", description = "Update the personal rating for one or more books.")
    @ApiResponse(responseCode = "200", description = "Personal rating updated successfully")
    @PutMapping("/personal-rating")
    public ResponseEntity<List<PersonalRatingUpdateResponse>> updatePersonalRating(
            @Parameter(description = "Personal rating update request") @RequestBody @Valid PersonalRatingUpdateRequest request) {
        return ResponseEntity.ok(bookUpdateService.updatePersonalRating(request.ids(), request.rating()));
    }

    @Operation(summary = "Reset personal rating", description = "Reset the personal rating for one or more books.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Personal rating reset successfully"),
            @ApiResponse(responseCode = "400", description = "No book IDs provided")
    })
    @PostMapping("/reset-personal-rating")
    public ResponseEntity<List<PersonalRatingUpdateResponse>> resetPersonalRating(
            @Parameter(description = "List of book IDs to reset personal rating for") @RequestBody @Size(max = 500) List<Long> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("No book IDs provided");
        }
        List<PersonalRatingUpdateResponse> updatedBooks = bookUpdateService.resetPersonalRating(bookIds);
        return ResponseEntity.ok(updatedBooks);
    }

    @Operation(summary = "Find duplicate books", description = "Detect potential duplicate books in a library using configurable matching strategies.")
    @ApiResponse(responseCode = "200", description = "Duplicate groups returned successfully")
    @PostMapping("/duplicates")
    @PreAuthorize("@securityUtil.canManageLibrary() or @securityUtil.isAdmin()")
    public ResponseEntity<List<DuplicateGroup>> findDuplicates(
            @Parameter(description = "Duplicate detection configuration") @RequestBody @Valid DuplicateDetectionRequest request) {
        return ResponseEntity.ok(duplicateDetectionService.findDuplicates(request));
    }

    @Operation(summary = "Toggle physical book flag", description = "Mark or unmark a book as a physical book.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Physical flag updated successfully"),
            @ApiResponse(responseCode = "404", description = "Book not found")
    })
    @PatchMapping("/{bookId}/physical")
    @CheckBookAccess(bookIdParam = "bookId")
    @PreAuthorize("@securityUtil.canManageLibrary() or @securityUtil.isAdmin()")
    public ResponseEntity<Book> togglePhysicalFlag(
            @Parameter(description = "ID of the book") @PathVariable long bookId,
            @Parameter(description = "Whether the book is physical") @RequestParam boolean physical) {
        return ResponseEntity.ok(physicalBookService.togglePhysicalFlag(bookId, physical));
    }

    @Operation(summary = "Update currently reading status", description = "Mark or unmark a book as currently reading.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Currently reading status updated successfully"),
            @ApiResponse(responseCode = "404", description = "Book not found")
    })
    @PatchMapping("/{bookId}")
    @CheckBookAccess(bookIdParam = "bookId")
    public ResponseEntity<Book> updateCurrentlyReadingStatus(
            @Parameter(description = "ID of the book") @PathVariable long bookId,
            @Parameter(description = "Whether the book is currently reading") @RequestParam boolean isCurrentlyReading) {
        return ResponseEntity.ok(bookService.updateCurrentlyReadingStatus(bookId, isCurrentlyReading));
    }

    @Operation(summary = "Attach book files", description = "Attach book files from single-file source books to a target book as alternative formats.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Book files attached successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request - books must be in same library, sources must have exactly one file each"),
            @ApiResponse(responseCode = "403", description = "Forbidden - requires library management permission"),
            @ApiResponse(responseCode = "404", description = "Book not found")
    })
    @PostMapping("/{targetBookId}/attach-file")
    @PreAuthorize("@securityUtil.canManageLibrary() or @securityUtil.isAdmin()")
    public ResponseEntity<AttachBookFileResponse> attachBookFiles(
            @Parameter(description = "ID of the target book to attach the files to") @PathVariable Long targetBookId,
            @Parameter(description = "Request containing source book IDs and delete option") @RequestBody @Valid AttachBookFileRequest request) {
        return ResponseEntity.ok(bookFileAttachmentService.attachBookFiles(targetBookId, request.getSourceBookIds(), request.isMoveFiles()));
    }
}
