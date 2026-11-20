package org.fable.repository;

import jakarta.persistence.EntityManager;
import org.fable.FableApplication;
import org.fable.config.BookmarkProperties;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.BookFileEntity;
import org.fable.model.entity.BookMetadataEntity;
import org.fable.model.entity.ComicMetadataEntity;
import org.fable.model.entity.LibraryEntity;
import org.fable.model.entity.LibraryPathEntity;
import org.fable.model.enums.BookFileType;
import org.fable.model.enums.FetchedMetadataProposalStatus;
import org.hibernate.Hibernate;
import org.fable.service.task.TaskCronService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = {
                FableApplication.class,
                BookRepositorySummaryMetadataDataJpaTest.TestConfig.class,
})
@Transactional
@TestPropertySource(properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "spring.datasource.url=jdbc:h2:mem:summary_graph_test;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                                "spring.datasource.password=",
                "app.path-config=build/tmp/test-config-summary",
                "app.bookdrop-folder=build/tmp/test-bookdrop-summary",
                "spring.main.allow-bean-definition-overriding=true",
                "spring.task.scheduling.enabled=false",
                "app.task.scan-library-cron=*/1 * * * * *",
                "app.task.process-bookdrop-cron=*/1 * * * * *",
                "app.features.oidc-enabled=false"
})
@Import(BookRepositorySummaryMetadataDataJpaTest.TestConfig.class)
class BookRepositorySummaryMetadataDataJpaTest {

        @TestConfiguration
        static class TestConfig {

                @Bean
                @Primary
                RestTemplate testRestTemplate() {
                        return new RestTemplate();
                }

                @Bean
                @Primary
                BookmarkProperties bookmarkProperties() {
                        return new BookmarkProperties();
                }

                @Bean("flyway")
                @Primary
                org.flywaydb.core.Flyway flyway() {
                        return mock(org.flywaydb.core.Flyway.class);
                }

                @Bean
                @Primary
                TaskCronService taskCronService() {
                        return mock(TaskCronService.class);
                }
        }

    @Autowired
        private EntityManager entityManager;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private MetadataFetchProposalRepository proposalRepository;

    @Test
    void findAllWithSummaryMetadataByIds_keepsDescriptionAndComicMetadataLazy() {
        long bookId = persistBookWithDescriptionAndComicMetadata();

        entityManager.flush();
        entityManager.clear();

        BookEntity book = bookRepository.findAllWithSummaryMetadataByIds(Set.of(bookId)).getFirst();

        assertThat(book.getMetadata().getTitle()).isEqualTo("Summary Title");
        assertThat(Hibernate.isPropertyInitialized(book.getMetadata(), "description")).isFalse();
        assertThat(Hibernate.isPropertyInitialized(book.getMetadata(), "comicMetadata")).isFalse();

        assertThat(book.getMetadata().getDescription()).isEqualTo("A very long description that should stay lazy in summary queries.");
        assertThat(Hibernate.isPropertyInitialized(book.getMetadata(), "description")).isTrue();
    }

    @Test
    void updateStatusByBookIdsAndStatus_doesNotDetachLoadedBookMetadataDescription() {
        long bookId = persistBookWithDescriptionAndComicMetadata();

        entityManager.flush();
        entityManager.clear();

        BookEntity book = bookRepository.findAllWithMetadataByIds(Set.of(bookId)).getFirst();
        book.setStaged(false);
        book.setStagingCompletedAt(null);
        bookRepository.saveAll(List.of(book));

        proposalRepository.updateStatusByBookIdsAndStatus(
                Set.of(bookId),
                FetchedMetadataProposalStatus.FETCHED,
                FetchedMetadataProposalStatus.REJECTED,
                Instant.now(),
                1L
        );

        assertThat(entityManager.contains(book)).isTrue();
        assertThat(book.getMetadata().getDescription())
                .isEqualTo("A very long description that should stay lazy in summary queries.");
    }

    private long persistBookWithDescriptionAndComicMetadata() {
        LibraryEntity library = LibraryEntity.builder()
                .name("Summary Graph Library")
                .watch(false)
                .formatPriority(List.of(BookFileType.CBX, BookFileType.EPUB))
                .build();
        entityManager.persist(library);

        LibraryPathEntity libraryPath = LibraryPathEntity.builder()
                .library(library)
                .path("/tmp/summary-graph")
                .build();
        entityManager.persist(libraryPath);

        BookEntity book = BookEntity.builder()
                .library(library)
                .libraryPath(libraryPath)
                .addedOn(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        entityManager.persist(book);

        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .bookId(book.getId())
                .book(book)
                .title("Summary Title")
                .description("A very long description that should stay lazy in summary queries.")
                .build();
        entityManager.persist(metadata);
        book.setMetadata(metadata);

        ComicMetadataEntity comicMetadata = ComicMetadataEntity.builder()
                .bookId(book.getId())
                .bookMetadata(metadata)
                .issueNumber("12")
                .build();
        entityManager.persist(comicMetadata);
        metadata.setComicMetadata(comicMetadata);

        BookFileEntity primaryFile = BookFileEntity.builder()
                .book(book)
                .fileName("summary-title.cbz")
                .fileSubPath("")
                .isBookFormat(true)
                .bookType(BookFileType.CBX)
                .addedOn(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        entityManager.persist(primaryFile);
        book.setBookFiles(List.of(primaryFile));

        library.setLibraryPaths(List.of(libraryPath));
        return book.getId();
    }
}