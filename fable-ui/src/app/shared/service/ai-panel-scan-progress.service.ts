import {inject, Injectable} from '@angular/core';
import {BehaviorSubject, Observable} from 'rxjs';
import {filter} from 'rxjs/operators';
import {MessageService} from 'primeng/api';

import {AiPanelScanProgressPayload} from '../model/ai-panel-scan-progress.model';
import {RxStompService} from '../websocket/rx-stomp.service';
import {RxStompState} from '@stomp/rx-stomp';

const DISCONNECT_GRACE_MS = 30_000;

@Injectable({providedIn: 'root'})
export class AiPanelScanProgressService {
  private readonly messageService = inject(MessageService);
  private readonly rxStompService = inject(RxStompService);
  private readonly progressSubject = new BehaviorSubject<AiPanelScanProgressPayload | null>(null);
  private clearTimer: ReturnType<typeof setTimeout> | undefined;
  private disconnectClearTimer: ReturnType<typeof setTimeout> | undefined;

  readonly progress$ = this.progressSubject.asObservable();

  constructor() {
    this.rxStompService?.connectionState$?.subscribe(state => {
      if (state === RxStompState.OPEN) {
        this.cancelDisconnectClearTimer();
      } else if (this.progressSubject.value && !this.disconnectClearTimer) {
        this.disconnectClearTimer = setTimeout(() => {
          this.disconnectClearTimer = undefined;
          this.progressSubject.next(null);
        }, DISCONNECT_GRACE_MS);
      }
    });
  }

  private cancelDisconnectClearTimer(): void {
    if (this.disconnectClearTimer) {
      clearTimeout(this.disconnectClearTimer);
      this.disconnectClearTimer = undefined;
    }
  }

  handleIncomingProgress(progress: AiPanelScanProgressPayload): void {
    this.cancelDisconnectClearTimer();
    if (this.clearTimer) {
      clearTimeout(this.clearTimer);
      this.clearTimer = undefined;
    }

    this.progressSubject.next(progress);

    if (progress.mode === 'BATCH' && (progress.event === 'COMPLETED' || progress.event === 'FAILED')) {
      this.clearTimer = setTimeout(() => {
        if (this.progressSubject.value === progress) {
          this.progressSubject.next(null);
        }
      }, 5000);
    }
  }

  bookProgress$(bookId: number): Observable<AiPanelScanProgressPayload> {
    return this.progress$.pipe(
      filter((progress): progress is AiPanelScanProgressPayload => !!progress && progress.mode === 'BOOK' && progress.bookId === bookId)
    );
  }

  batchProgress$(): Observable<AiPanelScanProgressPayload> {
    return this.progress$.pipe(
      filter((progress): progress is AiPanelScanProgressPayload => !!progress && progress.mode === 'BATCH')
    );
  }

  buildStatusText(progress: AiPanelScanProgressPayload): string {
    const processedPages = progress.processedPages ?? 0;
    const totalPages = progress.totalPages ?? 0;
    const panelsFound = progress.panelsFound ?? 0;
    const pagesWithPanels = progress.pagesWithPanels ?? 0;

    if (progress.event === 'FAILED') {
      return progress.error || progress.message || 'AI panel scan failed.';
    }

    if (progress.mode === 'BATCH') {
      const completedBooks = progress.completedBooks ?? 0;
      const totalBooks = progress.totalBooks ?? 0;
      return `${completedBooks}/${totalBooks} books, ${processedPages}/${totalPages || processedPages} pages, ${panelsFound} panels across ${pagesWithPanels} pages.`;
    }

    if (totalPages > 0) {
      return `${processedPages}/${totalPages} pages scanned, ${panelsFound} panels across ${pagesWithPanels} pages.`;
    }

    return `${processedPages} pages scanned, ${panelsFound} panels across ${pagesWithPanels} pages.`;
  }

  updateReaderToast(progress: AiPanelScanProgressPayload): void {
    const detail = this.buildStatusText(progress);

    if (progress.event === 'FAILED') {
      this.messageService.clear('ai-scan');
      this.messageService.add({
        key: 'ai-scan',
        severity: 'warn',
        summary: 'AI Panel Detection',
        detail,
        life: 5000
      });
      return;
    }

    if (progress.event === 'COMPLETED') {
      this.messageService.clear('ai-scan');
      this.messageService.add({
        key: 'ai-scan',
        severity: 'success',
        summary: 'AI Panel Detection',
        detail,
        life: 3500
      });
      return;
    }

    this.messageService.clear('ai-scan');
    this.messageService.add({
      key: 'ai-scan',
      severity: 'info',
      summary: 'AI is scanning panels',
      detail,
      sticky: true
    });
  }
}