package org.fable.service;

import org.fable.model.entity.LogNotificationEntity;
import org.fable.model.websocket.Severity;
import org.fable.repository.LogNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LogNotificationServiceTest {

    @Mock
    private LogNotificationRepository repository;

    private LogNotificationService service;

    @BeforeEach
    void setUp() {
        service = new LogNotificationService(repository);
    }

    @Test
    void deleteByIdForUser_deletesOwnedNotification() {
        LogNotificationEntity entity = LogNotificationEntity.builder()
                .id(42L)
                .message("fail")
                .severity(Severity.ERROR)
                .triggeredByUserId(9L)
                .createdAt(Instant.now())
                .build();
        when(repository.findById(42L)).thenReturn(Optional.of(entity));

        assertTrue(service.deleteByIdForUser(42L, 9L, false).isPresent());
        verify(repository).delete(entity);
    }

    @Test
    void deleteByIdForUser_rejectsForeignNotification() {
        LogNotificationEntity entity = LogNotificationEntity.builder()
                .id(42L)
                .message("fail")
                .severity(Severity.ERROR)
                .triggeredByUserId(9L)
                .createdAt(Instant.now())
                .build();
        when(repository.findById(42L)).thenReturn(Optional.of(entity));

        assertTrue(service.deleteByIdForUser(42L, 3L, true).isEmpty());
        verify(repository, never()).delete(any());
    }

    @Test
    void deleteByIdForUser_allowsSystemForOperators() {
        LogNotificationEntity entity = LogNotificationEntity.builder()
                .id(7L)
                .message("cron fail")
                .severity(Severity.ERROR)
                .triggeredByUserId(null)
                .createdAt(Instant.now())
                .build();
        when(repository.findById(7L)).thenReturn(Optional.of(entity));

        assertTrue(service.deleteByIdForUser(7L, 1L, true).isPresent());
        verify(repository).delete(entity);
    }

    @Test
    void persist_storesTriggeredByUserId() {
        when(repository.save(any(LogNotificationEntity.class))).thenAnswer(invocation -> {
            LogNotificationEntity saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        LogNotificationEntity saved = service.persist("msg", Severity.WARN, 5L);
        ArgumentCaptor<LogNotificationEntity> captor = ArgumentCaptor.forClass(LogNotificationEntity.class);
        verify(repository).save(captor.capture());
        assertEquals(5L, captor.getValue().getTriggeredByUserId());
        assertEquals(99L, saved.getId());
    }
}
