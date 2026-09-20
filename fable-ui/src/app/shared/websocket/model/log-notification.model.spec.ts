import {describe, expect, it} from 'vitest';
import {
  parseLogNotification,
  parseLogNotificationSyncEvent,
  Severity
} from './log-notification.model';

describe('log-notification.model sync parsing', () => {
  it('parses DELETED and CLEARED sync events', () => {
    expect(parseLogNotificationSyncEvent(JSON.stringify({action: 'DELETED', id: 9})))
      .toEqual({action: 'DELETED', id: 9});
    expect(parseLogNotificationSyncEvent(JSON.stringify({action: 'CLEARED'})))
      .toEqual({action: 'CLEARED'});
  });

  it('returns null for normal failure payloads so they stay inbox creates', () => {
    expect(parseLogNotificationSyncEvent(JSON.stringify({
      id: 3,
      message: 'Boom',
      severity: 'ERROR',
    }))).toBeNull();

    const created = parseLogNotification(JSON.stringify({
      id: 3,
      message: 'Boom',
      severity: 'ERROR',
      timestamp: '2026-07-21T00:00:00Z',
    }));
    expect(created.severity).toBe(Severity.ERROR);
    expect(created.message).toBe('Boom');
  });

  it('rejects DELETED without an id', () => {
    expect(parseLogNotificationSyncEvent(JSON.stringify({action: 'DELETED'}))).toBeNull();
  });
});
