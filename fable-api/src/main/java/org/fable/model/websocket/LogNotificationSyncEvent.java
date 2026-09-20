package org.fable.model.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

/**
 * WebSocket payload on {@link Topic#LOG} for inbox mutations other than new failures.
 * Clients must branch on {@code action} before treating the message as a new notification.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LogNotificationSyncEvent {

    public static final String ACTION_DELETED = "DELETED";
    public static final String ACTION_CLEARED = "CLEARED";

    private final String action;
    private final Long id;

    private LogNotificationSyncEvent(String action, Long id) {
        this.action = action;
        this.id = id;
    }

    public static LogNotificationSyncEvent deleted(Long id) {
        return new LogNotificationSyncEvent(ACTION_DELETED, id);
    }

    public static LogNotificationSyncEvent cleared() {
        return new LogNotificationSyncEvent(ACTION_CLEARED, null);
    }
}
