package org.fable.service.metadata;

import org.fable.exception.APIException;
import org.fable.mapper.BookMapper;
import org.fable.mapper.BookMetadataMapper;
import org.fable.mapper.MetadataClearFlagsMapper;
import org.fable.model.MetadataClearFlags;
import org.fable.model.MetadataUpdateWrapper;
import org.fable.model.dto.Book;
import org.fable.model.dto.BookMetadata;
import org.fable.model.dto.request.BulkMetadataUpdateRequest;
import org.fable.model.dto.request.FetchMetadataRequest;
import org.fable.model.dto.request.IsbnLookupRequest;
import org.fable.model.dto.request.MetadataRefreshOptions;
import org.fable.model.dto.request.ToggleAllLockRequest;
import org.fable.model.dto.settings.AppSettings;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.BookFileEntity;
import org.fable.model.entity.BookMetadataEntity;
import org.fable.model.enums.BookFileType;
import org.fable.model.enums.Lock;
import org.fable.model.enums.MetadataProvider;
import org.fable.repository.BookMetadataRepository;
import org.fable.repository.BookRepository;
import org.fable.service.NotificationService;
import org.fable.service.appsettings.AppSettingService;
import org.fable.service.book.BookCreatorService;
import org.fable.service.book.BookQueryService;
import org.fable.service.metadata.extractor.CbxMetadataExtractor;
import org.fable.service.metadata.extractor.MetadataExtractorFactory;
import org.fable.service.metadata.parser.BookParser;
import org.fable.service.metadata.parser.DetailedMetadataProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookMetadataServiceTest {

    @Mock private BookRepository bookRepository;
    @Mock private BookMapper bookMapper;
    @Mock private BookMetadataMapper bookMetadataMapper;
    @Mock private BookMetadataUpdater bookMetadataUpdater;
    @Mock private NotificationService notificationService;
    @Mock private BookMetadataRepository bookMetadataRepository;
    @Mock private BookQueryService bookQueryService;
    @Mock private CbxMetadataExtractor cbxMetadataExtractor;
    @Mock private MetadataExtractorFactory metadataExtractorFactory;
    @Mock private MetadataClearFlagsMapper metadataClearFlagsMapper;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private AppSettingService appSettingService;
    @Mock private BookCreatorService bookCreatorService;

    private Map<MetadataProvider, BookParser> parserMap;
    private BookMetadataService service;

    @BeforeEach
    void setUp() {
        parserMap = new HashMap<>();
        service = new BookMetadataService(
                bookRepository, bookMapper, bookMetadataMapper, bookMetadataUpdater,
                notificationService, bookMetadataRepository, bookQueryService,
                parserMap, cbxMetadataExtractor, metadataExtractorFactory,
                metadataClearFlagsMapper, transactionManager, appSettingService,
                bookCreatorService
        );
    }

    @Nested
    class FetchMetadataListFromAProvider {

        @Test
        void delegatesToParser() {
            BookParser parser = mock(BookParser.class);
            parserMap.put(MetadataProvider.Google, parser);
            Book book = Book.builder().build();
            FetchMetadataRequest request = FetchMetadataRequest.builder().build();
            List<BookMetadata> expected = List.of(BookMetadata.builder().title("Test").build());
                        when(parser.fetchMetadata(eq(book), any())).thenReturn(expected);

            List<BookMetadata> result = service.fetchMetadataListFromAProvider(MetadataProvider.Google, book, request);

            assertThat(result).isEqualTo(expected);
        }

                @Test
                void normalizesComicvineSpacedDashWithoutMutatingOriginalRequest() {
                        BookParser parser = mock(BookParser.class);
                        parserMap.put(MetadataProvider.Comicvine, parser);
                        Book book = Book.builder().build();
                        FetchMetadataRequest request = FetchMetadataRequest.builder()
                                        .title("comicvine - test")
                                        .build();
                        when(parser.fetchMetadata(eq(book), any())).thenReturn(List.of());

                        service.fetchMetadataListFromAProvider(MetadataProvider.Comicvine, book, request);

                        verify(parser).fetchMetadata(eq(book), argThat(cleanedRequest ->
                                        "comicvine test".equals(cleanedRequest.getTitle())
                                                        && cleanedRequest.getAuthor() == null));
                        assertThat(request.getTitle()).isEqualTo("comicvine - test");
                        assertThat(request.getAuthor()).isNull();
                }

        @Test
        void throwsWhenProviderNotInMap() {
            assertThatThrownBy(() -> service.fetchMetadataListFromAProvider(MetadataProvider.Amazon, Book.builder().build(), FetchMetadataRequest.builder().build()))
                    .isInstanceOf(APIException.class);
        }
    }

    @Nested
    class GetDetailedProviderMetadata {

        @Test
        void returnsDetailedMetadataWhenParserImplementsDetailedProvider() {
            DetailedBookParser parser = mock(DetailedBookParser.class);
            parserMap.put(MetadataProvider.Google, parser);
            BookMetadata expected = BookMetadata.builder().title("Detailed").build();
            when(parser.fetchDetailedMetadata("item-123")).thenReturn(expected);

            BookMetadata result = service.getDetailedProviderMetadata(MetadataProvider.Google, "item-123");

            assertThat(result).isEqualTo(expected);
        }

        @Test
        void returnsNullWhenParserDoesNotImplementDetailedProvider() {
            BookParser parser = mock(BookParser.class);
            parserMap.put(MetadataProvider.Google, parser);

            BookMetadata result = service.getDetailedProviderMetadata(MetadataProvider.Google, "item-123");

            assertThat(result).isNull();
        }

        @Test
        void throwsWhenProviderNotFound() {
            assertThatThrownBy(() -> service.getDetailedProviderMetadata(MetadataProvider.Amazon, "item-123"))
                    .isInstanceOf(APIException.class);
        }

        interface DetailedBookParser extends BookParser, DetailedMetadataProvider {}
    }

    @Nested
    class LookupByIsbn {

        @Test
        void returnsFirstResultFromFirstSuccessfulProvider() {
            BookParser googleParser = mock(BookParser.class);
            parserMap.put(MetadataProvider.Google, googleParser);

            AppSettings settings = AppSettings.builder()
                    .defaultMetadataRefreshOptions(MetadataRefreshOptions.builder()
                            .fieldOptions(MetadataRefreshOptions.FieldOptions.builder()
                                    .title(MetadataRefreshOptions.FieldProvider.builder()
                                            .p1(MetadataProvider.Google)
                                            .build())
                                    .build())
                            .build())
                    .build();
            when(appSettingService.getAppSettings()).thenReturn(settings);

            BookMetadata expected = BookMetadata.builder().title("Found").build();
            when(googleParser.fetchMetadata(any(Book.class), any(FetchMetadataRequest.class)))
                    .thenReturn(List.of(expected));

            BookMetadata result = service.lookupByIsbn(IsbnLookupRequest.builder().isbn("978-0123456789").build());

            assertThat(result).isEqualTo(expected);
        }

        @Test
        void skipsFailingProviderAndTriesNext() {
            BookParser googleParser = mock(BookParser.class);
            BookParser amazonParser = mock(BookParser.class);
            parserMap.put(MetadataProvider.Google, googleParser);
            parserMap.put(MetadataProvider.Amazon, amazonParser);

            AppSettings settings = AppSettings.builder()
                    .defaultMetadataRefreshOptions(MetadataRefreshOptions.builder()
                            .fieldOptions(MetadataRefreshOptions.FieldOptions.builder()
                                    .title(MetadataRefreshOptions.FieldProvider.builder()
                                            .p1(MetadataProvider.Google)
                                            .p2(MetadataProvider.Amazon)
                                            .build())
                                    .build())
                            .build())
                    .build();
            when(appSettingService.getAppSettings()).thenReturn(settings);

            when(googleParser.fetchMetadata(any(Book.class), any(FetchMetadataRequest.class)))
                    .thenThrow(new RuntimeException("timeout"));
            BookMetadata expected = BookMetadata.builder().title("Amazon Result").build();
            when(amazonParser.fetchMetadata(any(Book.class), any(FetchMetadataRequest.class)))
                    .thenReturn(List.of(expected));

            BookMetadata result = service.lookupByIsbn(IsbnLookupRequest.builder().isbn("978-0123456789").build());

            assertThat(result).isEqualTo(expected);
        }

        @Test
        void returnsNullWhenAllProvidersFail() {
            BookParser googleParser = mock(BookParser.class);
            parserMap.put(MetadataProvider.Google, googleParser);

            AppSettings settings = AppSettings.builder()
                    .defaultMetadataRefreshOptions(MetadataRefreshOptions.builder()
                            .fieldOptions(MetadataRefreshOptions.FieldOptions.builder()
                                    .title(MetadataRefreshOptions.FieldProvider.builder()
                                            .p1(MetadataProvider.Google)
                                            .build())
                                    .build())
                            .build())
                    .build();
            when(appSettingService.getAppSettings()).thenReturn(settings);
            when(googleParser.fetchMetadata(any(Book.class), any(FetchMetadataRequest.class)))
                    .thenThrow(new RuntimeException("fail"));

            BookMetadata result = service.lookupByIsbn(IsbnLookupRequest.builder().isbn("978-0123456789").build());

            assertThat(result).isNull();
        }

        @Test
        void returnsNullWhenProviderReturnsEmptyList() {
            BookParser googleParser = mock(BookParser.class);
            parserMap.put(MetadataProvider.Google, googleParser);

            AppSettings settings = AppSettings.builder()
                    .defaultMetadataRefreshOptions(MetadataRefreshOptions.builder()
                            .fieldOptions(MetadataRefreshOptions.FieldOptions.builder()
                                    .title(MetadataRefreshOptions.FieldProvider.builder()
                                            .p1(MetadataProvider.Google)
                                            .build())
                                    .build())
                            .build())
                    .build();
            when(appSettingService.getAppSettings()).thenReturn(settings);
            when(googleParser.fetchMetadata(any(Book.class), any(FetchMetadataRequest.class)))
                    .thenReturn(Collections.emptyList());

            BookMetadata result = service.lookupByIsbn(IsbnLookupRequest.builder().isbn("978-0123456789").build());

            assertThat(result).isNull();
        }

        @Test
        void fallsBackToGoogleWhenSettingsHaveNoFieldOptions() {
            BookParser googleParser = mock(BookParser.class);
            parserMap.put(MetadataProvider.Google, googleParser);

            AppSettings settings = AppSettings.builder()
                    .defaultMetadataRefreshOptions(MetadataRefreshOptions.builder()
                            .fieldOptions(null)
                            .build())
                    .build();
            when(appSettingService.getAppSettings()).thenReturn(settings);

            BookMetadata expected = BookMetadata.builder().title("Google").build();
            when(googleParser.fetchMetadata(any(Book.class), any(FetchMetadataRequest.class)))
                    .thenReturn(List.of(expected));

            BookMetadata result = service.lookupByIsbn(IsbnLookupRequest.builder().isbn("978-0123456789").build());

            assertThat(result).isEqualTo(expected);
        }

        @Test
        void fallsBackToGoogleWhenSettingsThrow() {
            BookParser googleParser = mock(BookParser.class);
            parserMap.put(MetadataProvider.Google, googleParser);

            when(appSettingService.getAppSettings()).thenThrow(new RuntimeException("config error"));

            BookMetadata expected = BookMetadata.builder().title("Google").build();
            when(googleParser.fetchMetadata(any(Book.class), any(FetchMetadataRequest.class)))
                    .thenReturn(List.of(expected));

            BookMetadata result = service.lookupByIsbn(IsbnLookupRequest.builder().isbn("978-0123456789").build());

            assertThat(result).isEqualTo(expected);
        }

        @Test
        void fallsBackToGoogleWhenTitleProviderIsNull() {
            BookParser googleParser = mock(BookParser.class);
            parserMap.put(MetadataProvider.Google, googleParser);

            AppSettings settings = AppSettings.builder()
                    .defaultMetadataRefreshOptions(MetadataRefreshOptions.builder()
                            .fieldOptions(MetadataRefreshOptions.FieldOptions.builder()
                                    .title(null)
                                    .build())
                            .build())
                    .build();
            when(appSettingService.getAppSettings()).thenReturn(settings);

            BookMetadata expected = BookMetadata.builder().title("Google").build();
            when(googleParser.fetchMetadata(any(Book.class), any(FetchMetadataRequest.class)))
                    .thenReturn(List.of(expected));

            BookMetadata result = service.lookupByIsbn(IsbnLookupRequest.builder().isbn("978-0123456789").build());

            assertThat(result).isEqualTo(expected);
        }

        @Test
        void deduplicatesProviders() {
            BookParser googleParser = mock(BookParser.class);
            parserMap.put(MetadataProvider.Google, googleParser);

            AppSettings settings = AppSettings.builder()
                    .defaultMetadataRefreshOptions(MetadataRefreshOptions.builder()
                            .fieldOptions(MetadataRefreshOptions.FieldOptions.builder()
                                    .title(MetadataRefreshOptions.FieldProvider.builder()
                                            .p1(MetadataProvider.Google)
                                            .p2(MetadataProvider.Google)
                                            .p3(MetadataProvider.Google)
                                            .build())
                                    .build())
                            .build())
                    .build();
            when(appSettingService.getAppSettings()).thenReturn(settings);
            when(googleParser.fetchMetadata(any(Book.class), any(FetchMetadataRequest.class)))
                    .thenReturn(Collections.emptyList());

            service.lookupByIsbn(IsbnLookupRequest.builder().isbn("978-0123456789").build());

            verify(googleParser, times(1)).fetchMetadata(any(Book.class), any(FetchMetadataRequest.class));
        }

        @Test
        void fallsBackToGoogleWhenDefaultMetadataRefreshOptionsIsNull() {
            BookParser googleParser = mock(BookParser.class);
            parserMap.put(MetadataProvider.Google, googleParser);

            AppSettings settings = AppSettings.builder().defaultMetadataRefreshOptions(null).build();
            when(appSettingService.getAppSettings()).thenReturn(settings);

            BookMetadata expected = BookMetadata.builder().title("Google").build();
            when(googleParser.fetchMetadata(any(Book.class), any(FetchMetadataRequest.class)))
                    .thenReturn(List.of(expected));

            BookMetadata result = service.lookupByIsbn(IsbnLookupRequest.builder().isbn("978-0123456789").build());

            assertThat(result).isEqualTo(expected);
        }
    }

    @Nested
    class ToggleFieldLocks {

        @Test
        void locksSingleField() {
            BookMetadataEntity entity = BookMetadataEntity.builder().bookId(1L).titleLocked(false).build();
            BookEntity bookEntity = BookEntity.builder().id(1L).metadata(entity).build();
            when(bookQueryService.findAllWithMetadataByIds(Set.of(1L))).thenReturn(List.of(bookEntity));
            when(bookMapper.toBookWithDescription(bookEntity, true)).thenReturn(Book.builder().build());

            service.toggleFieldLocks(List.of(1L), Map.of("titleLocked", "LOCK"));

            assertThat(entity.getTitleLocked()).isTrue();
            verify(bookRepository).saveAll(List.of(bookEntity));
        }

        @Test
        void unlocksSingleField() {
            BookMetadataEntity entity = BookMetadataEntity.builder().bookId(1L).titleLocked(true).build();
            BookEntity bookEntity = BookEntity.builder().id(1L).metadata(entity).build();
            when(bookQueryService.findAllWithMetadataByIds(Set.of(1L))).thenReturn(List.of(bookEntity));
            when(bookMapper.toBookWithDescription(bookEntity, true)).thenReturn(Book.builder().build());

            service.toggleFieldLocks(List.of(1L), Map.of("titleLocked", "UNLOCK"));

            assertThat(entity.getTitleLocked()).isFalse();
            verify(bookRepository).saveAll(List.of(bookEntity));
        }

        @Test
        void mapsThumbnailLockedToCoverLocked() {
            BookMetadataEntity entity = BookMetadataEntity.builder().bookId(1L).coverLocked(false).build();
            BookEntity bookEntity = BookEntity.builder().id(1L).metadata(entity).build();
            when(bookQueryService.findAllWithMetadataByIds(Set.of(1L))).thenReturn(List.of(bookEntity));
            when(bookMapper.toBookWithDescription(bookEntity, true)).thenReturn(Book.builder().build());

            service.toggleFieldLocks(List.of(1L), Map.of("thumbnailLocked", "LOCK"));

            assertThat(entity.getCoverLocked()).isTrue();
        }

        @Test
        void handlesMultipleBooks() {
            BookMetadataEntity entity1 = BookMetadataEntity.builder().bookId(1L).titleLocked(false).build();
            BookEntity bookEntity1 = BookEntity.builder().id(1L).metadata(entity1).build();
            BookMetadataEntity entity2 = BookMetadataEntity.builder().bookId(2L).titleLocked(false).build();
            BookEntity bookEntity2 = BookEntity.builder().id(2L).metadata(entity2).build();
            when(bookQueryService.findAllWithMetadataByIds(Set.of(1L, 2L))).thenReturn(List.of(bookEntity1, bookEntity2));
            when(bookMapper.toBookWithDescription(any(), eq(true))).thenReturn(Book.builder().build());

            service.toggleFieldLocks(List.of(1L, 2L), Map.of("titleLocked", "LOCK"));

            assertThat(entity1.getTitleLocked()).isTrue();
            assertThat(entity2.getTitleLocked()).isTrue();
        }

        @Test
        void throwsForInvalidField() {
            BookMetadataEntity entity = BookMetadataEntity.builder().bookId(1L).build();
            BookEntity bookEntity = BookEntity.builder().id(1L).metadata(entity).build();
            when(bookQueryService.findAllWithMetadataByIds(Set.of(1L))).thenReturn(List.of(bookEntity));

            assertThatThrownBy(() -> service.toggleFieldLocks(List.of(1L), Map.of("nonExistentField", "LOCK")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to invoke setter for field: nonExistentField");
        }
    }

    @Nested
    class ToggleAllLock {

        @Test
        void locksAllFieldsForBooks() {
            BookMetadataEntity metadata = BookMetadataEntity.builder().bookId(1L).build();
            BookEntity bookEntity = BookEntity.builder().id(1L).metadata(metadata).build();
            when(bookQueryService.findAllWithMetadataByIds(Set.of(1L))).thenReturn(List.of(bookEntity));
            Book dto = Book.builder().build();
            when(bookMapper.toBookWithDescription(bookEntity, true)).thenReturn(dto);

            ToggleAllLockRequest request = new ToggleAllLockRequest();
            request.setBookIds(Set.of(1L));
            request.setLock(Lock.LOCK);

            List<Book> result = service.toggleAllLock(request);

            assertThat(result).hasSize(1);
            verify(bookRepository).saveAll(anyList());
        }

        @Test
        void unlocksAllFieldsForBooks() {
            BookMetadataEntity metadata = BookMetadataEntity.builder().bookId(1L).build();
            metadata.applyLockToAllFields(true);
            BookEntity bookEntity = BookEntity.builder().id(1L).metadata(metadata).build();
            when(bookQueryService.findAllWithMetadataByIds(Set.of(1L))).thenReturn(List.of(bookEntity));
            Book dto = Book.builder().build();
            when(bookMapper.toBookWithDescription(bookEntity, true)).thenReturn(dto);

            ToggleAllLockRequest request = new ToggleAllLockRequest();
            request.setBookIds(Set.of(1L));
            request.setLock(Lock.UNLOCK);

            List<Book> result = service.toggleAllLock(request);

            assertThat(result).hasSize(1);
            assertThat(metadata.getTitleLocked()).isFalse();
            assertThat(metadata.getCoverLocked()).isFalse();
        }
    }

    @Nested
    class GetComicInfoMetadata {

        @Test
        void throwsWhenBookNotFound() {
            when(bookRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getComicInfoMetadata(1L))
                    .isInstanceOf(APIException.class);
        }

        @Test
        void returnsNullWhenPrimaryFileIsNull() {
            BookEntity bookEntity = BookEntity.builder().id(1L).bookFiles(new ArrayList<>()).build();
            when(bookRepository.findById(1L)).thenReturn(Optional.of(bookEntity));

            BookMetadata result = service.getComicInfoMetadata(1L);

            assertThat(result).isNull();
        }

        @Test
        void returnsNullWhenFileTypeIsNotCbx() {
            BookFileEntity file = BookFileEntity.builder().bookType(BookFileType.PDF).isBookFormat(true).build();
            BookEntity bookEntity = BookEntity.builder().id(1L).bookFiles(List.of(file)).build();
            when(bookRepository.findById(1L)).thenReturn(Optional.of(bookEntity));

            BookMetadata result = service.getComicInfoMetadata(1L);

            assertThat(result).isNull();
        }
    }

    @Nested
    class GetFileMetadata {

        @Test
        void throwsWhenBookNotFound() {
            when(bookRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getFileMetadata(1L))
                    .isInstanceOf(APIException.class);
        }

        @Test
        void throwsWhenPrimaryFileIsNull() {
            BookEntity bookEntity = BookEntity.builder().id(1L).bookFiles(new ArrayList<>()).build();
            when(bookRepository.findById(1L)).thenReturn(Optional.of(bookEntity));

            assertThatThrownBy(() -> service.getFileMetadata(1L))
                    .isInstanceOf(APIException.class);
        }
    }

    @Nested
    class BulkUpdateMetadata {

        @Test
        void processesEachBookId() {
            BulkMetadataUpdateRequest request = new BulkMetadataUpdateRequest();
            request.setBookIds(Set.of(1L, 2L));
            request.setGenres(Set.of("Fiction"));
            request.setMoods(Set.of("Dark"));
            request.setTags(Set.of("Favorite"));

            MetadataClearFlags clearFlags = new MetadataClearFlags();
            when(metadataClearFlagsMapper.toClearFlags(request)).thenReturn(clearFlags);

            BookEntity book1 = BookEntity.builder().id(1L).build();
            BookEntity book2 = BookEntity.builder().id(2L).build();
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book1));
            when(bookRepository.findByIdWithBookFiles(2L)).thenReturn(Optional.of(book2));

            var txStatus = mock(org.springframework.transaction.TransactionStatus.class);
            when(transactionManager.getTransaction(any())).thenReturn(txStatus);

            service.bulkUpdateMetadata(request, true, false, true);

            verify(bookMetadataUpdater, times(2)).setBookMetadata(any());
        }

        @Test
        void continuesProcessingWhenOneBookFails() {
            BulkMetadataUpdateRequest request = new BulkMetadataUpdateRequest();
            // Use a LinkedHashSet to maintain order
            Set<Long> bookIds = new LinkedHashSet<>();
            bookIds.add(1L);
            bookIds.add(2L);
            request.setBookIds(bookIds);

            MetadataClearFlags clearFlags = new MetadataClearFlags();
            when(metadataClearFlagsMapper.toClearFlags(request)).thenReturn(clearFlags);

            var txStatus = mock(org.springframework.transaction.TransactionStatus.class);
            when(transactionManager.getTransaction(any())).thenReturn(txStatus);

            BookEntity book2 = BookEntity.builder().id(2L).build();
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.empty());
            when(bookRepository.findByIdWithBookFiles(2L)).thenReturn(Optional.of(book2));

            service.bulkUpdateMetadata(request, false, false, false);

            verify(bookMetadataUpdater, times(1)).setBookMetadata(any());
        }

        @Test
        void usesEmptySetWhenGenresMoodsTagsAreNull() {
            BulkMetadataUpdateRequest request = new BulkMetadataUpdateRequest();
            request.setBookIds(Set.of(1L));
            request.setGenres(null);
            request.setMoods(null);
            request.setTags(null);

            MetadataClearFlags clearFlags = new MetadataClearFlags();
            when(metadataClearFlagsMapper.toClearFlags(request)).thenReturn(clearFlags);

            var txStatus = mock(org.springframework.transaction.TransactionStatus.class);
            when(transactionManager.getTransaction(any())).thenReturn(txStatus);

            BookEntity book = BookEntity.builder().id(1L).build();
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            service.bulkUpdateMetadata(request, false, false, false);

            verify(bookMetadataUpdater).setBookMetadata(argThat(ctx -> {
                BookMetadata md = ctx.getMetadataUpdateWrapper().getMetadata();
                return md.getCategories().isEmpty() && md.getMoods().isEmpty() && md.getTags().isEmpty();
            }));
        }
    }

    @Nested
    class GetProspectiveMetadataListForBookId {

        @Test
        void throwsWhenBookNotFound() {
            when(bookRepository.findById(99L)).thenReturn(Optional.empty());
            FetchMetadataRequest request = FetchMetadataRequest.builder()
                    .providers(List.of(MetadataProvider.Google))
                    .build();

            assertThatThrownBy(() -> service.getProspectiveMetadataListForBookId(99L, request))
                    .isInstanceOf(APIException.class);
        }
    }
    
        @Nested
        class WipeMetadata {

                @Test
                void wipeBookMetadata_unlocksAndRestoresTitleFromPrimaryFilename() {
                        BookMetadataEntity metadataEntity = BookMetadataEntity.builder()
                                        .bookId(1L)
                                        .title("Title")
                                        .rating(4.5)
                                        .reviewCount(12)
                                        .authors(new ArrayList<>())
                                        .categories(new HashSet<>())
                                        .moods(new HashSet<>())
                                        .tags(new HashSet<>())
                                        .build();
                        metadataEntity.applyLockToAllFields(true);

                        BookFileEntity primaryFile = BookFileEntity.builder()
                                        .bookType(BookFileType.EPUB)
                                        .isBookFormat(true)
                                        .fileName("Moby-Dick or, The Whale.epub")
                                        .build();

                        BookEntity bookEntity = BookEntity.builder()
                                        .id(1L)
                                        .metadata(metadataEntity)
                                        .bookFiles(new ArrayList<>(List.of(primaryFile)))
                                        .build();
                        metadataEntity.setBook(bookEntity);
                        primaryFile.setBook(bookEntity);

                        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(bookEntity));
                        when(bookMapper.toBookWithDescription(bookEntity, true)).thenReturn(Book.builder().build());

                        service.wipeBookMetadata(1L);

                        var contextCaptor = org.mockito.ArgumentCaptor.forClass(org.fable.model.MetadataUpdateContext.class);
                        verify(bookMetadataUpdater).setBookMetadata(contextCaptor.capture());

                        assertThat(metadataEntity.getTitleLocked()).isFalse();
                        assertThat(metadataEntity.getReviewsLocked()).isFalse();
                        assertThat(metadataEntity.getRating()).isNull();
                        assertThat(metadataEntity.getReviewCount()).isNull();
                        assertThat(contextCaptor.getValue().getMetadataUpdateWrapper().getClearFlags().isTitle()).isFalse();
                        assertThat(contextCaptor.getValue().getMetadataUpdateWrapper().getMetadata().getTitle()).isEqualTo("Moby-Dick or, The Whale");
                        assertThat(contextCaptor.getValue().getMetadataUpdateWrapper().getClearFlags().isReviews()).isTrue();
                        assertThat(contextCaptor.getValue().getMetadataUpdateWrapper().getMetadata().getComicMetadata()).isNotNull();
                        assertThat(contextCaptor.getValue().getMetadataUpdateWrapper().getMetadata().getComicMetadata().getCharacters()).isEmpty();
                }

                @Test
                void wipeBookMetadata_clearsStagingCompletedFlags() {
                        BookMetadataEntity metadataEntity = BookMetadataEntity.builder()
                                        .bookId(1L)
                                        .title("Title")
                                        .isbnVerified(Boolean.TRUE)
                                        .isbnWrittenToFile(Boolean.TRUE)
                                        .authors(new ArrayList<>())
                                        .categories(new HashSet<>())
                                        .moods(new HashSet<>())
                                        .tags(new HashSet<>())
                                        .build();

                        BookEntity bookEntity = BookEntity.builder()
                                        .id(1L)
                                        .staged(true)
                                        .lastMetadataFetchAt(java.time.Instant.parse("2026-07-20T12:00:00Z"))
                                        .stagingCompletedAt(java.time.Instant.parse("2026-07-20T12:00:00Z"))
                                        .isbnDiscoveryStatus(org.fable.model.enums.IsbnDiscoveryStatus.ERROR)
                                        .isbnDiscoveryCheckedAt(java.time.Instant.parse("2026-07-20T11:00:00Z"))
                                        .isbnDiscoveryDetail("OCR unavailable")
                                        .metadata(metadataEntity)
                                        .bookFiles(new ArrayList<>())
                                        .build();
                        metadataEntity.setBook(bookEntity);

                        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(bookEntity));
                        when(bookMapper.toBookWithDescription(bookEntity, true)).thenReturn(Book.builder().build());

                        service.wipeBookMetadata(1L);

                        assertThat(bookEntity.getLastMetadataFetchAt()).isNull();
                        assertThat(bookEntity.getStagingCompletedAt()).isNull();
                        assertThat(bookEntity.getIsbnDiscoveryStatus()).isNull();
                        assertThat(bookEntity.getIsbnDiscoveryCheckedAt()).isNull();
                        assertThat(bookEntity.getIsbnDiscoveryDetail()).isNull();
                        assertThat(metadataEntity.getIsbnVerified()).isFalse();
                        assertThat(metadataEntity.getIsbnWrittenToFile()).isFalse();
                        assertThat(bookEntity.getStaged()).isTrue();
                }

                @Test
                void updateMetadata_stampsStagingCompletedAtForManualEditButSkipsFollowUpTagOnly() {
                        BookEntity stagedBook = BookEntity.builder()
                                        .id(10L)
                                        .staged(true)
                                        .metadata(BookMetadataEntity.builder().bookId(10L).title("Old").build())
                                        .build();
                        when(bookQueryService.findAllWithMetadataByIds(Collections.singleton(10L)))
                                        .thenReturn(List.of(stagedBook));
                        when(bookMapper.toBookWithDescription(stagedBook, true)).thenReturn(Book.builder().id(10L).build());

                        MetadataUpdateWrapper followUpOnly = MetadataUpdateWrapper.builder()
                                        .metadata(BookMetadata.builder()
                                                        .bookId(10L)
                                                        .tags(Set.of("Metadata Follow-Up Req"))
                                                        .build())
                                        .clearFlags(new MetadataClearFlags())
                                        .build();
                        service.updateMetadata(10L, followUpOnly, false, org.fable.model.enums.MetadataReplaceMode.REPLACE_WHEN_PROVIDED);
                        assertThat(stagedBook.getStagingCompletedAt()).isNull();
                        assertThat(stagedBook.getLastMetadataFetchAt()).isNull();

                        MetadataUpdateWrapper manualEdit = MetadataUpdateWrapper.builder()
                                        .metadata(BookMetadata.builder()
                                                        .bookId(10L)
                                                        .title("Edited Title")
                                                        .build())
                                        .clearFlags(new MetadataClearFlags())
                                        .build();
                        service.updateMetadata(10L, manualEdit, false, org.fable.model.enums.MetadataReplaceMode.REPLACE_WHEN_PROVIDED);
                        assertThat(stagedBook.getStagingCompletedAt()).isNotNull();
                        assertThat(stagedBook.getLastMetadataFetchAt()).isNull();
                }

                @Test
                void wipeBookMetadata_clearsTitleWhenNoFilenameFallbackExists() {
                        BookMetadataEntity metadataEntity = BookMetadataEntity.builder()
                                        .bookId(1L)
                                        .title("Title")
                                        .authors(new ArrayList<>())
                                        .categories(new HashSet<>())
                                        .moods(new HashSet<>())
                                        .tags(new HashSet<>())
                                        .build();

                        BookEntity bookEntity = BookEntity.builder()
                                        .id(1L)
                                        .metadata(metadataEntity)
                                        .bookFiles(new ArrayList<>())
                                        .build();
                        metadataEntity.setBook(bookEntity);

                        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(bookEntity));
                        when(bookMapper.toBookWithDescription(bookEntity, true)).thenReturn(Book.builder().build());

                        service.wipeBookMetadata(1L);

                        var contextCaptor = org.mockito.ArgumentCaptor.forClass(org.fable.model.MetadataUpdateContext.class);
                        verify(bookMetadataUpdater).setBookMetadata(contextCaptor.capture());

                        assertThat(contextCaptor.getValue().getMetadataUpdateWrapper().getClearFlags().isTitle()).isTrue();
                        assertThat(contextCaptor.getValue().getMetadataUpdateWrapper().getMetadata().getTitle()).isNull();
                }

                @Test
                void restoreTitlesFromFilename_updatesOnlyBlankUnlockedTitles() {
                        TransactionStatus txStatus = mock(TransactionStatus.class);
                        when(transactionManager.getTransaction(any())).thenReturn(txStatus);

                        BookMetadataEntity restorableMetadata = BookMetadataEntity.builder()
                                        .bookId(1L)
                                        .title(null)
                                        .authors(new ArrayList<>())
                                        .categories(new HashSet<>())
                                        .moods(new HashSet<>())
                                        .tags(new HashSet<>())
                                        .build();
                        BookMetadataEntity titledMetadata = BookMetadataEntity.builder()
                                        .bookId(2L)
                                        .title("Already set")
                                        .authors(new ArrayList<>())
                                        .categories(new HashSet<>())
                                        .moods(new HashSet<>())
                                        .tags(new HashSet<>())
                                        .build();
                        BookMetadataEntity lockedMetadata = BookMetadataEntity.builder()
                                        .bookId(3L)
                                        .title(null)
                                        .titleLocked(true)
                                        .authors(new ArrayList<>())
                                        .categories(new HashSet<>())
                                        .moods(new HashSet<>())
                                        .tags(new HashSet<>())
                                        .build();

                        BookEntity restorableBook = BookEntity.builder()
                                        .id(1L)
                                        .metadata(restorableMetadata)
                                        .bookFiles(new ArrayList<>(List.of(BookFileEntity.builder()
                                                        .bookType(BookFileType.EPUB)
                                                        .isBookFormat(true)
                                                        .fileName("Dune.epub")
                                                        .build())))
                                        .build();
                        restorableMetadata.setBook(restorableBook);
                        restorableBook.getBookFiles().getFirst().setBook(restorableBook);

                        BookEntity titledBook = BookEntity.builder()
                                        .id(2L)
                                        .metadata(titledMetadata)
                                        .bookFiles(new ArrayList<>(List.of(BookFileEntity.builder()
                                                        .bookType(BookFileType.EPUB)
                                                        .isBookFormat(true)
                                                        .fileName("Neuromancer.epub")
                                                        .build())))
                                        .build();
                        titledMetadata.setBook(titledBook);
                        titledBook.getBookFiles().getFirst().setBook(titledBook);

                        BookEntity lockedBook = BookEntity.builder()
                                        .id(3L)
                                        .metadata(lockedMetadata)
                                        .bookFiles(new ArrayList<>(List.of(BookFileEntity.builder()
                                                        .bookType(BookFileType.EPUB)
                                                        .isBookFormat(true)
                                                        .fileName("Hyperion.epub")
                                                        .build())))
                                        .build();
                        lockedMetadata.setBook(lockedBook);
                        lockedBook.getBookFiles().getFirst().setBook(lockedBook);

                        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(restorableBook));
                        when(bookRepository.findByIdWithBookFiles(2L)).thenReturn(Optional.of(titledBook));
                        when(bookRepository.findByIdWithBookFiles(3L)).thenReturn(Optional.of(lockedBook));
                        when(bookMapper.toBookWithDescription(eq(restorableBook), eq(true))).thenReturn(Book.builder().id(1L).build());

                        List<Book> restoredBooks = service.restoreTitlesFromFilename(new HashSet<>(List.of(1L, 2L, 3L)));
                        int restoredCount = restoredBooks.size();

                        assertThat(restoredCount).isEqualTo(1);
                        verify(bookMetadataUpdater).setBookMetadata(argThat(context ->
                                "Dune".equals(context.getMetadataUpdateWrapper().getMetadata().getTitle())
                        ));
                }
        }

}
