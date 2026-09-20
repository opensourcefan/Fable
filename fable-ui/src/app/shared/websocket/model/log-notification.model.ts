export enum Severity {
  INFO = 'INFO',
  WARN = 'WARN',
  ERROR = 'ERROR'
}

export type LogNotificationSyncAction = 'DELETED' | 'CLEARED';

export interface LogNotification {
  id?: number;
  timestamp?: string;
  message: string;
  severity?: Severity;
}

export interface LogNotificationSyncEvent {
  action: LogNotificationSyncAction;
  id?: number;
}

export function parseLogNotification(messageBody: string): LogNotification {
  const raw = JSON.parse(messageBody);
  return {
    id: raw.id != null ? Number(raw.id) : undefined,
    timestamp: raw.timestamp ? new Date(raw.timestamp).toISOString() : undefined,
    message: typeof raw.message === 'string' ? raw.message : String(raw.message ?? ''),
    severity: raw.severity ? Severity[raw.severity as keyof typeof Severity] : undefined
  };
}

/** Returns a sync event when the LOG payload is a delete/clear, otherwise null. */
export function parseLogNotificationSyncEvent(messageBody: string): LogNotificationSyncEvent | null {
  const raw = JSON.parse(messageBody);
  if (raw?.action === 'DELETED') {
    if (raw.id == null) {
      return null;
    }
    return {action: 'DELETED', id: Number(raw.id)};
  }
  if (raw?.action === 'CLEARED') {
    return {action: 'CLEARED'};
  }
  return null;
}

export function isInboxSeverity(severity?: Severity | string): boolean {
  return severity === Severity.ERROR || severity === Severity.WARN || severity === 'ERROR' || severity === 'WARN';
}

export function formatNotificationTime(timestamp?: string): string {
  if (!timestamp) {
    return '';
  }
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) {
    return timestamp;
  }
  return date.toLocaleString();
}
