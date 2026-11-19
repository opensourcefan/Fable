import {inject, Injectable, OnDestroy} from '@angular/core';
import {Subject, Subscription} from 'rxjs';
import {debounceTime, skip} from 'rxjs/operators';
import {RxStompService} from '../websocket/rx-stomp.service';
import {MetadataProgressService} from './metadata-progress.service';
import {AiSearchScanProgressService} from './ai-search-scan-progress.service';
import {AuthService} from './auth.service';
import {ReadingSessionService} from './reading-session.service';

const HIDDEN_RESYNC_THRESHOLD_MS = 5_000;
const RECOVERY_DEBOUNCE_MS = 500;

/**
 * Coordinates frontend state reconciliation whenever connectivity returns:
 * - WebSocket STOMP reconnects (subsequent OPEN emissions after initial connect)
 * - Browser 'online' event
 * - Tab becomes visible after being hidden for > 5 seconds
 */
@Injectable({providedIn: 'root'})
export class ConnectionRecoveryService implements OnDestroy {
  private readonly rxStompService = inject(RxStompService);
  private readonly metadataProgressService = inject(MetadataProgressService);
  private readonly aiSearchScanProgressService = inject(AiSearchScanProgressService);
  private readonly authService = inject(AuthService);
  private readonly readingSessionService = inject(ReadingSessionService);

  private readonly recoveryTrigger$ = new Subject<void>();
  private readonly subscriptions = new Subscription();
  private initialized = false;
  private hiddenAt: number | null = null;

  private readonly onOnline = (): void => {
    this.recoveryTrigger$.next();
  };

  private readonly onVisibilityChange = (): void => {
    if (document.hidden) {
      this.hiddenAt = Date.now();
    } else {
      if (this.hiddenAt !== null && Date.now() - this.hiddenAt > HIDDEN_RESYNC_THRESHOLD_MS) {
        this.recoveryTrigger$.next();
      }
      this.hiddenAt = null;
    }
  };

  initialize(): void {
    if (this.initialized) {
      return;
    }
    this.initialized = true;

    const triggerSub = this.recoveryTrigger$
      .pipe(debounceTime(RECOVERY_DEBOUNCE_MS))
      .subscribe(() => this.performResync());
    this.subscriptions.add(triggerSub);

    if (this.rxStompService?.connected$) {
      const stompSub = this.rxStompService.connected$
        .pipe(skip(1))
        .subscribe(() => this.recoveryTrigger$.next());
      this.subscriptions.add(stompSub);
    }

    window.addEventListener('online', this.onOnline);
    document.addEventListener('visibilitychange', this.onVisibilityChange);
  }

  private performResync(): void {
    const hasSession = !!(this.authService.getInternalAccessToken() || this.authService.getInternalRefreshToken());
    if (!hasSession) {
      return;
    }
    this.authService.retryProactiveRefreshIfNeeded?.();
    this.metadataProgressService.refreshNow?.();
    this.aiSearchScanProgressService.reconcileWithServer?.();
    this.readingSessionService.flushPendingSessions?.();
  }

  ngOnDestroy(): void {
    window.removeEventListener('online', this.onOnline);
    document.removeEventListener('visibilitychange', this.onVisibilityChange);
    this.subscriptions.unsubscribe();
    this.recoveryTrigger$.complete();
  }
}

