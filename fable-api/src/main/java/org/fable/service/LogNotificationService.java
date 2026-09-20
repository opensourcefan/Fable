package org.fable.service;

import org.fable.model.entity.LogNotificationEntity;
import org.fable.model.websocket.LogNotification;
import org.fable.model.websocket.Severity;
import org.fable.repository.LogNotificationRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class LogNotificationService {

    private static final Set<Severity> INBOX_SEVERITIES = EnumSet.of(Severity.ERROR, Severity.WARN);

    private final LogNotificationRepository repository;

    @Transactional
    public LogNotificationEntity persist(String message, Severity severity) {
        return persist(message, severity, null);
    }

    @Transactional
    public LogNotificationEntity persist(String message, Severity severity, Long triggeredByUserId) {
        LogNotificationEntity entity = LogNotificationEntity.builder()
                .message(message)
                .severity(severity)
                .triggeredByUserId(triggeredByUserId)
                .createdAt(Instant.now())
                .build();
        LogNotificationEntity saved = repository.save(entity);
        log.debug("Persisted log notification id={} severity={} userId={}",
                saved.getId(), severity, triggeredByUserId);
        return saved;
    }

    /**
     * Failure/warn inbox for the current user. System (null actor) failures included for task managers/admins.
     */
    @Transactional(readOnly = true)
    public List<LogNotification> getRecentFailuresForUser(Long userId, boolean includeSystem, int maxResults) {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        return repository.findVisibleFailures(
                        since, userId, includeSystem, INBOX_SEVERITIES, PageRequest.of(0, maxResults))
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LogNotification> getRecentNotifications(int maxResults) {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        return repository.findByCreatedAtAfterOrderByCreatedAtDesc(since, PageRequest.of(0, maxResults))
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteAllNotifications() {
        repository.deleteAll();
        log.info("Deleted all log notifications");
    }

    @Transactional
    public void deleteAllForUser(Long userId) {
        repository.deleteByTriggeredByUserId(userId);
        log.info("Deleted log notifications for userId={}", userId);
    }

    /**
     * Deletes a visible inbox row. Returns the deleted entity when removal succeeded
     * so callers can broadcast a sync event to other sessions.
     */
    @Transactional
    public Optional<LogNotificationEntity> deleteByIdForUser(Long id, Long userId, boolean allowSystem) {
        return repository.findById(id).flatMap(entity -> {
            boolean owns = userId != null && userId.equals(entity.getTriggeredByUserId());
            boolean systemOk = allowSystem && entity.getTriggeredByUserId() == null;
            if (!owns && !systemOk) {
                return Optional.empty();
            }
            repository.delete(entity);
            return Optional.of(entity);
        });
    }

    public LogNotification toDto(LogNotificationEntity entity) {
        return new LogNotification(
                entity.getId(),
                entity.getMessage(),
                entity.getSeverity(),
                entity.getCreatedAt()
        );
    }
}
