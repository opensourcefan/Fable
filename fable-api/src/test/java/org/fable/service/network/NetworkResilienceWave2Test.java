package org.fable.service.network;

import org.fable.config.security.service.AuthenticationService;
import org.fable.mapper.BookMapper;
import org.fable.model.dto.Book;
import org.fable.model.dto.BookMetadata;
import org.fable.model.dto.FableUser;
import org.fable.model.dto.request.MetadataRefreshOptions;
import org.fable.model.dto.request.MetadataRefreshRequest;
import org.fable.model.dto.request.TaskCreateRequest;
import org.fable.model.dto.response.TaskCreateResponse;
import org.fable.model.dto.settings.AppSettings;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.BookMetadataEntity;
import org.fable.model.entity.LibraryEntity;
import org.fable.model.entity.MetadataFetchJobEntity;
import org.fable.model.enums.MetadataFetchTaskStatus;
import org.fable.model.enums.MetadataProvider;
import org.fable.model.enums.TaskType;
import org.fable.repository.BookRepository;
import org.fable.repository.LibraryRepository;
import org.fable.repository.MetadataFetchJobRepository;
import org.fable.service.FailureNotificationService;
import org.fable.service.LogNotificationService;
import org.fable.service.NotificationService;
import org.fable.service.appsettings.AppSettingService;
import org.fable.service.metadata.BookMetadataUpdater;
import org.fable.service.metadata.MetadataRefreshService;
import org.fable.service.metadata.parser.BookParser;
import org.fable.service.task.TaskCronService;
import org.fable.service.task.TaskHistoryService;
import org.fable.service.task.TaskService;
import org.fable.service.user.UserService;
import org.fable.task.TaskCancellationManager;
import org.fable.task.TaskStatus;
import org.fable.task.tasks.Task;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class NetworkResilienceWave2Test {

    private NetworkResilienceService buildDeterministicService(
            TaskCancellationManager cancellationManager,
            AtomicBoolean networkUp,
            AtomicLong fakeClock) {
        return new NetworkResilienceService(
                cancellationManager,
                (host, port, timeoutMs) -> networkUp.get(),
                ms -> fakeClock.addAndGet(Math.max(1L, ms)),
                fakeClock::get,
                new long[]{100L, 200L},
                new long[]{1_000L, 2_000L, 5_000L}
        );
    }

    @Test
    void briefBlipRecoversViaMicroRetryWithoutEnteringNetworkPause() {
        TaskCancellationManager cancellationManager = new TaskCancellationManager();
        AtomicBoolean networkUp = new AtomicBoolean(true);
        AtomicLong clock = new AtomicLong(10_000L);
        NetworkResilienceService resilience = buildDeterministicService(cancellationManager, networkUp, clock);

        AtomicInteger attempts = new AtomicInteger(0);
        List<String> pauseMessages = new ArrayList<>();

        String result = resilience.executeWithOutageRecovery(
                "task-blip",
                List.of(MetadataProvider.Google),
                Duration.ofMinutes(5),
                pauseMessages::add,
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw TransientProviderException.connectivity(
                                MetadataProvider.Google, "Temporary DNS blip", new UnknownHostException("dns"));
                    }
                    return "recovered-ok";
                });

        assertThat(result).isEqualTo("recovered-ok");
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(pauseMessages).isEmpty();
    }

    @Test
    void connectivityOutagePausesInPlaceAndAutoResumesWhenProbeRecovers() {
        TaskCancellationManager cancellationManager = new TaskCancellationManager();
        AtomicBoolean networkUp = new AtomicBoolean(false);
        AtomicLong clock = new AtomicLong(10_000L);
        AtomicInteger probeCalls = new AtomicInteger(0);

        NetworkResilienceService resilience = new NetworkResilienceService(
                cancellationManager,
                (host, port, timeoutMs) -> {
                    int call = probeCalls.incrementAndGet();
                    // First two probes see offline, third sees connectivity restored
                    if (call >= 3) {
                        networkUp.set(true);
                    }
                    return networkUp.get();
                },
                ms -> clock.addAndGet(Math.max(1L, ms)),
                clock::get,
                new long[]{50L, 50L},
                new long[]{1_000L, 2_000L}
        );

        List<String> pauseMessages = new ArrayList<>();
        AtomicInteger opCalls = new AtomicInteger(0);

        String result = resilience.executeWithOutageRecovery(
                "task-outage",
                List.of(MetadataProvider.Google),
                Duration.ofMinutes(5),
                pauseMessages::add,
                () -> {
                    opCalls.incrementAndGet();
                    if (!networkUp.get()) {
                        throw TransientProviderException.connectivity(
                                MetadataProvider.Google, "Connection lost", new SocketTimeoutException("timeout"));
                    }
                    return "post-recovery-data";
                });

        assertThat(result).isEqualTo("post-recovery-data");
        assertThat(pauseMessages).anyMatch(msg -> msg.contains("Network connection lost"));
        assertThat(pauseMessages).anyMatch(msg -> msg.contains("Connection restored"));
        // Initial 3 attempts during outage + 1 attempt after probe restored = 4 (did NOT burn calls while offline!)
        assertThat(opCalls.get()).isEqualTo(4);
    }

    @Test
    void cancellationDuringNetworkPauseExitsImmediately() {
        TaskCancellationManager cancellationManager = new TaskCancellationManager();
        AtomicBoolean networkUp = new AtomicBoolean(false);
        AtomicLong clock = new AtomicLong(10_000L);
        NetworkResilienceService resilience = buildDeterministicService(cancellationManager, networkUp, clock);

        assertThatThrownBy(() -> resilience.executeWithOutageRecovery(
                "task-cancel",
                List.of(MetadataProvider.Google),
                Duration.ofMinutes(5),
                msg -> cancellationManager.cancelTask("task-cancel"),
                () -> {
                    throw TransientProviderException.connectivity(
                            MetadataProvider.Google, "Offline", new UnknownHostException("offline"));
                }))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void isolatedProviderHttp503WithHealthyProbeDoesNotPauseAndTripsCircuitBreaker() {
        TaskCancellationManager cancellationManager = new TaskCancellationManager();
        AtomicBoolean networkUp = new AtomicBoolean(true);
        AtomicLong clock = new AtomicLong(10_000L);
        NetworkResilienceService resilience = buildDeterministicService(cancellationManager, networkUp, clock);

        List<String> pauseMessages = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> resilience.executeWithOutageRecovery(
                    "task-503-" + clock.get(),
                    List.of(MetadataProvider.Amazon),
                    Duration.ofMinutes(5),
                    pauseMessages::add,
                    () -> {
                        throw TransientProviderException.upstreamStatus(
                                MetadataProvider.Amazon, 503, "HTTP 503 upstream", null);
                    }))
                    .isInstanceOf(TransientProviderException.class);
        }

        assertThat(pauseMessages).isEmpty();
        assertThat(resilience.isCircuitOpen(MetadataProvider.Amazon)).isTrue();
        resilience.recordProviderSuccess(MetadataProvider.Amazon);
        assertThat(resilience.isCircuitOpen(MetadataProvider.Amazon)).isFalse();
    }

    @Test
    void waitForEndpointRecoveryRecoversAndRespectsBudget() {
        TaskCancellationManager cancellationManager = new TaskCancellationManager();
        AtomicInteger probes = new AtomicInteger(0);
        AtomicLong clock = new AtomicLong(10_000L);
        NetworkResilienceService resilience = new NetworkResilienceService(
                cancellationManager,
                (host, port, timeoutMs) -> probes.incrementAndGet() >= 3,
                ms -> clock.addAndGet(Math.max(1L, ms)),
                clock::get,
                new long[]{50L},
                new long[]{1_000L}
        );

        List<String> statuses = new ArrayList<>();
        boolean recovered = resilience.waitForEndpointRecovery(
                "ai-task", "http://localhost:8090", Duration.ofMinutes(2), statuses::add);
        assertThat(recovered).isTrue();
        assertThat(statuses).anyMatch(s -> s.contains("restored"));

        // Budget exhausted case
        NetworkResilienceService alwaysDown = buildDeterministicService(cancellationManager, new AtomicBoolean(false), new AtomicLong(0L));
        assertThat(alwaysDown.waitForEndpointRecovery("ai-task-2", "http://localhost:8090", Duration.ofSeconds(3), null))
                .isFalse();
    }

    @Test
    void metadataRefreshStopsLoopCleanlyOnOutageExhaustionAndPreservesResumableBooks() {
        LibraryRepository libraryRepository = mock(LibraryRepository.class);
        MetadataFetchJobRepository jobRepository = mock(MetadataFetchJobRepository.class);
        BookMapper bookMapper = mock(BookMapper.class);
        BookMetadataUpdater bookMetadataUpdater = mock(BookMetadataUpdater.class);
        NotificationService notificationService = mock(NotificationService.class);
        AppSettingService appSettingService = mock(AppSettingService.class);
        BookParser googleParser = mock(BookParser.class);
        ObjectMapper objectMapper = new ObjectMapper();
        BookRepository bookRepository = mock(BookRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        AuthenticationService authenticationService = mock(AuthenticationService.class);
        TaskCancellationManager cancellationManager = new TaskCancellationManager();

        when(txManager.getTransaction(any(TransactionDefinition.class))).thenAnswer(inv -> new SimpleTransactionStatus());
        when(authenticationService.getAuthenticatedUser()).thenReturn(FableUser.builder().id(1L).username("tester").build());

        MetadataRefreshOptions opts = MetadataRefreshOptions.builder()
                .fieldOptions(MetadataRefreshOptions.FieldOptions.builder()
                        .title(MetadataRefreshOptions.FieldProvider.builder().p1(MetadataProvider.Google).build())
                        .build())
                .build();
        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder().defaultMetadataRefreshOptions(opts).build());

        // Capture saved job entity
        final MetadataFetchJobEntity[] savedJob = new MetadataFetchJobEntity[1];
        when(jobRepository.save(any(MetadataFetchJobEntity.class))).thenAnswer(inv -> {
            savedJob[0] = inv.getArgument(0);
            return savedJob[0];
        });
        when(jobRepository.findById("job-outage")).thenAnswer(inv -> Optional.ofNullable(savedJob[0]));

        BookEntity book1 = buildBookEntity(1L, "Book One");
        BookEntity book2 = buildBookEntity(2L, "Book Two");
        BookEntity book3 = buildBookEntity(3L, "Book Three");
        when(bookRepository.findAllWithMetadataByIds(Set.of(1L))).thenReturn(List.of(book1));
        when(bookRepository.findAllWithMetadataByIds(Set.of(2L))).thenReturn(List.of(book2));
        when(bookRepository.findAllWithMetadataByIds(Set.of(3L))).thenReturn(List.of(book3));
        when(bookMapper.toBook(any(BookEntity.class))).thenAnswer(inv -> {
            BookEntity e = inv.getArgument(0);
            return Book.builder().id(e.getId()).metadata(BookMetadata.builder().title(e.getMetadata().getTitle()).build()).build();
        });
        when(bookMapper.toBookWithDescription(any(BookEntity.class), eq(true))).thenAnswer(inv -> {
            BookEntity e = inv.getArgument(0);
            return Book.builder().id(e.getId()).build();
        });

        // Book 1 succeeds; Book 2 hits sustained network outage -> budget exhausted
        AtomicBoolean networkUp = new AtomicBoolean(true);
        AtomicLong clock = new AtomicLong(0L);
        NetworkResilienceService resilience = new NetworkResilienceService(
                cancellationManager,
                (host, port, timeoutMs) -> networkUp.get(),
                ms -> clock.addAndGet(Math.max(1L, ms)),
                clock::get,
                new long[]{100L, 100L},
                new long[]{600_000L, 600_000L, 600_000L}
        );

        when(googleParser.fetchTopMetadata(any(), any())).thenAnswer(inv -> {
            Book b = inv.getArgument(0);
            if (b.getId() == 1L) {
                return BookMetadata.builder().provider(MetadataProvider.Google).title("Book One Updated").build();
            }
            networkUp.set(false);
            throw TransientProviderException.connectivity(MetadataProvider.Google, "No internet", new UnknownHostException("offline"));
        });

        MetadataRefreshService refreshService = new MetadataRefreshService(
                libraryRepository, jobRepository, bookMapper, bookMetadataUpdater, notificationService,
                appSettingService, Map.of(MetadataProvider.Google, googleParser), objectMapper,
                bookRepository, txManager, authenticationService, cancellationManager, resilience
        );

        MetadataRefreshRequest req = MetadataRefreshRequest.builder()
                .refreshType(MetadataRefreshRequest.RefreshType.BOOKS)
                .bookIds(new LinkedHashSet<>(List.of(1L, 2L, 3L)))
                .refreshOptions(opts)
                .build();

        refreshService.refreshMetadata(req, "job-outage");

        assertThat(savedJob[0]).isNotNull();
        assertThat(savedJob[0].getStatus()).isEqualTo(MetadataFetchTaskStatus.ERROR);
        assertThat(savedJob[0].getCompletedBooks()).isEqualTo(1);
        assertThat(savedJob[0].getCompletedBookIds()).containsExactly(1L);
        assertThat(savedJob[0].getRequestedBookIds()).containsExactly(1L, 2L, 3L);
        assertThat(refreshService.getTerminalTaskStatus("job-outage")).isEqualTo(TaskStatus.FAILED);
        // Book 3 must NEVER have been loaded because the loop broke cleanly on Book 2 outage exhaustion
        verify(bookRepository, never()).findAllWithMetadataByIds(Set.of(3L));
    }

    @Test
    void taskServicePropagatesFailedStatusFromAsyncTaskToHistory() {
        AuthenticationService auth = mock(AuthenticationService.class);
        TaskHistoryService history = mock(TaskHistoryService.class);
        TaskCronService cron = mock(TaskCronService.class);
        UserService userService = mock(UserService.class);
        NotificationService notificationService = mock(NotificationService.class);
        LogNotificationService logNotificationService = mock(LogNotificationService.class);
        FailureNotificationService failureNotificationService = mock(FailureNotificationService.class);
        TaskCancellationManager cancellationManager = new TaskCancellationManager();
        Executor directExecutor = Runnable::run;
        ObjectMapper objectMapper = new ObjectMapper();
        TaskScheduler taskScheduler = mock(TaskScheduler.class);

        Task failingAsyncTask = mock(Task.class);
        when(failingAsyncTask.getTaskType()).thenReturn(TaskType.REFRESH_METADATA_MANUAL);
        when(failingAsyncTask.execute(any())).thenAnswer(inv -> {
            TaskCreateRequest req = inv.getArgument(0);
            return TaskCreateResponse.builder()
                    .taskId(req.getTaskId())
                    .taskType(TaskType.REFRESH_METADATA_MANUAL)
                    .status(TaskStatus.FAILED)
                    .build();
        });

        TaskService taskService = new TaskService(
                auth, history, cron, userService, notificationService, logNotificationService,
                failureNotificationService, List.of(failingAsyncTask), cancellationManager,
                directExecutor, objectMapper, taskScheduler
        );

        when(auth.getAuthenticatedUser()).thenReturn(FableUser.builder().id(1L).username("admin").build());

        TaskCreateResponse accepted = taskService.runAsUser(TaskCreateRequest.builder()
                .taskType(TaskType.REFRESH_METADATA_MANUAL)
                .triggeredByCron(false)
                .build());

        verify(history, timeout(5_000)).updateTaskError(eq(accepted.getTaskId()), anyString());
        verify(history, never()).updateTaskStatus(eq(accepted.getTaskId()), eq(TaskStatus.COMPLETED), anyString());
    }

    private BookEntity buildBookEntity(Long id, String title) {
        LibraryEntity lib = new LibraryEntity();
        lib.setId(1L);
        BookMetadataEntity meta = BookMetadataEntity.builder().bookId(id).title(title).build();
        BookEntity entity = BookEntity.builder().id(id).library(lib).metadata(meta).build();
        meta.setBook(entity);
        return entity;
    }
}

