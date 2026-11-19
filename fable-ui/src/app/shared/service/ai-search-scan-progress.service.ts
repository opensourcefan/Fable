import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {BehaviorSubject, Observable} from 'rxjs';
import {filter} from 'rxjs/operators';
import {FailureNotificationService} from './failure-notification.service';
import {RxStompService} from '../websocket/rx-stomp.service';
import {RxStompState} from '@stomp/rx-stomp';
import {API_CONFIG} from '../../core/config/api-config';

const DISCONNECT_GRACE_MS = 30_000;

export interface AiSearchScanStatusResponse {
  running: boolean;
  progress: AiSearchProgressPayload | null;
}

export interface AiSearchProgressPayload {
  mode: 'SINGLE' | 'BATCH';
  event: 'STARTED' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' | 'STOPPED';
  message: string;
  error?: string;
  current?: number;
  total?: number;
  importedBooks?: string[];
  failedBooks?: string[];
}

@Injectable({providedIn: 'root'})
export class AiSearchScanProgressService {
  private readonly failureNotifications = inject(FailureNotificationService);
  private readonly rxStompService = inject(RxStompService);
  private readonly http = inject(HttpClient, {optional: true});
  private readonly progressSubject = new BehaviorSubject<AiSearchProgressPayload | null>(null);
  private readonly embeddingBookIdsSubject = new BehaviorSubject<Set<number>>(new Set());
  private readonly isStoppingSubject = new BehaviorSubject<boolean>(false);
  private clearTimer: ReturnType<typeof setTimeout> | undefined;
  private disconnectClearTimer: ReturnType<typeof setTimeout> | undefined;

  readonly progress$ = this.progressSubject.asObservable();
  readonly embeddingBookIds$ = this.embeddingBookIdsSubject.asObservable();
  readonly isStopping$ = this.isStoppingSubject.asObservable();

  constructor() {
    this.rxStompService?.connectionState$?.subscribe(state => {
      if (state === RxStompState.OPEN) {
        this.cancelDisconnectClearTimer();
      } else if (this.progressSubject.value && !this.disconnectClearTimer) {
        // Keep progress visible during brief network blips; clear if disconnected > 30s.
        this.disconnectClearTimer = setTimeout(() => {
          this.disconnectClearTimer = undefined;
          this.progressSubject.next(null);
          this.isStoppingSubject.next(false);
        }, DISCONNECT_GRACE_MS);
      }
    });
  }

  /** Reconciles local AI search progress card with server truth after reconnect. */
  reconcileWithServer(): void {
    if (!this.http) {
      return;
    }
    this.http.get<AiSearchScanStatusResponse>(`${API_CONFIG.BASE_URL}/api/v1/ai/search/scan-status`).subscribe({
      next: (status) => {
        if (status?.running && status.progress) {
          this.handleIncomingProgress(status.progress);
        } else if (!status?.running) {
          const current = this.progressSubject.value;
          if (current && (current.event === 'STARTED' || current.event === 'IN_PROGRESS')) {
            this.clearProgress();
            this.isStoppingSubject.next(false);
          }
        }
      },
      error: () => {
        // Ignore transient failure; next reconnect will reconcile again.
      }
    });
  }

  private cancelDisconnectClearTimer(): void {
    if (this.disconnectClearTimer) {
      clearTimeout(this.disconnectClearTimer);
      this.disconnectClearTimer = undefined;
    }
  }

  setStopping(stopping: boolean): void {
    this.isStoppingSubject.next(stopping);
  }

  clearProgress(): void {
    this.cancelDisconnectClearTimer();
    if (this.clearTimer) {
      clearTimeout(this.clearTimer);
      this.clearTimer = undefined;
    }
    this.progressSubject.next(null);
  }

  /** True when a finished BATCH card is visible and can be dismissed. */
  hasDismissableFinishedBatch(): boolean {
    if (this.isStoppingSubject.value) {
      return false;
    }
    const progress = this.progressSubject.value;
    return !!progress
      && progress.mode === 'BATCH'
      && (progress.event === 'COMPLETED' || progress.event === 'FAILED' || progress.event === 'STOPPED');
  }

  /** Dismiss a finished BATCH card; no-op while running or stopping. */
  dismissFinishedBatch(): boolean {
    if (!this.hasDismissableFinishedBatch()) {
      return false;
    }
    this.clearProgress();
    return true;
  }

  handleIncomingProgress(progress: AiSearchProgressPayload): void {
    this.cancelDisconnectClearTimer();
    if (this.clearTimer) {
      clearTimeout(this.clearTimer);
      this.clearTimer = undefined;
    }

    if (progress.mode === 'BATCH' && (progress.event === 'STOPPED' || progress.event === 'COMPLETED' || progress.event === 'FAILED')) {
      this.isStoppingSubject.next(false);
    }

    this.progressSubject.next(progress);

    if (progress.mode === 'BATCH' && (progress.event === 'COMPLETED' || progress.event === 'FAILED')) {
      this.clearTimer = setTimeout(() => {
        if (this.progressSubject.value === progress) {
          this.progressSubject.next(null);
        }
      }, 5000);
    }

    // Persist terminal failures into the durable Notifications inbox (with id)
    // so dismiss survives reload. Ephemeral progress stays on the Tasks tab.
    // Intentional STOPPED stays Tasks-only (not a failure).
    if (progress.event === 'FAILED') {
      const detail = this.buildStatusText(progress);
      const completionSummary = this.buildCompletionSummary(progress);
      this.failureNotifications.reportSafe('AI Search failed', completionSummary || detail);
    }

    this.updateReaderToast(progress);
  }

  batchProgress$(): Observable<AiSearchProgressPayload> {
    return this.progress$.pipe(
      filter((progress): progress is AiSearchProgressPayload => !!progress && progress.mode === 'BATCH')
    );
  }

  singleProgress$(): Observable<AiSearchProgressPayload> {
    return this.progress$.pipe(
      filter((progress): progress is AiSearchProgressPayload => !!progress && progress.mode === 'SINGLE')
    );
  }

  buildStatusText(progress: AiSearchProgressPayload): string {
    if (progress.event === 'FAILED') {
      return progress.error || progress.message || 'AI search embedding failed.';
    }

    if (progress.mode === 'BATCH') {
      const current = progress.current ?? 0;
      const total = progress.total ?? 0;
      if (progress.event === 'STARTED' || total === 0) {
        return progress.message;
      }
      return `${current}/${total} books embedded. ${progress.message}`;
    }

    return progress.message;
  }

  buildCompletionSummary(progress: AiSearchProgressPayload): string {
    const imported = progress.importedBooks;
    const failed = progress.failedBooks;
    const parts: string[] = [];

    if (imported && imported.length > 0) {
      const count = imported.length;
      const titles = imported.slice(0, 3).join(', ');
      const suffix = imported.length > 3 ? ` and ${imported.length - 3} more` : '';
      parts.push(`Imported (${count}): ${titles}${suffix}`);
    }

    if (failed && failed.length > 0) {
      const count = failed.length;
      const titles = failed.slice(0, 3).join(', ');
      const suffix = failed.length > 3 ? ` and ${failed.length - 3} more` : '';
      parts.push(`Failed (${count}): ${titles}${suffix}`);
    }

    return parts.length > 0 ? parts.join('. ') : '';
  }

  updateReaderToast(_progress: AiSearchProgressPayload): void {
    // Toast notifications are disabled as progress notifications are logged in the topbar dropdown instead.
  }
}
