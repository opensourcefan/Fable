package org.fable.controller;

import org.fable.config.security.SecurityUtil;
import org.fable.config.security.service.AuthenticationService;
import org.fable.model.dto.request.FailureNotificationRequest;
import org.fable.model.entity.LogNotificationEntity;
import org.fable.model.enums.PermissionType;
import org.fable.model.websocket.LogNotification;
import org.fable.model.websocket.LogNotificationSyncEvent;
import org.fable.model.websocket.Topic;
import org.fable.repository.UserRepository;
import org.fable.service.FailureNotificationService;
import org.fable.service.LogNotificationService;
import org.fable.service.NotificationService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/notifications")
public class LogNotificationController {

    private final LogNotificationService logNotificationService;
    private final FailureNotificationService failureNotificationService;
    private final AuthenticationService authenticationService;
    private final SecurityUtil securityUtil;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    @GetMapping("/recent")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<LogNotification>> getRecentNotifications(
            @RequestParam(defaultValue = "50") int limit) {
        int cappedLimit = Math.min(limit, 200);
        var user = authenticationService.getAuthenticatedUser();
        if (user == null || user.getId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean includeSystem = securityUtil.canAccessTaskManager() || securityUtil.isAdmin();
        List<LogNotification> notifications = logNotificationService.getRecentFailuresForUser(
                user.getId(), includeSystem, cappedLimit);
        return ResponseEntity.ok(notifications);
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<LogNotification> reportFailure(@Valid @RequestBody FailureNotificationRequest request) {
        var user = authenticationService.getAuthenticatedUser();
        if (user == null || user.getId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String message = request.getMessage();
        if (request.getOperation() != null && !request.getOperation().isBlank()) {
            message = request.getOperation().trim() + ": " + message;
        }
        LogNotification notification = failureNotificationService.reportError(message, user.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(notification);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteNotification(@PathVariable Long id) {
        var user = authenticationService.getAuthenticatedUser();
        if (user == null || user.getId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean allowSystem = securityUtil.canAccessTaskManager() || securityUtil.isAdmin();
        var deleted = logNotificationService.deleteByIdForUser(id, user.getId(), allowSystem);
        if (deleted.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        broadcastDeleted(deleted.get());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteAllNotifications() {
        var user = authenticationService.getAuthenticatedUser();
        if (user == null || user.getId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (securityUtil.canAccessTaskManager() || securityUtil.isAdmin()) {
            logNotificationService.deleteAllNotifications();
            notificationService.sendMessageToAllUsers(Topic.LOG, LogNotificationSyncEvent.cleared());
        } else {
            logNotificationService.deleteAllForUser(user.getId());
            notificationService.sendMessageToUser(user.getUsername(), Topic.LOG, LogNotificationSyncEvent.cleared());
        }
        return ResponseEntity.noContent().build();
    }

    private void broadcastDeleted(LogNotificationEntity entity) {
        LogNotificationSyncEvent event = LogNotificationSyncEvent.deleted(entity.getId());
        if (entity.getTriggeredByUserId() != null) {
            userRepository.findById(entity.getTriggeredByUserId())
                    .ifPresent(owner -> notificationService.sendMessageToUser(
                            owner.getUsername(), Topic.LOG, event));
        } else {
            notificationService.sendMessageToPermissions(Topic.LOG, event,
                    Set.of(PermissionType.ADMIN, PermissionType.MANAGE_LIBRARY));
        }
    }
}
