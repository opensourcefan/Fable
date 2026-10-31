package org.fable.service.book;

import org.fable.model.entity.AuthorEntity;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.BookMetadataEntity;
import org.fable.model.entity.CategoryEntity;
import org.fable.model.entity.MoodEntity;
import org.fable.model.entity.TagEntity;
import org.fable.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/* BookCreatorService has the following functions that are not tested and should be added in the future
- createShellBook()
- saveConnections()
- setComicMetadataDto()
- populateComicMetadataRelationships()
- addCreatorsWithRole()
- truncate()

Gaps in existing coverage:
- addCategoriesToBook, addMoodsToBook, addTagsToBook: missing "not found in repo → save and add" cases (covered for authors only)*/

@ExtendWith(MockitoExtension.class)
class BookCreatorServiceTest {

    @Mock private AuthorRepository authorRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private MoodRepository moodRepository;
    @Mock private TagRepository tagRepository;
    @Mock private BookRepository bookRepository;
    @Mock private BookMetadataRepository bookMetadataRepository;
    @Mock private ComicMetadataRepository comicMetadataRepository;
    @Mock private ComicCharacterRepository comicCharacterRepository;
    @Mock private ComicTeamRepository comicTeamRepository;
    @Mock private ComicLocationRepository comicLocationRepository;
    @Mock private ComicCreatorRepository comicCreatorRepository;
    @Mock private org.fable.service.appsettings.AppSettingService appSettingService;

    @InjectMocks
    private BookCreatorService bookCreatorService;

    private BookEntity bookEntity;

    @BeforeEach
    void setUp() {
        BookMetadataEntity metadata = new BookMetadataEntity();
        bookEntity = BookEntity.builder().metadata(metadata).build();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void addAuthorsToBook_givenNullOrEmptySet_doesNothing(Set<String> authors) {
        bookCreatorService.addAuthorsToBook(authors, bookEntity);

        assertTrue(bookEntity.getMetadata().getAuthors().isEmpty());
        verifyNoInteractions(authorRepository);
    }

    @Test
    void addAuthorsToBook_emptyAuthors_doesNothing() {
        bookCreatorService.addAuthorsToBook(Set.of(), bookEntity);

        assertTrue(bookEntity.getMetadata().getAuthors().isEmpty());
        verifyNoInteractions(authorRepository);
    }

    @Test
    void addAuthorsToBook_validAuthors_addsToBook() {
        AuthorEntity author = AuthorEntity.builder().name("Test Author").build();
        when(authorRepository.findByName("Test Author")).thenReturn(Optional.of(author));

        bookCreatorService.addAuthorsToBook(Set.of("Test Author"), bookEntity);

        assertThat(bookEntity.getMetadata().getAuthors())
                .extracting(AuthorEntity::getName)
                .containsExactly("Test Author");
    }

    @Test
    void addAuthorsToBook_nullExistingAuthorsOnEntity_initializesSet() {
        assertTrue(bookEntity.getMetadata().getAuthors().isEmpty());

        AuthorEntity author = AuthorEntity.builder().name("Author").build();
        when(authorRepository.findByName("Author")).thenReturn(Optional.of(author));

        bookCreatorService.addAuthorsToBook(Set.of("Author"), bookEntity);

        assertNotNull(bookEntity.getMetadata().getAuthors());
        assertTrue(bookEntity.getMetadata().getAuthors().contains(author));
    }

    @Test
    void addCategoriesToBook_nullCategories_doesNothing() {
        bookCreatorService.addCategoriesToBook(null, bookEntity);

        assertTrue(bookEntity.getMetadata().getCategories().isEmpty());
        verifyNoInteractions(categoryRepository);
    }

    @Test
    void addCategoriesToBook_emptyCategories_doesNothing() {
        bookCreatorService.addCategoriesToBook(Set.of(), bookEntity);

        assertTrue(bookEntity.getMetadata().getCategories().isEmpty());
        verifyNoInteractions(categoryRepository);
    }

    @Test
    void addCategoriesToBook_validCategories_addsToBook() {
        CategoryEntity category = CategoryEntity.builder().name("Fiction").build();
        when(categoryRepository.findByName("Fiction")).thenReturn(Optional.of(category));

        bookCreatorService.addCategoriesToBook(Set.of("Fiction"), bookEntity);

        assertThat(bookEntity.getMetadata().getCategories())
                .extracting(CategoryEntity::getName)
                .containsExactly("Fiction");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void addMoodsToBook_givenNullOrEmptySet_doesNothing(Set<String> moods) {
        bookCreatorService.addMoodsToBook(moods, bookEntity);

        assertTrue(bookEntity.getMetadata().getMoods().isEmpty());
        verifyNoInteractions(moodRepository);
    }

    @Test
    void addMoodsToBook_emptyMoods_doesNothing() {
        bookCreatorService.addMoodsToBook(Set.of(), bookEntity);

        assertTrue(bookEntity.getMetadata().getMoods().isEmpty());
        verifyNoInteractions(moodRepository);
    }

    @Test
    void addMoodsToBook_validMoods_addsToBook() {
        MoodEntity mood = MoodEntity.builder().name("Dark").build();
        when(moodRepository.findByName("Dark")).thenReturn(Optional.of(mood));

        bookCreatorService.addMoodsToBook(Set.of("Dark"), bookEntity);

        assertThat(bookEntity.getMetadata().getMoods())
                .extracting(MoodEntity::getName)
                .containsExactly("Dark");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void addTagsToBook_givenNullOrEmptySet_doesNothing(Set<String> tags) {
        bookCreatorService.addTagsToBook(tags, bookEntity);

        assertTrue(bookEntity.getMetadata().getTags().isEmpty());
        verifyNoInteractions(tagRepository);
    }

    @Test
    void addTagsToBook_nullTags_doesNothing() {
        bookCreatorService.addTagsToBook(null, bookEntity);

        assertTrue(bookEntity.getMetadata().getTags().isEmpty());
        verifyNoInteractions(tagRepository);
    }

    @Test
    void addTagsToBook_emptyTags_doesNothing() {
        bookCreatorService.addTagsToBook(Set.of(), bookEntity);

        assertTrue(bookEntity.getMetadata().getTags().isEmpty());
        verifyNoInteractions(tagRepository);
    }

    @Test
    void addTagsToBook_validTags_addsToBook() {
        TagEntity tag = TagEntity.builder().name("favorite").build();
        when(tagRepository.findByName("favorite")).thenReturn(Optional.of(tag));

        bookCreatorService.addTagsToBook(Set.of("favorite"), bookEntity);

        assertThat(bookEntity.getMetadata().getTags())
                .extracting(TagEntity::getName)
                .containsExactly("favorite");
    }

    @Test
    void addAuthorsToBook_existingAuthorsOnEntity_appendsWithoutOverwriting() {
        AuthorEntity existingAuthor = AuthorEntity.builder().name("Existing").build();
        bookEntity.getMetadata().setAuthors(new ArrayList<>(List.of(existingAuthor)));

        AuthorEntity newAuthor = AuthorEntity.builder().name("New Author").build();
        when(authorRepository.findByName("New Author")).thenReturn(Optional.of(newAuthor));

        bookCreatorService.addAuthorsToBook(Set.of("New Author"), bookEntity);

        assertThat(bookEntity.getMetadata().getAuthors())
                .isNotNull()
                .extracting(AuthorEntity::getName)
                .contains("Existing", "New Author");
    }

    @Test
    void addAuthorsToBook_newAuthorNotInRepo_savesAndAdds() {
        AuthorEntity saved = AuthorEntity.builder().name("Brand New").build();
        when(authorRepository.findByName("Brand New")).thenReturn(Optional.empty());
        when(authorRepository.save(any(AuthorEntity.class))).thenReturn(saved);

        bookCreatorService.addAuthorsToBook(Set.of("Brand New"), bookEntity);

        verify(authorRepository).save(argThat(author -> "Brand New".equals(author.getName())));

        assertThat(bookEntity.getMetadata().getAuthors())
                .isNotNull()
                .extracting(AuthorEntity::getName)
                .containsExactly("Brand New");
    }

    @Test
    void addAuthorsToBook_duplicateAuthors_doesNotAddDuplicates() {
        AuthorEntity author = AuthorEntity.builder().id(1L).name("Duplicate Writer").build();
        bookEntity.getMetadata().setAuthors(new ArrayList<>(List.of(author)));
        when(authorRepository.findByName("Duplicate Writer")).thenReturn(Optional.of(author));

        bookCreatorService.addAuthorsToBook(List.of("Duplicate Writer", "Duplicate Writer"), bookEntity);

        assertThat(bookEntity.getMetadata().getAuthors())
                .hasSize(1)
                .extracting(AuthorEntity::getName)
                .containsExactly("Duplicate Writer");
    }

    @Test
    void createShellBook_existingTemporarilyDeletedBook_restoresAndClearsDeletedAt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path file = tempDir.resolve("comic.cbz");
        java.nio.file.Files.writeString(file, "content");

        org.fable.model.entity.LibraryEntity lib = new org.fable.model.entity.LibraryEntity();
        lib.setId(1L);
        org.fable.model.entity.LibraryPathEntity libPath = new org.fable.model.entity.LibraryPathEntity();
        libPath.setId(2L);
        libPath.setPath(tempDir.toString());

        org.fable.model.dto.settings.LibraryFile libraryFile = org.fable.model.dto.settings.LibraryFile.builder()
                .libraryEntity(lib)
                .libraryPathEntity(libPath)
                .fileSubPath("")
                .fileName("comic.cbz")
                .bookFileType(org.fable.model.enums.BookFileType.CBX)
                .build();

        BookEntity existing = new BookEntity();
        existing.setId(50L);
        existing.setDeleted(true);
        existing.setDeletedAt(java.time.Instant.now());
        existing.setRemovedFromLibrary(false);
        org.fable.model.entity.BookFileEntity bf = new org.fable.model.entity.BookFileEntity();
        bf.setBook(existing);
        existing.setBookFiles(new ArrayList<>(List.of(bf)));

        when(bookRepository.findByLibraryIdAndLibraryPathIdAndFileSubPathAndFileName(1L, 2L, "", "comic.cbz"))
                .thenReturn(Optional.of(existing));

        BookEntity result = bookCreatorService.createShellBook(libraryFile, org.fable.model.enums.BookFileType.CBX);

        assertThat(result.getDeleted()).isFalse();
        assertThat(result.getDeletedAt()).isNull();
        assertThat(result.getAddedOn()).isNotNull();
    }

    @Test
    void createShellBook_existingPermanentlyRemovedBook_keepsDeletedTrue(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path file = tempDir.resolve("removed.cbz");
        java.nio.file.Files.writeString(file, "content");

        org.fable.model.entity.LibraryEntity lib = new org.fable.model.entity.LibraryEntity();
        lib.setId(1L);
        org.fable.model.entity.LibraryPathEntity libPath = new org.fable.model.entity.LibraryPathEntity();
        libPath.setId(2L);
        libPath.setPath(tempDir.toString());

        org.fable.model.dto.settings.LibraryFile libraryFile = org.fable.model.dto.settings.LibraryFile.builder()
                .libraryEntity(lib)
                .libraryPathEntity(libPath)
                .fileSubPath("")
                .fileName("removed.cbz")
                .bookFileType(org.fable.model.enums.BookFileType.CBX)
                .build();

        java.time.Instant deletedTime = java.time.Instant.now();
        BookEntity existing = new BookEntity();
        existing.setId(51L);
        existing.setDeleted(true);
        existing.setDeletedAt(deletedTime);
        existing.setRemovedFromLibrary(true);
        org.fable.model.entity.BookFileEntity bf = new org.fable.model.entity.BookFileEntity();
        bf.setBook(existing);
        existing.setBookFiles(new ArrayList<>(List.of(bf)));

        when(bookRepository.findByLibraryIdAndLibraryPathIdAndFileSubPathAndFileName(1L, 2L, "", "removed.cbz"))
                .thenReturn(Optional.of(existing));

        BookEntity result = bookCreatorService.createShellBook(libraryFile, org.fable.model.enums.BookFileType.CBX);

        assertThat(result.getDeleted()).isTrue();
        assertThat(result.getRemovedFromLibrary()).isTrue();
        assertThat(result.getDeletedAt()).isEqualTo(deletedTime);
    }
}
