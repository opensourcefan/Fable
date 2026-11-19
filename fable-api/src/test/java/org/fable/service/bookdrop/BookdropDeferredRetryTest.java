package org.fable.service.bookdrop;

import org.fable.model.entity.BookdropFileEntity;
import org.fable.model.enums.MetadataProvider;
import org.fable.repository.BookdropFileRepository;
import org.fable.service.appsettings.AppSettingService;
import org.fable.service.network.TransientProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BookdropDeferredRetryTest {

    @Test
    void deferredRetrySkipsAlreadyFinalizedBookdropFile() throws Exception {
        BookdropFileRepository repo = mock(BookdropFileRepository.class);
        BookdropNotificationService notif = mock(BookdropNotificationService.class);
        AppSettingService appSettingService = mock(AppSettingService.class);
        BookdropMetadataService metadataService = mock(BookdropMetadataService.class);
        BookdropInboxService inboxService = mock(BookdropInboxService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);

        BookdropEventHandlerService handler = new BookdropEventHandlerService(
                repo, notif, appSettingService, metadataService, inboxService, scheduler);

        BookdropFileEntity finalized = BookdropFileEntity.builder()
                .id(42L)
                .status(BookdropFileEntity.Status.FINALIZED)
                .build();
        when(repo.findById(42L)).thenReturn(Optional.of(finalized));

        handler.runDeferredMetadataFetchRetry(42L, 0);

        verify(metadataService, never()).attachFetchedMetadata(any());
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void deferredRetryReschedulesOnTransientFailureAndNotifiesOnSuccess() throws Exception {
        BookdropFileRepository repo = mock(BookdropFileRepository.class);
        BookdropNotificationService notif = mock(BookdropNotificationService.class);
        AppSettingService appSettingService = mock(AppSettingService.class);
        BookdropMetadataService metadataService = mock(BookdropMetadataService.class);
        BookdropInboxService inboxService = mock(BookdropInboxService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);

        BookdropEventHandlerService handler = new BookdropEventHandlerService(
                repo, notif, appSettingService, metadataService, inboxService, scheduler);

        BookdropFileEntity pending = BookdropFileEntity.builder()
                .id(77L)
                .status(BookdropFileEntity.Status.PENDING_REVIEW)
                .fetchedMetadata(null)
                .build();
        when(repo.findById(77L)).thenReturn(Optional.of(pending));
        when(metadataService.attachFetchedMetadata(77L))
                .thenThrow(TransientProviderException.connectivity(MetadataProvider.Google, "Offline", null))
                .thenReturn(pending);

        // Attempt 0 fails transiently -> reschedules attempt 1
        handler.runDeferredMetadataFetchRetry(77L, 0);
        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
        verify(notif, never()).sendBookdropFileSummaryNotification();

        // Attempt 1 succeeds -> notifies summary and does not schedule again
        handler.runDeferredMetadataFetchRetry(77L, 1);
        verify(notif, times(1)).sendBookdropFileSummaryNotification();
        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }
}

