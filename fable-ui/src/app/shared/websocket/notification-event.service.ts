import {inject, Injectable} from '@angular/core';
import {BehaviorSubject, Observable, of} from 'rxjs';
import {catchError, filter, map} from 'rxjs/operators';
import {
  formatNotificationTime,
  isInboxSeverity,
  LogNotification
} from './model/log-notification.model';
import {HttpClient} from '@angular/common/http';
import {API_CONFIG} from '../../core/config/api-config';

@Injectable({
  providedIn: 'root',
})
export class NotificationEventService {
  private http = inject(HttpClient);

  private latestNotificationSubject = new BehaviorSubject<LogNotification | null>(null);

  latestNotification$: Observable<LogNotification> = this.latestNotificationSubject.asObservable().pipe(
    filter((event): event is LogNotification => event !== null)
  );

  activeNotification$: Observable<LogNotification | null> = this.latestNotificationSubject.asObservable();

  private notificationHighlightSubject = new BehaviorSubject<boolean>(false);
  notificationHighlight$ = this.notificationHighlightSubject.asObservable();

  private historicalNotificationsSubject = new BehaviorSubject<LogNotification[]>([]);
  historicalNotifications$ = this.historicalNotificationsSubject.asObservable();

  private unreadFailureCountSubject = new BehaviorSubject<number>(0);
  unreadFailureCount$ = this.unreadFailureCountSubject.asObservable();

  /** Ignores stale GET /recent responses that race with deletes. */
  private fetchGeneration = 0;

  handleNewNotification(notification: LogNotification, highlight = true): void {
    const normalized: LogNotification = {
      ...notification,
      timestamp: notification.timestamp ?? new Date().toISOString(),
      message: this.stripHtml(notification.message)
    };

    // Failure inbox only — INFO success chatter does not enter Notifications
    if (!isInboxSeverity(normalized.severity)) {
      return;
    }

    this.latestNotificationSubject.next(normalized);
    this.prependHistorical(normalized);
    this.unreadFailureCountSubject.next(this.historicalNotificationsSubject.value.length);
    if (highlight) {
      this.notificationHighlightSubject.next(true);
    }
  }

  clearNotification(): void {
    this.notificationHighlightSubject.next(false);
    this.latestNotificationSubject.next(null);
  }

  /** Apply a delete that happened in another browser session. */
  applyRemoteDelete(id: number): void {
    this.removeHistoricalById(Number(id));
  }

  /** Apply a clear-all that happened in another browser session. */
  applyRemoteClearAll(): void {
    this.fetchGeneration++;
    this.historicalNotificationsSubject.next([]);
    this.latestNotificationSubject.next(null);
    this.notificationHighlightSubject.next(false);
    this.unreadFailureCountSubject.next(0);
  }

  fetchHistoricalNotifications(): void {
    const generation = ++this.fetchGeneration;
    this.http.get<LogNotification[]>(`${API_CONFIG.BASE_URL}/api/v1/notifications/recent?limit=50`)
      .subscribe({
        next: (notifications) => {
          if (generation !== this.fetchGeneration) {
            return;
          }
          const mapped = (notifications ?? [])
            .filter(n => isInboxSeverity(n.severity))
            .map(n => ({
              ...n,
              id: n.id != null ? Number(n.id) : undefined,
              message: this.stripHtml(n.message),
              timestamp: n.timestamp ?? undefined
            }));
          this.historicalNotificationsSubject.next(mapped);
          this.unreadFailureCountSubject.next(mapped.length);
        },
        error: (err) => {
          console.warn('Failed to fetch historical notifications', err);
        }
      });
  }

  deleteNotification(id: number): Observable<void> {
    const numericId = Number(id);
    this.removeHistoricalById(numericId);
    return this.http.delete(`${API_CONFIG.BASE_URL}/api/v1/notifications/${numericId}`, {
      responseType: 'text'
    }).pipe(
      map(() => undefined),
      catchError(err => {
        console.warn('Failed to delete notification', err);
        this.fetchHistoricalNotifications();
        return of(undefined);
      })
    );
  }

  deleteAllNotifications(): Observable<void> {
    this.fetchGeneration++;
    this.historicalNotificationsSubject.next([]);
    this.latestNotificationSubject.next(null);
    this.notificationHighlightSubject.next(false);
    this.unreadFailureCountSubject.next(0);
    return this.http.delete(`${API_CONFIG.BASE_URL}/api/v1/notifications`, {
      responseType: 'text'
    }).pipe(
      map(() => undefined),
      catchError(err => {
        this.fetchHistoricalNotifications();
        console.warn('Failed to delete all notifications', err);
        return of(undefined);
      })
    );
  }

  /** Remove a client-only (no id) inbox row without a backend round-trip. */
  dismissLocalNotification(notification: LogNotification): void {
    if (notification.id != null) {
      this.deleteNotification(Number(notification.id)).subscribe();
      return;
    }
    const remaining = this.historicalNotificationsSubject.value.filter(n => n !== notification
      && !(n.id == null
        && n.message === notification.message
        && n.timestamp === notification.timestamp
        && n.severity === notification.severity));
    this.historicalNotificationsSubject.next(remaining);
    this.unreadFailureCountSubject.next(remaining.length);
    const active = this.latestNotificationSubject.value;
    if (active && active.id == null
      && active.message === notification.message
      && active.timestamp === notification.timestamp) {
      this.clearNotification();
    }
  }

  formatTimestamp(timestamp?: string): string {
    return formatNotificationTime(timestamp);
  }

  private removeHistoricalById(id: number): void {
    this.fetchGeneration++;
    const remaining = this.historicalNotificationsSubject.value.filter(n => Number(n.id) !== id);
    this.historicalNotificationsSubject.next(remaining);
    this.unreadFailureCountSubject.next(remaining.length);
    const active = this.latestNotificationSubject.value;
    if (active?.id != null && Number(active.id) === id) {
      this.clearNotification();
    }
  }

  private prependHistorical(notification: LogNotification): void {
    const current = this.historicalNotificationsSubject.value;
    if (notification.id != null && current.some(n => Number(n.id) === Number(notification.id))) {
      return;
    }
    this.historicalNotificationsSubject.next([notification, ...current].slice(0, 50));
  }

  private stripHtml(message: string): string {
    return (message ?? '').replace(/<[^>]*>/g, '').trim();
  }
}
