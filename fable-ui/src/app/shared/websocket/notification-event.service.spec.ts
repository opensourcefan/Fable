import {TestBed} from '@angular/core/testing';
import {HttpClientTestingModule, HttpTestingController} from '@angular/common/http/testing';
import {describe, expect, it, afterEach} from 'vitest';
import {NotificationEventService} from './notification-event.service';
import {Severity} from './model/log-notification.model';
import {API_CONFIG} from '../../core/config/api-config';

describe('NotificationEventService', () => {
  let service: NotificationEventService;
  let http: HttpTestingController;
  let latestHistorical: unknown[] = [];
  let latestCount = 0;

  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
  });

  function setup(): void {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [NotificationEventService],
    });
    service = TestBed.inject(NotificationEventService);
    http = TestBed.inject(HttpTestingController);
    service.historicalNotifications$.subscribe(v => {
      latestHistorical = v;
    });
    service.unreadFailureCount$.subscribe(v => {
      latestCount = v;
    });
  }

  it('optimistically removes a notification and ignores a stale fetch that races the delete', () => {
    setup();

    service.handleNewNotification({
      id: 7,
      message: 'Boom',
      severity: Severity.ERROR,
      timestamp: '2026-07-21T00:00:00.000Z',
    });
    expect(latestHistorical).toHaveLength(1);
    expect(latestCount).toBe(1);

    service.fetchHistoricalNotifications();
    const staleFetch = http.expectOne(`${API_CONFIG.BASE_URL}/api/v1/notifications/recent?limit=50`);

    service.deleteNotification(7).subscribe();
    http.expectOne(`${API_CONFIG.BASE_URL}/api/v1/notifications/7`).flush('', {status: 204, statusText: 'No Content'});
    expect(latestHistorical).toHaveLength(0);
    expect(latestCount).toBe(0);

    staleFetch.flush([{
      id: 7,
      message: 'Boom',
      severity: 'ERROR',
      timestamp: '2026-07-21T00:00:00.000Z',
    }]);
    expect(latestHistorical).toHaveLength(0);
    expect(latestCount).toBe(0);
  });

  it('dismisses client-only notifications without an id', () => {
    setup();
    const local = {
      message: 'Client only failure',
      severity: Severity.WARN,
      timestamp: '2026-07-21T01:00:00.000Z',
    };
    service.handleNewNotification(local);
    expect(latestHistorical).toHaveLength(1);

    service.dismissLocalNotification(latestHistorical[0] as never);
    expect(latestHistorical).toHaveLength(0);
    expect(latestCount).toBe(0);
  });

  it('applies remote delete and clear-all without an HTTP round-trip', () => {
    setup();
    service.handleNewNotification({
      id: 11,
      message: 'Remote boom',
      severity: Severity.ERROR,
      timestamp: '2026-07-21T02:00:00.000Z',
    });
    expect(latestHistorical).toHaveLength(1);

    service.applyRemoteDelete(11);
    expect(latestHistorical).toHaveLength(0);
    expect(latestCount).toBe(0);

    service.handleNewNotification({
      id: 12,
      message: 'Another',
      severity: Severity.WARN,
      timestamp: '2026-07-21T03:00:00.000Z',
    });
    service.applyRemoteClearAll();
    expect(latestHistorical).toHaveLength(0);
    expect(latestCount).toBe(0);
  });
});
