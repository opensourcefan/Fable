import {Component, inject, OnDestroy, OnInit, ViewChild} from '@angular/core';
import {Title} from '@angular/platform-browser';
import {environment} from '../environments/environment';
import {RxStompService} from './shared/websocket/rx-stomp.service';
import {BookService} from './features/book/service/book.service';
import {NotificationEventService} from './shared/websocket/notification-event.service';
import {parseLogNotification, parseLogNotificationSyncEvent} from './shared/websocket/model/log-notification.model';
import {ConfirmDialog} from 'primeng/confirmdialog';
import {Toast} from 'primeng/toast';
import {NavigationEnd, Router, RouterOutlet} from '@angular/router';
import {TranslocoDirective, TranslocoPipe} from '@jsverse/transloco';
import {AuthInitializationService} from './core/security/auth-initialization-service';
import {AppConfigService} from './shared/service/app-config.service';
import {MetadataBatchProgressNotification} from './shared/model/metadata-batch-progress.model';
import {MetadataProgressService} from './shared/service/metadata-progress.service';
import {BookdropFileNotification, BookdropFileService} from './features/bookdrop/service/bookdrop-file.service';
import {Subscription} from 'rxjs';
import {TaskProgressPayload, TaskService, TaskStatus, TaskType} from './features/settings/task-management/task.service';
import {LibraryService} from './features/book/service/library.service';
import {LibraryHealthService} from './features/book/service/library-health.service';
import {LibraryLoadingService} from './features/library-creator/library-loading.service';
import {filter, scan, withLatestFrom} from 'rxjs/operators';
import {AuthService} from './shared/service/auth.service';
import {AiPanelScanProgressPayload} from './shared/model/ai-panel-scan-progress.model';
import {AiPanelScanProgressService} from './shared/service/ai-panel-scan-progress.service';
import {AiSearchProgressPayload, AiSearchScanProgressService} from './shared/service/ai-search-scan-progress.service';
import {PagedGridPilotService} from './features/book/service/paged-grid-pilot.service';
import {LoadingIndicatorComponent} from './shared/components/loading-indicator/loading-indicator.component';
import {ConfirmationService} from 'primeng/api';
import {MobileBackHandle, MobileBackNavigationService} from './shared/service/mobile-back-navigation.service';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
  standalone: true,
  imports: [ConfirmDialog, Toast, RouterOutlet, TranslocoDirective, TranslocoPipe, LoadingIndicatorComponent]
})
export class AppComponent implements OnInit, OnDestroy {

  loading = true;
  offline = false;
  private subscriptions: Subscription[] = [];
  private subscriptionsInitialized = false;

  private titleService = inject(Title);
  private appConfigService = inject(AppConfigService); // DO NOT REMOVE: Used to initialize app config on startup
  private authInit = inject(AuthInitializationService);
  private bookService = inject(BookService);
  private rxStompService = inject(RxStompService);
  private notificationEventService = inject(NotificationEventService);
  private metadataProgressService = inject(MetadataProgressService);
  private bookdropFileService = inject(BookdropFileService);
  private taskService = inject(TaskService);
  private libraryService = inject(LibraryService);
  private libraryHealthService = inject(LibraryHealthService);
  private libraryLoadingService = inject(LibraryLoadingService);
  private authService = inject(AuthService);
  private aiPanelScanProgressService = inject(AiPanelScanProgressService);
  private aiSearchScanProgressService = inject(AiSearchScanProgressService);
  private pagedGridPilotService = inject(PagedGridPilotService);
  private router = inject(Router);
  private confirmationService = inject(ConfirmationService);
  private mobileBackNavigation = inject(MobileBackNavigationService);
  private tooltipSuppressTimer: ReturnType<typeof setTimeout> | null = null;
  private confirmBackHandle: MobileBackHandle | null = null;

  @ViewChild(ConfirmDialog) private confirmDialog?: ConfirmDialog;

  ngOnInit(): void {
    // Migrate legacy local storage keys from Fable to Fable
    const legacyKeys = [
      { old: 'booklore.aiScanSelectedPathIds', new: 'fable.aiScanSelectedPathIds' },
      { old: 'booklore.aiScanLibraryFilterIds', new: 'fable.aiScanLibraryFilterIds' },
      { old: 'booklore.cbx.mobileJoystick.v1', new: 'fable.cbx.mobileJoystick.v1' }
    ];
    legacyKeys.forEach(key => {
      try {
        const value = localStorage.getItem(key.old);
        if (value !== null) {
          localStorage.setItem(key.new, value);
          localStorage.removeItem(key.old);
        }
      } catch (e) {
        console.warn(`Failed to migrate local storage key ${key.old}:`, e);
      }
    });

    this.titleService.setTitle(environment.appName || 'Fable');
    window.addEventListener('online', this.onOnline);
    window.addEventListener('offline', this.onOffline);

    this.authInit.initialized$.subscribe(ready => {
      this.loading = !ready;
      if (ready && !this.subscriptionsInitialized) {
        this.setupWebSocketSubscriptions();
        this.libraryHealthService.initialize();
        this.subscriptionsInitialized = true;
      }
    });

    // Suppress tooltips for 500ms after each navigation to prevent touch
    // event bleed-through from the previous page triggering spurious tooltips
    // on the new page at the same screen coordinates.
    this.subscriptions.push(
      this.router.events.pipe(
        filter(e => e instanceof NavigationEnd)
      ).subscribe(() => {
        document.body.classList.add('tooltip-suppressed');
        if (this.tooltipSuppressTimer !== null) {
          clearTimeout(this.tooltipSuppressTimer);
        }
        this.tooltipSuppressTimer = setTimeout(() => {
          document.body.classList.remove('tooltip-suppressed');
          this.tooltipSuppressTimer = null;
        }, 500);
      })
    );

    // System back dismisses the global confirm dialog before leaving the route.
    this.subscriptions.push(
      this.confirmationService.requireConfirmation$.subscribe(confirmation => {
        if (!confirmation || this.confirmBackHandle) {
          return;
        }
        this.confirmBackHandle = this.mobileBackNavigation.register(() => {
          this.confirmDialog?.onReject();
        });
      })
    );
  }

  onConfirmDialogHide(): void {
    this.confirmBackHandle?.release();
    this.confirmBackHandle = null;
  }

  private onOnline = () => {
    this.offline = false;
  };

  private onOffline = () => {
    this.checkServerReachable().then(reachable => {
      this.offline = !reachable;
    });
  };

  private checkServerReachable(): Promise<boolean> {
    return fetch('/api/public/settings', {method: 'HEAD', cache: 'no-store'})
      .then(() => true)
      .catch(() => false);
  }

  reload(): void {
    window.location.reload();
  }

  private refreshBookStateAndPagedViews(): void {
    this.bookService.refreshBooks().subscribe(() => {
      this.pagedGridPilotService.invalidateAllBooksCache();
    });
  }

  private setupWebSocketSubscriptions(): void {
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/book-add').pipe(
        withLatestFrom(this.libraryService.largeLibraryLoading$),
        scan((acc, [msg, loadingState]) => {
          const book = JSON.parse(msg.body);
          if (loadingState.isLoading) {
            const newCount = acc.count + 1;
            this.libraryLoadingService.showBookLoadingProgress(book.metadata?.title || 'Unknown Book', newCount, loadingState.expectedCount);
            this.bookService.handleNewlyCreatedBook(book);
            this.pagedGridPilotService.appendNewBook(book);
            if (newCount >= loadingState.expectedCount) {
              this.libraryService.setLargeLibraryLoading(false, 0);
              return {count: 0};
            }
            return {count: newCount};
          } else {
            this.bookService.handleNewlyCreatedBook(book);
            this.pagedGridPilotService.appendNewBook(book);
            return {count: 0};
          }
        }, {count: 0})
      ).subscribe()
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/book-update').subscribe(msg =>
        this.bookService.handleBookUpdate(JSON.parse(msg.body))
      )
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/books-cover-update').subscribe(msg =>
        this.bookService.handleMultipleBookCoverPatches(JSON.parse(msg.body))
      )
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/books-remove').subscribe(msg =>
        this.bookService.handleRemovedBookIds(JSON.parse(msg.body))
      )
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/book-metadata-update').subscribe(msg =>
        this.bookService.handleBookUpdate(JSON.parse(msg.body))
      )
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/book-metadata-batch-update').subscribe(msg =>
        this.bookService.handleMultipleBookUpdates(JSON.parse(msg.body))
      )
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/book-metadata-batch-progress').subscribe(msg =>
        this.metadataProgressService.handleIncomingProgress(JSON.parse(msg.body) as MetadataBatchProgressNotification)
      )
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/log').subscribe(msg => {
        const sync = parseLogNotificationSyncEvent(msg.body);
        if (sync?.action === 'DELETED' && sync.id != null) {
          this.notificationEventService.applyRemoteDelete(sync.id);
          return;
        }
        if (sync?.action === 'CLEARED') {
          this.notificationEventService.applyRemoteClearAll();
          return;
        }
        const logNotification = parseLogNotification(msg.body);
        this.notificationEventService.handleNewNotification(logNotification);
      })
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/bookdrop-file').subscribe(msg => {
        const notification = JSON.parse(msg.body) as BookdropFileNotification;
        this.bookdropFileService.handleIncomingFile(notification);
      })
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/task-progress').subscribe(msg => {
        const progress = JSON.parse(msg.body) as TaskProgressPayload;
        this.taskService.handleTaskProgress(progress);
        if (
          (progress.taskType === TaskType.SYNC_LIBRARY_FILES ||
            progress.taskType === TaskType.DIRECTORY_TAGGING ||
            progress.taskType === TaskType.BOOKDROP_PERIODIC_SCANNING) &&
          progress.taskStatus === TaskStatus.COMPLETED
        ) {
          this.refreshBookStateAndPagedViews();
        }
      })
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/ai-panel-scan-progress').subscribe(msg => {
        const progress = JSON.parse(msg.body) as AiPanelScanProgressPayload;
        this.aiPanelScanProgressService.handleIncomingProgress(progress);
      })
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/ai-search-progress').subscribe(msg => {
        const progress = JSON.parse(msg.body) as AiSearchProgressPayload;
        this.aiSearchScanProgressService.handleIncomingProgress(progress);
        if (progress.event === 'COMPLETED') {
          this.refreshBookStateAndPagedViews();
        }
      })
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/session-revoked').subscribe(() => {
        this.authService.forceLogout('session_revoked');
      })
    );
  }

  ngOnDestroy(): void {
    window.removeEventListener('online', this.onOnline);
    window.removeEventListener('offline', this.onOffline);
    this.subscriptions.forEach(sub => sub.unsubscribe());
    this.confirmBackHandle?.release(false);
    this.confirmBackHandle = null;
    this.libraryLoadingService.hide();
  }
}