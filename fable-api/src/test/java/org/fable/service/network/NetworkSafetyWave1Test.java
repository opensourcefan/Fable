package org.fable.service.network;

import org.fable.config.security.service.AuthenticationService;
import org.fable.mapper.BookMapper;
import org.fable.model.dto.Book;
import org.fable.model.dto.BookMetadata;
import org.fable.model.dto.FableUser;
import org.fable.model.dto.request.FetchMetadataRequest;
import org.fable.model.dto.request.MetadataRefreshOptions;
import org.fable.model.dto.request.MetadataRefreshRequest;
import org.fable.model.dto.settings.AppSettings;
import org.fable.model.dto.settings.MetadataProviderSettings;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.BookMetadataEntity;
import org.fable.model.entity.MetadataFetchJobEntity;
import org.fable.model.enums.MetadataFetchTaskStatus;
import org.fable.model.enums.MetadataProvider;
import org.fable.model.enums.MetadataReplaceMode;
import org.fable.repository.BookRepository;
import org.fable.repository.LibraryRepository;
import org.fable.repository.MetadataFetchJobRepository;
import org.fable.service.NotificationService;
import org.fable.service.appsettings.AppSettingService;
import org.fable.service.metadata.*;
import org.fable.service.metadata.parser.BookParser;
import org.fable.service.metadata.parser.GoogleParser;
import org.fable.task.TaskCancellationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NetworkSafetyWave1Test {

    @Mock private AppSettingService appSettingService;
    @Mock private HttpClient httpClient;
    @Mock private LibraryRepository libraryRepository;
    @Mock private MetadataFetchJobRepository metadataFetchJobRepository;
    @Mock private BookMapper bookMapper;
    @Mock private BookMetadataUpdater bookMetadataUpdater;
    @Mock private NotificationService notificationService;
    @Mock private BookRepository bookRepository;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private AuthenticationService authenticationService;
    @Mock private TaskCancellationManager cancellationManager;
    @Mock private BookMetadataService bookMetadataService;
    @Mock private IsbnDiscoveryService isbnDiscoveryService;
    @Mock private IsbnDiscoveryStatusService isbnDiscoveryStatusService;

    @Test
    void networkErrorClassifier_detectsDeepConnectivityCausesAndIgnoresCancellation() {
        Throwable wrappedDns = new RuntimeException("outer", new UnknownHostException("api.example.com"));
        Throwable wrappedTimeout = new RuntimeException(new SocketTimeoutException("Read timed out"));
        Throwable wrappedConnect = new ResourceAccessException("I/O error", new ConnectException("Connection refused"));
        Throwable wrappedSsl = new RuntimeException(new SSLException("Handshake dropped"));
        Throwable cancelled = new RuntimeException(new CancellationException("user cancelled"));
        Throwable interrupted = new RuntimeException(new InterruptedException("stopped"));

        assertThat(NetworkErrorClassifier.isConnectivityError(wrappedDns)).isTrue();
        assertThat(NetworkErrorClassifier.isConnectivityError(wrappedTimeout)).isTrue();
        assertThat(NetworkErrorClassifier.isConnectivityError(wrappedConnect)).isTrue();
        assertThat(NetworkErrorClassifier.isConnectivityError(wrappedSsl)).isTrue();
        assertThat(NetworkErrorClassifier.isConnectivityError(cancelled)).isFalse();
        assertThat(NetworkErrorClassifier.isConnectivityError(interrupted)).isFalse();
        assertThat(NetworkErrorClassifier.isTransientHttpStatus(503)).isTrue();
        assertThat(NetworkErrorClassifier.isTransientHttpStatus(502)).isTrue();
        assertThat(NetworkErrorClassifier.isTransientHttpStatus(504)).isTrue();
        assertThat(NetworkErrorClassifier.isTransientHttpStatus(404)).isFalse();
    }

    @Test
    void googleParser_setsRequestTimeoutAndThrowsTransientProviderExceptionWithoutFallbackStorm() throws Exception {
        MetadataProviderSettings.Google googleSettings = new MetadataProviderSettings.Google();
        googleSettings.setLanguage("en");
        MetadataProviderSettings providerSettings = new MetadataProviderSettings();
        providerSettings.setGoogle(googleSettings);
        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder().metadataProviderSettings(providerSettings).build());
        when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new UnknownHostException("www.googleapis.com"));

        GoogleParser parser = new GoogleParser(new ObjectMapper(), appSettingService, httpClient);
        FetchMetadataRequest request = FetchMetadataRequest.builder()
                .isbn("9780132350884")
                .title("Clean Code")
                .author("Robert C. Martin")
                .build();

        assertThatThrownBy(() -> parser.fetchMetadata(Book.builder().build(), request))
                .isInstanceOf(TransientProviderException.class)
                .satisfies(ex -> assertThat(((TransientProviderException) ex).isConnectivityError()).isTrue());

        // Must NOT cascade to ISBN-13 general, Title+Author, or Title-only on a connectivity drop
        ArgumentCaptor<HttpRequest> reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(1)).send(reqCaptor.capture(), any());
        assertThat(reqCaptor.getValue().timeout()).contains(Duration.ofSeconds(20));
    }

    @Test
    void metadataRefreshService_replaceAllDoesNotWipeOrMarkCompletedOnNetworkDrop() {
        BookParser googleParser = mock(BookParser.class);
        when(googleParser.fetchTopMetadata(any(), any()))
                .thenThrow(TransientProviderException.connectivity(MetadataProvider.Google, "DNS down", new UnknownHostException("www.googleapis.com")));

        MetadataProviderSettings.Google gSettings = new MetadataProviderSettings.Google();
        gSettings.setEnabled(true);
        MetadataProviderSettings providerSettings = new MetadataProviderSettings();
        providerSettings.setGoogle(gSettings);
        AppSettings appSettings = AppSettings.builder().metadataProviderSettings(providerSettings).build();
        when(appSettingService.getAppSettings()).thenReturn(appSettings);
        when(authenticationService.getAuthenticatedUser()).thenReturn(FableUser.builder().id(1L).username("admin").build());
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        BookMetadataEntity metaEntity = BookMetadataEntity.builder()
                .bookId(42L)
                .title("Existing Valuable Title")
                .build();
        BookEntity bookEntity = BookEntity.builder()
                .id(42L)
                .metadata(metaEntity)
                .build();
        when(bookRepository.findAllWithMetadataByIds(Collections.singleton(42L))).thenReturn(List.of(bookEntity));
        when(bookMapper.toBook(bookEntity)).thenReturn(Book.builder()
                .id(42L)
                .metadata(BookMetadata.builder().title("Existing Valuable Title").build())
                .build());

        MetadataRefreshService service = new MetadataRefreshService(
                libraryRepository,
                metadataFetchJobRepository,
                bookMapper,
                bookMetadataUpdater,
                notificationService,
                appSettingService,
                Map.of(MetadataProvider.Google, googleParser),
                new ObjectMapper(),
                bookRepository,
                transactionManager,
                authenticationService,
                cancellationManager
        );

        MetadataRefreshOptions options = MetadataRefreshOptions.builder()
                .reviewBeforeApply(false)
                .replaceMode(MetadataReplaceMode.REPLACE_ALL)
                .fieldOptions(MetadataRefreshOptions.FieldOptions.builder()
                        .title(MetadataRefreshOptions.FieldProvider.builder().p1(MetadataProvider.Google).build())
                        .build())
                .enabledFields(MetadataRefreshOptions.EnabledFields.builder().title(true).build())
                .build();

        MetadataRefreshRequest request = MetadataRefreshRequest.builder()
                .refreshType(MetadataRefreshRequest.RefreshType.BOOKS)
                .bookIds(Set.of(42L))
                .refreshOptions(options)
                .build();

        service.refreshMetadata(request, "job-net-drop");

        // Must NOT call full-scan fallback after TransientProviderException
        verify(googleParser, never()).fetchMetadata(any(), any());
        // Must NOT wipe metadata or stamp lastMetadataFetchAt
        verify(bookMetadataUpdater, never()).setBookMetadata(any());
        assertThat(bookEntity.getLastMetadataFetchAt()).isNull();

        ArgumentCaptor<MetadataFetchJobEntity> jobCaptor = ArgumentCaptor.forClass(MetadataFetchJobEntity.class);
        verify(metadataFetchJobRepository, atLeastOnce()).save(jobCaptor.capture());
        MetadataFetchJobEntity finalSaved = jobCaptor.getAllValues().getLast();
        assertThat(finalSaved.getStatus()).isEqualTo(MetadataFetchTaskStatus.ERROR);
        assertThat(finalSaved.getCompletedBookIds()).doesNotContain(42L);
    }

    @Test
    void isbnMetadataFillService_doesNotClearUnlockedMetadataWhenProvidersFailWithNetworkError() {
        BookParser googleParser = mock(BookParser.class);
        when(googleParser.fetchMetadata(any(), any()))
                .thenThrow(TransientProviderException.connectivity(MetadataProvider.Google, "Offline", new UnknownHostException("www.googleapis.com")));

        AppSettings settings = AppSettings.builder()
                .isbnDiscoveryEnabled(true)
                .isbnFetchReviewBeforeApply(false)
                .isbnFillMode("MULTI_PASS")
                .build();
        when(appSettingService.getAppSettings()).thenReturn(settings);

        BookMetadataEntity metaEntity = BookMetadataEntity.builder()
                .bookId(7L)
                .title("Keep Me Safe")
                .isbn13("9780132350884")
                .build();
        BookEntity bookEntity = BookEntity.builder().id(7L).metadata(metaEntity).build();
        when(bookRepository.findById(7L)).thenReturn(Optional.of(bookEntity));
        when(bookMapper.toBook(bookEntity)).thenReturn(Book.builder()
                .id(7L)
                .metadata(BookMetadata.builder().title("Keep Me Safe").isbn13("9780132350884").build())
                .build());

        IsbnMetadataFillService service = new IsbnMetadataFillService(
                appSettingService,
                bookRepository,
                bookMapper,
                bookMetadataService,
                bookMetadataUpdater,
                isbnDiscoveryService,
                isbnDiscoveryStatusService,
                notificationService,
                Map.of(MetadataProvider.Google, googleParser)
        );

        assertThatThrownBy(() -> service.fillBookFromIsbn(7L, List.of(MetadataProvider.Google)))
                .isInstanceOf(TransientProviderException.class);

        verifyNoInteractions(bookMetadataService);
        verify(bookMetadataUpdater, never()).setBookMetadata(any());
    }
}

