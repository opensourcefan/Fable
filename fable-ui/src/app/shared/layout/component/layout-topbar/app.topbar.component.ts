import {ToolbarConfigService, ToolbarItem} from './toolbar-config.service';
import {ToolbarEditorComponent} from './toolbar-editor.component';
import {AppSidebarComponent} from '../layout-sidebar/app.sidebar.component';
import {Component, ElementRef, HostListener, OnDestroy, ViewChild, inject} from '@angular/core';
import {MenuItem} from 'primeng/api';
import {LayoutService} from '../layout-main/service/app.layout.service';
import {NavigationStart, Router, RouterLink} from '@angular/router';
import {DynamicDialogRef} from 'primeng/dynamicdialog';
import {TooltipModule} from 'primeng/tooltip';
import {FormsModule} from '@angular/forms';
import {InputTextModule} from 'primeng/inputtext';
import {BookSearcherComponent} from '../../../../features/book/components/book-searcher/book-searcher.component';
import {AsyncPipe, NgClass, NgStyle} from '@angular/common';
import {NotificationEventService} from '../../../websocket/notification-event.service';
import {Button} from 'primeng/button';
import {StyleClass} from 'primeng/styleclass';
import {Divider} from 'primeng/divider';
import {ThemeConfiguratorComponent} from '../theme-configurator/theme-configurator.component';
import {AuthService} from '../../../service/auth.service';
import {UserService} from '../../../../features/settings/user-management/user.service';
import {Popover} from 'primeng/popover';
import {MetadataProgressService} from '../../../service/metadata-progress.service';
import {filter, takeUntil, catchError} from 'rxjs/operators';
import {Subject, Subscription, of, interval} from 'rxjs';
import {
  MetadataBatchPhase,
  MetadataBatchProgressNotification
} from '../../../model/metadata-batch-progress.model';
import {BookdropFileService} from '../../../../features/bookdrop/service/bookdrop-file.service';
import {DialogLauncherService} from '../../../services/dialog-launcher.service';
import {UnifiedNotificationBoxComponent} from '../../../components/unified-notification-popover/unified-notification-popover-component';
import {Severity, LogNotification} from '../../../websocket/model/log-notification.model';
import {Dialog} from 'primeng/dialog';
import {Menu} from 'primeng/menu';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {AVAILABLE_LANGS, LANG_LABELS} from '../../../../core/config/transloco-loader';
import {LANG_STORAGE_KEY} from '../../../../core/config/language-initializer';
import {SidebarFilterTogglePrefService} from '../../../../features/book/components/book-browser/filters/sidebar-filter-toggle-pref.service';
import {AiPanelScanProgressPayload} from '../../../model/ai-panel-scan-progress.model';
import {AiPanelScanProgressService} from '../../../service/ai-panel-scan-progress.service';
import {TaskProgressPayload, TaskService, TaskStatus, TaskType} from '../../../../features/settings/task-management/task.service';
import {WriteProgressPayload, WriteProgressService} from '../../../../shared/service/write-progress.service';
import {SidecarBackupProgressService} from '../../../service/sidecar-backup-progress.service';
import {MetadataTaskLog, MetadataTaskService} from '../../../../features/book/service/metadata-task';
import {MobileBackHandle, MobileBackNavigationService} from '../../../service/mobile-back-navigation.service';
import {MobileUxService} from '../../../../core/services/mobile-ux.service';
import {ResizableDividerDirective} from '../../../directives/resizable-divider.directive';
import {AiSearchDialogComponent, AiSearchDialogService} from '../../../../features/book/components/ai-search-dialog/ai-search-dialog.component';
import {AppSettingsService} from '../../../service/app-settings.service';
import {AiSearchProgressPayload, AiSearchScanProgressService} from '../../../service/ai-search-scan-progress.service';
import {
  toggleAppFullscreen,
  addFullscreenChangeListener,
  isAppFullscreen,
  clearFullscreenTransientPointerUi
} from '../../../util/fullscreen.util';
import {GhostClickGuard, OVERLAY_GHOST_CLICK_MS} from '../../../util/overlay-dismiss.util';
import {selectTopbarMetadataPhase} from './topbar-metadata-phase.util';

@Component({
  selector: 'app-topbar',
  templateUrl: './app.topbar.component.html',
  styleUrls: ['./app.topbar.component.scss'],
  standalone: true,
  imports: [
    ToolbarEditorComponent,
    RouterLink,
    TooltipModule,
    FormsModule,
    InputTextModule,
    BookSearcherComponent,
    Button,
    ThemeConfiguratorComponent,
    StyleClass,
    NgClass,
    Divider,
    AsyncPipe,
    Popover,
    UnifiedNotificationBoxComponent,
    NgStyle,
    Menu,
    Dialog,
    TranslocoDirective,
    AppSidebarComponent,
    Popover,
    ResizableDividerDirective,
    AiSearchDialogComponent,
  ],
})
export class AppTopBarComponent implements OnDestroy {
  private static readonly METADATA_FETCH_RESUME_AT_PATTERN = /resets at\s+([^.]+)\.?/i;

  items!: MenuItem[];
  ref?: DynamicDialogRef;
  statsMenuItems: MenuItem[] = [];

  @ViewChild('menubutton') menuButton!: ElementRef;
  @ViewChild('topbarmenubutton') topbarMenuButton!: ElementRef;
  @ViewChild('topbarmenu') menu!: ElementRef;
  @ViewChild('statsMenu') statsMenu: Menu | undefined;
  @ViewChild('aiSearchDialog') aiSearchDialog!: AiSearchDialogComponent;
  @ViewChild('mobileBookSearcher') mobileBookSearcher?: BookSearcherComponent;
  @ViewChild('mobileSidebarPop') mobileSidebarPop: Popover | undefined;
  @ViewChild('mobileDirPop') mobileDirPop: Popover | undefined;
  @ViewChild('mobileMenu') mobileMenuPop: Popover | undefined;
  @ViewChild('notificationPopover') notificationPopover: Popover | undefined;
  @ViewChild('notificationPopoverMobile') notificationPopoverMobile: Popover | undefined;

  isMenuVisible = true;
  mobileSearchVisible = false;
  /** Delayed so a desktop-touch ghost click cannot slam the search dialog shut (and drop the OSK). */
  mobileSearchDismissableMask = false;
  private readonly mobileSearchGhostGuard = new GhostClickGuard();
  private mobileSearchMaskTimer: ReturnType<typeof setTimeout> | null = null;
  progressHighlight = false;
  completedTaskCount = 0;
  unreadFailureCount = 0;
  hasActiveOrCompletedTasks = false;
  showPulse = false;
  hasAnyTasks = false;
  hasPendingBookdropFiles = false;
  showMobileBookFilterTrigger = false;
  showMobileDirTrigger = false;
  mobileDirectoryPopoverOpen = false;
  mobileSidebarOpen = false;
  mobileOverflowOpen = false;
  aiSearchEnabled = false;
  aiBatchProgress: AiPanelScanProgressPayload | null = null;
  aiSearchBatchProgress: AiSearchProgressPayload | null = null;
  aiSearchSingleProgress: AiSearchProgressPayload | null = null;
  isAiSearchStopping = false;
  metadataFlushProgress: TaskProgressPayload | null = null;
  importScanProgress: TaskProgressPayload | null = null;
  directoryTaggingProgress: TaskProgressPayload | null = null;
  metadataFetchProgress: TaskProgressPayload | null = null;
  metadataFetchLogVisible = false;
  metadataFetchLogLoading = false;
  metadataFetchLog: MetadataTaskLog | null = null;
  metadataFetchLogError = '';
  writeProgress: WriteProgressPayload | null = null;
  isSidecarBackupRunning = false;
  isFullscreen = false;
  private removeFullscreenChangeListener: (() => void) | null = null;

  searchStatus: 'READY' | 'STARTING' | 'ERROR' = 'READY';
  isSearchActive = false;
  isSearchError = false;
  isBatchEmbedding = false;
  private pollingSub?: Subscription;
  private searchActiveSub?: Subscription;
  private searchErrorSub?: Subscription;
  private embeddingProgressSub?: Subscription;

  private eventTimer: number | undefined;
  private flushDismissTimer: ReturnType<typeof setTimeout> | undefined;
  private importDismissTimer: ReturnType<typeof setTimeout> | undefined;
  private directoryTagDismissTimer: ReturnType<typeof setTimeout> | undefined;
  private metadataFetchDismissTimer: ReturnType<typeof setTimeout> | undefined;
  private topbarIsbnFailedTimer: ReturnType<typeof setTimeout> | undefined;
  private writeDismissTimer: ReturnType<typeof setTimeout> | undefined;
  private aiSearchSingleDismissTimer: ReturnType<typeof setTimeout> | undefined;
  private destroy$ = new Subject<void>();

  private latestTasks: Record<string, MetadataBatchProgressNotification> = {};
  private topbarMetadataPhase: MetadataBatchPhase | null = null;
  private topbarIsbnFailedLockedUntil = 0;
  private latestHasPendingFiles = false;
  private latestNotificationSeverity?: Severity;
  hasActiveLogNotification = false;
  private mobileSidebarBackHandle: MobileBackHandle | null = null;
  private mobileDirectoryBackHandle: MobileBackHandle | null = null;
  private mobileOverflowBackHandle: MobileBackHandle | null = null;
  private mobileSearchBackHandle: MobileBackHandle | null = null;
  private metadataFetchLogBackHandle: MobileBackHandle | null = null;
  private mobileNotificationBackHandle: MobileBackHandle | null = null;

  activeLang = '';
  langMenuItems: MenuItem[] = [];

  private translocoService = inject(TranslocoService);

  public layoutService = inject(LayoutService);
  public toolbarConfig = inject(ToolbarConfigService);
  private notificationService = inject(NotificationEventService);
  private router = inject(Router);
  private authService = inject(AuthService);
  protected userService = inject(UserService);
  private metadataProgressService = inject(MetadataProgressService);
  private bookdropFileService = inject(BookdropFileService);
  private dialogLauncher = inject(DialogLauncherService);
  private sidebarFilterTogglePrefService = inject(SidebarFilterTogglePrefService);
  private aiPanelScanProgressService = inject(AiPanelScanProgressService);
  private taskService = inject(TaskService);
  private writeProgressService = inject(WriteProgressService);
  private sidecarBackupProgressService = inject(SidecarBackupProgressService);
  private metadataTaskService = inject(MetadataTaskService);
  private mobileBackNavigation = inject(MobileBackNavigationService);
  public mobileUx = inject(MobileUxService);
  private appSettingsService = inject(AppSettingsService);
  private aiSearchScanProgressService = inject(AiSearchScanProgressService);
  private aiSearchDialogService = inject(AiSearchDialogService);

  constructor() {
    this.updateMobileBookFilterTriggerVisibility(this.router.url);
    this.syncFullscreenState();
    this.activeLang = this.translocoService.getActiveLang();
    this.langMenuItems = AVAILABLE_LANGS.map(lang => ({
      label: LANG_LABELS[lang] || lang,
      icon: lang === this.activeLang ? 'pi pi-check' : undefined,
      command: () => this.switchLanguage(lang),
    }));

    this.subscribeToMetadataProgress();
    this.subscribeToNotifications();

    this.metadataProgressService.activeTasks$
      .pipe(takeUntil(this.destroy$))
      .subscribe((tasks) => {
        this.latestTasks = tasks;
        this.syncTopbarMetadataPhase();
        this.hasAnyTasks = Object.keys(tasks).length > 0;
        this.updateCompletedTaskCount();
        this.updateTaskVisibility(tasks);
        this.checkNotificationPopoverClose();
      });

    this.bookdropFileService.hasPendingFiles$
      .pipe(takeUntil(this.destroy$))
      .subscribe((hasPending) => {
        this.latestHasPendingFiles = hasPending;
        this.hasPendingBookdropFiles = hasPending;
        this.updateCompletedTaskCount();
        this.updateTaskVisibilityWithBookdrop();
        this.checkNotificationPopoverClose();
      });

    this.aiPanelScanProgressService.progress$
      .pipe(takeUntil(this.destroy$))
      .subscribe(progress => {
        this.aiBatchProgress = progress?.mode === 'BATCH' ? progress : null;
      });

    this.aiSearchScanProgressService.progress$
      .pipe(takeUntil(this.destroy$))
      .subscribe(progress => {
        if (!progress) {
          this.aiSearchBatchProgress = null;
          this.aiSearchSingleProgress = null;
        } else if (progress.mode === 'BATCH') {
          this.aiSearchBatchProgress = progress;
        } else if (progress.mode === 'SINGLE') {
          this.aiSearchSingleProgress = progress;
          if (progress.event === 'COMPLETED' || progress.event === 'FAILED') {
            clearTimeout(this.aiSearchSingleDismissTimer);
            this.aiSearchSingleDismissTimer = setTimeout(() => {
              this.aiSearchSingleProgress = null;
            }, 5000);
          } else {
            clearTimeout(this.aiSearchSingleDismissTimer);
          }
        }
        this.checkNotificationPopoverClose();
      });

    this.aiSearchScanProgressService.isStopping$
      .pipe(takeUntil(this.destroy$))
      .subscribe(stopping => {
        this.isAiSearchStopping = stopping;
        this.checkNotificationPopoverClose();
      });

    this.appSettingsService.appSettings$
      .pipe(takeUntil(this.destroy$))
      .subscribe(settings => {
        this.aiSearchEnabled = settings?.aiSearchEnabled ?? false;
        if (this.aiSearchEnabled) {
          this.startAiStatusPolling();
        } else {
          this.stopAiStatusPolling();
        }
      });

    this.searchActiveSub = this.aiSearchDialogService.searchActive$.subscribe(active => {
      this.isSearchActive = active;
    });

    this.searchErrorSub = this.aiSearchDialogService.searchError$.subscribe(error => {
      this.isSearchError = error;
    });

    this.embeddingProgressSub = this.aiSearchScanProgressService.progress$
      .pipe(takeUntil(this.destroy$))
      .subscribe(progress => {
        this.isBatchEmbedding = progress?.mode === 'BATCH' && progress?.event === 'IN_PROGRESS';
      });

    this.sidecarBackupProgressService.active$
      .pipe(takeUntil(this.destroy$))
      .subscribe(active => {
        this.isSidecarBackupRunning = active;
      });

    this.taskService.taskProgress$
      .pipe(
        filter((p): p is TaskProgressPayload => !!p && p.taskType === TaskType.FLUSH_METADATA_TO_FILES),
        takeUntil(this.destroy$)
      )
      .subscribe(progress => {
        this.metadataFlushProgress = progress;
        if (progress.taskStatus === TaskStatus.COMPLETED || progress.taskStatus === TaskStatus.CANCELLED) {
          clearTimeout(this.flushDismissTimer);
          this.flushDismissTimer = setTimeout(() => {
            this.metadataFlushProgress = null;
          }, 5000);
        }
      });

    this.taskService.taskProgress$
      .pipe(
        filter((p): p is TaskProgressPayload => !!p && p.taskType === TaskType.SYNC_LIBRARY_FILES),
        takeUntil(this.destroy$)
      )
      .subscribe(progress => {
        this.importScanProgress = progress;
        if (progress.taskStatus === TaskStatus.COMPLETED || progress.taskStatus === TaskStatus.CANCELLED) {
          clearTimeout(this.importDismissTimer);
          this.importDismissTimer = setTimeout(() => {
            this.importScanProgress = null;
          }, 5000);
        } else if (progress.taskStatus === TaskStatus.IN_PROGRESS) {
          clearTimeout(this.importDismissTimer);
        }
      });

    this.taskService.taskProgress$
      .pipe(
        filter((p): p is TaskProgressPayload => !!p && p.taskType === TaskType.DIRECTORY_TAGGING),
        takeUntil(this.destroy$)
      )
      .subscribe(progress => {
        this.directoryTaggingProgress = progress;
        if (
          progress.taskStatus === TaskStatus.COMPLETED ||
          progress.taskStatus === TaskStatus.CANCELLED ||
          progress.taskStatus === TaskStatus.FAILED
        ) {
          clearTimeout(this.directoryTagDismissTimer);
          this.directoryTagDismissTimer = setTimeout(() => {
            this.directoryTaggingProgress = null;
          }, 5000);
        } else if (progress.taskStatus === TaskStatus.IN_PROGRESS) {
          clearTimeout(this.directoryTagDismissTimer);
        }
      });

    this.taskService.taskProgress$
      .pipe(
        filter((p): p is TaskProgressPayload =>
          !!p && (p.taskType === TaskType.REFRESH_LIBRARY_METADATA || p.taskType === TaskType.REFRESH_METADATA_MANUAL)),
        takeUntil(this.destroy$)
      )
      .subscribe(progress => {
        this.metadataFetchProgress = progress;
        this.syncTopbarMetadataPhase();
        clearTimeout(this.metadataFetchDismissTimer);
        if (
          progress.taskStatus === TaskStatus.COMPLETED ||
          progress.taskStatus === TaskStatus.CANCELLED ||
          progress.taskStatus === TaskStatus.FAILED
        ) {
          this.metadataFetchDismissTimer = setTimeout(() => {
            this.metadataFetchProgress = null;
          }, 5000);
        } else if (progress.taskStatus === TaskStatus.IN_PROGRESS) {
          clearTimeout(this.metadataFetchDismissTimer);
        }
      });

    this.writeProgressService.progress$
      .pipe(takeUntil(this.destroy$))
      .subscribe(payload => {
        this.writeProgress = payload;
        clearTimeout(this.writeDismissTimer);
        if (payload && (payload.status === 'COMPLETED' || payload.status === 'FAILED')) {
          this.writeDismissTimer = setTimeout(() => {
            this.writeProgress = null;
          }, 5000);
        } else if (payload && payload.status === 'IN_PROGRESS') {
          clearTimeout(this.writeDismissTimer);
        }
      });

    this.userService.userState$
      .pipe(takeUntil(this.destroy$))
      .subscribe((userState) => {
        this.toolbarConfig.load(userState.user);
        this.initializeStatsMenu();
      });

    this.translocoService.langChanges$
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        this.initializeStatsMenu();
      });

    this.router.events
      .pipe(
        filter(e => e instanceof NavigationStart),
        takeUntil(this.destroy$)
      )
      .subscribe((event) => {
        this.closeMobileSearch();
        this.closeMobileTopbarPopoversForNavigation();
        this.updateMobileBookFilterTriggerVisibility((event as NavigationStart).url);
      });

    this.removeFullscreenChangeListener = addFullscreenChangeListener(this.onFullscreenChange);
    this.syncFullscreenState();
  }

  ngOnDestroy(): void {
    this.removeFullscreenChangeListener?.();
    this.removeFullscreenChangeListener = null;
    this.mobileSidebarBackHandle?.release(false);
    this.mobileSidebarBackHandle = null;
    this.mobileDirectoryBackHandle?.release(false);
    this.mobileDirectoryBackHandle = null;
    this.mobileOverflowBackHandle?.release(false);
    this.mobileOverflowBackHandle = null;
    this.mobileSearchBackHandle?.release(false);
    this.mobileSearchBackHandle = null;
    this.clearMobileSearchMaskTimer();
    this.metadataFetchLogBackHandle?.release(false);
    this.metadataFetchLogBackHandle = null;

    if (this.ref) this.ref.close();
    clearTimeout(this.eventTimer);
    clearTimeout(this.flushDismissTimer);
    clearTimeout(this.importDismissTimer);
    clearTimeout(this.directoryTagDismissTimer);
    clearTimeout(this.metadataFetchDismissTimer);
    clearTimeout(this.topbarIsbnFailedTimer);
    clearTimeout(this.writeDismissTimer);

    this.searchActiveSub?.unsubscribe();
    this.searchErrorSub?.unsubscribe();
    this.embeddingProgressSub?.unsubscribe();
    this.stopAiStatusPolling();

    this.destroy$.next();
    this.destroy$.complete();
  }

  private startAiStatusPolling(): void {
    if (this.pollingSub) return;
    
    const fetchStatus = () => {
      this.appSettingsService.getAiSearchServiceStatus().pipe(
        catchError(() => of({ status: 'ERROR' }))
      ).subscribe((res) => {
        if (res && res.status) {
          this.searchStatus = res.status as 'READY' | 'STARTING' | 'ERROR';
        }
      });
    };
    
    fetchStatus();
    this.pollingSub = interval(5000).subscribe(() => fetchStatus());
  }

  private stopAiStatusPolling(): void {
    if (this.pollingSub) {
      this.pollingSub.unsubscribe();
      this.pollingSub = undefined;
    }
  }

  toggleMenu() {
    this.isMenuVisible = !this.isMenuVisible;
    this.layoutService.onMenuToggle();
  }

  openMobileSearch(): void {
    this.armMobileSearchGhostGuard();
    this.mobileSearchVisible = true;
    if (!this.mobileSearchBackHandle) {
      this.mobileSearchBackHandle = this.mobileBackNavigation.register(() => {
        this.mobileSearchVisible = false;
      });
    }
    // Attempt focus in the same turn as the open tap (desktop touch OSK).
    // onShow remains the mount-timing backup when the dialog content appears later.
    queueMicrotask(() => this.mobileBookSearcher?.focusInput());
  }

  closeMobileSearch(): void {
    this.clearMobileSearchMaskTimer();
    this.mobileSearchDismissableMask = false;
    this.mobileBookSearcher?.blurInput();
    this.mobileSearchVisible = false;
    this.mobileSearchBackHandle?.release();
    this.mobileSearchBackHandle = null;
  }

  onMobileSearchHide(): void {
    // Ghost click after open: PrimeNG already flipped visible=false — restore it.
    if (this.mobileSearchGhostGuard.shouldIgnore()) {
      this.mobileSearchVisible = true;
      queueMicrotask(() => this.mobileBookSearcher?.focusInput());
      return;
    }
    this.closeMobileSearch();
  }

  private armMobileSearchGhostGuard(): void {
    this.mobileSearchGhostGuard.arm();
    this.mobileSearchDismissableMask = false;
    this.clearMobileSearchMaskTimer();
    this.mobileSearchMaskTimer = setTimeout(() => {
      this.mobileSearchDismissableMask = true;
      this.mobileSearchMaskTimer = null;
    }, OVERLAY_GHOST_CLICK_MS);
  }

  private clearMobileSearchMaskTimer(): void {
    if (this.mobileSearchMaskTimer) {
      clearTimeout(this.mobileSearchMaskTimer);
      this.mobileSearchMaskTimer = null;
    }
  }

  onMobileSearchShow(): void {
    // User tapped Search — focus so Android/iOS/desktop-touch can open the keyboard.
    this.mobileBookSearcher?.focusInput();
  }

  toggleFullscreen(): void {
    void toggleAppFullscreen().finally(() => this.syncFullscreenState());
  }

  toggleMobileBookFilter(event: MouseEvent): void {
    this.sidebarFilterTogglePrefService.requestMobileFilterToggle(event);
  }

  onMobileSidebarClick(event: MouseEvent): void {
    const target = event.target as HTMLElement | null;
    if (!target) {
      return;
    }

    // Close on row-level selections, but keep open for inline action controls
    // (including section headers that expand/collapse).
    const selectedRow = target.closest('.menu-item-container, .sidebar-bottom-btn');
    const inlineAction = target.closest(
      '.entity-menu-button, .expand-icon, .plus-icon, .sidebar-heading-nav, .section-visibility-btn, .sidebar-reorder-btn, .reorder-row, .root-item-with-dropdown, .sidebar-drag-handle, .sidebar-order-btn, .section-reorder-toolbar, .reorder-done-btn, .row-order-actions, .section-order-actions'
    );

    if (selectedRow && !inlineAction) {
      this.mobileSidebarPop?.hide();
    }
  }

  onMobileSidebarPopoverShow(): void {
    this.mobileSidebarOpen = true;
    if (this.mobileSidebarBackHandle) {
      return;
    }

    this.mobileSidebarBackHandle = this.mobileBackNavigation.register(() => {
      this.mobileSidebarPop?.hide();
    });
  }

  onMobileSidebarPopoverHide(): void {
    this.mobileSidebarOpen = false;
    this.mobileSidebarBackHandle?.release();
    this.mobileSidebarBackHandle = null;
  }

  onNotificationPopoverShow(): void {
    this.notificationService.fetchHistoricalNotifications();
    if (this.mobileNotificationBackHandle) {
      return;
    }
    this.mobileNotificationBackHandle = this.mobileBackNavigation.register(() => {
      this.notificationPopover?.hide();
      this.notificationPopoverMobile?.hide();
    });
  }

  onNotificationPopoverHide(): void {
    this.mobileNotificationBackHandle?.release();
    this.mobileNotificationBackHandle = null;
  }

  onMobileDirectoryPopoverShow(): void {
    this.mobileDirectoryPopoverOpen = true;
    if (this.mobileDirectoryBackHandle) {
      return;
    }

    this.mobileDirectoryBackHandle = this.mobileBackNavigation.register(() => {
      this.mobileDirPop?.hide();
    });
  }

  onMobileDirectoryPopoverHide(): void {
    this.mobileDirectoryPopoverOpen = false;
    this.mobileDirectoryBackHandle?.release();
    this.mobileDirectoryBackHandle = null;
  }

  onMobileOverflowPopoverShow(): void {
    this.mobileOverflowOpen = true;
    if (this.mobileOverflowBackHandle) {
      return;
    }

    this.mobileOverflowBackHandle = this.mobileBackNavigation.register(() => {
      this.mobileMenuPop?.hide();
    });
  }

  onMobileOverflowPopoverHide(): void {
    this.mobileOverflowOpen = false;
    this.mobileOverflowBackHandle?.release();
    this.mobileOverflowBackHandle = null;
  }

  private closeMobileTopbarPopoversForNavigation(): void {
    this.mobileSidebarBackHandle?.release(false);
    this.mobileSidebarBackHandle = null;
    this.mobileDirectoryBackHandle?.release(false);
    this.mobileDirectoryBackHandle = null;
    this.mobileOverflowBackHandle?.release(false);
    this.mobileOverflowBackHandle = null;

    this.mobileSidebarPop?.hide();
    this.mobileDirPop?.hide();
    this.mobileMenuPop?.hide();
    this.notificationPopover?.hide();
    this.notificationPopoverMobile?.hide();
    this.mobileDirectoryPopoverOpen = false;
  }


  openLibraryCreatorDialog(): void {
    this.dialogLauncher.openLibraryCreateDialog();
  }

  openFileUploadDialog(): void {
    this.dialogLauncher.openFileUploadDialog();
  }

  openUserProfileDialog(): void {
    this.dialogLauncher.openUserProfileDialog();
  }

  navigateToSettings() {
    this.router.navigate(['/settings'], {queryParams: {returnTo: this.router.url}});
  }

  navigateToAiSettings() {
    this.router.navigate(['/settings'], {queryParams: {tab: 'ai-settings', returnTo: this.router.url}});
  }

  openAiSearch(): void {
    this.aiSearchDialog.open();
  }

  navigateToMetadataPersistenceSettings(): void {
    this.router.navigate(['/settings'], {queryParams: {tab: 'metadata', returnTo: this.router.url}});
  }

  navigateToBookdrop() {
    this.router.navigate(['/bookdrop']);
  }

  navigateToMetadataManager() {
    this.router.navigate(['/metadata-manager']);
  }

  openMetadataFetchLog(): void {
    const taskId = this.displayedMetadataFetchProgress?.taskId;
    if (!taskId) {
      return;
    }

    this.metadataFetchLogVisible = true;
    if (!this.metadataFetchLogBackHandle) {
      this.metadataFetchLogBackHandle = this.mobileBackNavigation.register(() => {
        this.metadataFetchLogVisible = false;
      });
    }
    this.metadataFetchLogLoading = true;
    this.metadataFetchLogError = '';
    this.metadataTaskService.getTaskLog(taskId).subscribe({
      next: (log) => {
        this.metadataFetchLog = log;
        this.metadataFetchLogLoading = false;
      },
      error: () => {
        this.metadataFetchLog = null;
        this.metadataFetchLogLoading = false;
        this.metadataFetchLogError = this.translocoService.translate('layout.topbar.metadataFetchLogLoadFailed');
      }
    });
  }

  closeMetadataFetchLog(): void {
    this.metadataFetchLogVisible = false;
    this.metadataFetchLogBackHandle?.release();
    this.metadataFetchLogBackHandle = null;
  }

  onMetadataFetchLogHide(): void {
    this.closeMetadataFetchLog();
  }

  navigateToTaskManagement(): void {
    this.router.navigate(['/settings'], {queryParams: {tab: 'task', returnTo: this.router.url}});
  }

  navigateToStats() {
    this.router.navigate(['/library-stats']);
  }

  navigateToUserStats() {
    this.router.navigate(['/reading-stats']);
  }

  switchLanguage(lang: string) {
    if (lang === this.activeLang) return;
    this.translocoService.load(lang).subscribe(() => {
      this.translocoService.setActiveLang(lang);
      localStorage.setItem(LANG_STORAGE_KEY, lang);
      this.activeLang = lang;
      this.langMenuItems = AVAILABLE_LANGS.map(l => ({
        label: LANG_LABELS[l] || l,
        icon: l === lang ? 'pi pi-check' : undefined,
        command: () => this.switchLanguage(l),
      }));
    });
  }

  logout() {
    this.authService.logout();
  }

  handleStatsButtonClick(event: Event) {
    if (this.statsMenuItems.length === 0) {
      return;
    }

    if (this.statsMenuItems.length === 1) {
      this.statsMenuItems[0].command?.({originalEvent: event, item: this.statsMenuItems[0]});
    }
  }

  private subscribeToMetadataProgress() {
    this.metadataProgressService.progressUpdates$
      .pipe(takeUntil(this.destroy$))
      .subscribe((progress) => {
        this.progressHighlight = progress.status === 'IN_PROGRESS';
      });
  }

  private subscribeToNotifications() {
    this.notificationService.activeNotification$
      .pipe(takeUntil(this.destroy$))
      .subscribe((notification: LogNotification | null) => {
        if (notification) {
          this.latestNotificationSeverity = notification.severity;
        } else {
          this.hasActiveLogNotification = false;
        }
        this.checkNotificationPopoverClose();
      });

    this.notificationService.notificationHighlight$
      .pipe(takeUntil(this.destroy$))
      .subscribe((highlight: boolean) => {
        this.hasActiveLogNotification = highlight;
        this.updateCompletedTaskCount();
        if (highlight) {
          this.triggerPulseEffect();
        }
        this.checkNotificationPopoverClose();
      });

    this.notificationService.unreadFailureCount$
      .pipe(takeUntil(this.destroy$))
      .subscribe(count => {
        this.unreadFailureCount = count;
        this.checkNotificationPopoverClose();
      });
  }

  private triggerPulseEffect() {
    this.showPulse = true;
    clearTimeout(this.eventTimer);
    this.eventTimer = setTimeout(() => {
      this.showPulse = false;
    }, 4000) as unknown as number;
  }

  private checkNotificationPopoverClose() {
    const hasActiveNotification = this.hasActiveLogNotification;
    const hasMetadataTasks = this.hasAnyTasks;
    const hasPendingBookdropFiles = this.hasPendingBookdropFiles;
    const hasAiSearchScan = !!(this.aiSearchBatchProgress && this.aiSearchBatchProgress.mode === 'BATCH') || this.isAiSearchStopping;
    const hasFailures = this.unreadFailureCount > 0;

    const hasAnyContent = hasActiveNotification || hasMetadataTasks || hasPendingBookdropFiles || hasAiSearchScan || hasFailures;

    if (!hasAnyContent) {
      if (this.notificationPopover) {
        this.notificationPopover.hide();
      }
      if (this.notificationPopoverMobile) {
        this.notificationPopoverMobile.hide();
      }
    }
  }

  private updateCompletedTaskCount() {
    const completedMetadataTasks = Object.values(this.latestTasks).length;
    const bookdropFileTaskCount = this.latestHasPendingFiles ? 1 : 0;
    this.completedTaskCount = completedMetadataTasks + bookdropFileTaskCount;
  }

  private updateTaskVisibility(tasks: Record<string, MetadataBatchProgressNotification>) {
    this.hasActiveOrCompletedTasks =
      this.progressHighlight || this.completedTaskCount > 0 || Object.keys(tasks).length > 0;
    this.updateTaskVisibilityWithBookdrop();
  }

  private updateTaskVisibilityWithBookdrop() {
    this.hasActiveOrCompletedTasks = this.hasActiveOrCompletedTasks || this.hasPendingBookdropFiles;
  }

  private initializeStatsMenu() {
    const userState = this.userService.userStateSubject.value;
    const user = userState.user;

    this.statsMenuItems = [];

    if (user?.permissions?.canAccessLibraryStats || user?.permissions?.admin) {
      this.statsMenuItems.push({
        label: this.translocoService.translate('layout.topbar.libraryStats'),
        icon: 'pi pi-chart-line',
        command: () => this.navigateToStats()
      });
    }

    if (user?.permissions?.canAccessUserStats || user?.permissions?.admin) {
      this.statsMenuItems.push({
        label: this.translocoService.translate('layout.topbar.readingStats'),
        icon: 'pi pi-users',
        command: () => this.navigateToUserStats()
      });
    }
  }

  get hasStatsAccess(): boolean {
    return this.statsMenuItems.length > 0;
  }

  get shouldShowStatsMenu(): boolean {
    return this.statsMenuItems.length > 1;
  }

  get statsTooltip(): string {
    if (this.statsMenuItems.length === 0) {
      return this.translocoService.translate('layout.topbar.stats');
    }
    if (this.statsMenuItems.length === 1) {
      return this.statsMenuItems[0].label || this.translocoService.translate('layout.topbar.stats');
    }
    return this.translocoService.translate('layout.topbar.stats');
  }

  get iconClass(): string {
    if (this.progressHighlight) return 'pi-spinner';
    if (this.iconPulsating) return 'pi-wave-pulse';
    if (this.hasActiveLogNotification) return 'pi-bell';
    if (this.completedTaskCount > 0 || this.hasPendingBookdropFiles) return 'pi-bell';
    return 'pi-wave-pulse';
  }

  get iconColor(): string {
    if (this.progressHighlight) return 'white';
    if (this.showPulse) {
      switch (this.latestNotificationSeverity) {
        case Severity.ERROR:
          return 'crimson';
        case Severity.WARN:
          return 'orange';
        default:
          return 'crimson';
      }
    }
    if (this.unreadFailureCount > 0) return 'crimson';
    if (this.completedTaskCount > 0 || this.hasPendingBookdropFiles)
      return 'var(--task-color-metadata, #7e22ce)';
    return 'inherit';
  }

  get iconPulsating(): boolean {
    return !this.progressHighlight && (this.showPulse);
  }

  get shouldShowNotificationBadge(): boolean {
    return (
      (this.unreadFailureCount > 0 || this.completedTaskCount > 0 || this.hasPendingBookdropFiles) &&
      !this.progressHighlight &&
      !this.showPulse
    );
  }

  get notificationBadgeCount(): number {
    if (this.unreadFailureCount > 0) {
      return this.unreadFailureCount;
    }
    return this.completedTaskCount;
  }

  get visibleDesktopToolbarItems(): ToolbarItem[] {
    void this.toolbarConfig.revision;
    const filtered = this.toolbarConfig.items.filter(item => this.isToolbarItemVisible(item));
    return this.normalizeToolbarSequence(filtered);
  }

  get showDesktopAiScanStatus(): boolean {
    return !!this.aiBatchProgress;
  }

  get showDesktopAiSearchScanStatus(): boolean {
    return !!this.aiSearchBatchProgress || this.isAiSearchStopping;
  }

  get showMetadataFlushStatus(): boolean {
    return !!this.metadataFlushProgress;
  }

  get showImportScanStatus(): boolean {
    return !!this.importScanProgress;
  }

  get showDirectoryTaggingStatus(): boolean {
    return !!this.directoryTaggingProgress;
  }

  get showMetadataFetchStatus(): boolean {
    return !!this.displayedMetadataFetchProgress;
  }

  get metadataFetchStatusClasses(): Record<string, boolean> {
    const progress = this.displayedMetadataFetchProgress;
    const phase = this.mobileUx.isPhone ? null : this.topbarMetadataPhase;
    return {
      'topbar-metadata-fetch-paused': this.isMetadataFetchPaused,
      'topbar-metadata-fetch-complete': progress?.taskStatus === TaskStatus.COMPLETED,
      'topbar-metadata-fetch-cancelled': progress?.taskStatus === TaskStatus.CANCELLED,
      'topbar-metadata-fetch-failed': progress?.taskStatus === TaskStatus.FAILED,
      'topbar-metadata-fetch-phase-isbn': phase === MetadataBatchPhase.ISBN_DISCOVERY,
      'topbar-metadata-fetch-phase-metadata': phase === MetadataBatchPhase.METADATA_FETCH,
      'topbar-metadata-fetch-phase-isbn-failed': phase === MetadataBatchPhase.ISBN_FAILED,
    };
  }

  get showSidecarBackupStatus(): boolean {
    return this.isSidecarBackupRunning;
  }

  get showWriteStatus(): boolean {
    return !!this.writeProgress;
  }

  get showMobileWriteIndicator(): boolean {
    return this.mobileUx.isMobileOrTablet && (!!this.writeProgress || !!this.metadataFetchProgress);
  }

  get showMobileWriteComplete(): boolean {
    return this.mobileUx.isMobileOrTablet && (this.writeProgress?.status === 'COMPLETED'
      || this.metadataFetchProgress?.taskStatus === TaskStatus.COMPLETED);
  }

  get showMobileWriteFailed(): boolean {
    return this.mobileUx.isMobileOrTablet && (this.writeProgress?.status === 'FAILED'
      || this.metadataFetchProgress?.taskStatus === TaskStatus.FAILED
      || this.metadataFetchProgress?.taskStatus === TaskStatus.CANCELLED);
  }

  @HostListener('document:click')
  onDocumentClick(): void {
    if (this.mobileUx.isMobileOrTablet) {
      this.dismissMobileWriteIndicator();
    }
  }

  @HostListener('document:touchstart')
  onDocumentTouchStart(): void {
    if (this.mobileUx.isMobileOrTablet) {
      this.dismissMobileWriteIndicator();
    }
  }

  @HostListener('document:keydown.enter')
  onDocumentEnter(): void {
    if (this.mobileUx.isMobileOrTablet) {
      this.dismissMobileWriteIndicator();
    }
  }

  dismissMobileWriteIndicator(): void {
    this.writeProgress = null;
    this.metadataFetchProgress = null;
  }

  get metadataFetchIconClass(): string {
    if (!this.mobileUx.isPhone) {
      if (this.topbarMetadataPhase === MetadataBatchPhase.ISBN_DISCOVERY) return 'pi pi-barcode';
      if (this.topbarMetadataPhase === MetadataBatchPhase.ISBN_FAILED) return 'pi pi-times-circle';
    }
    return this.isMetadataFetchPaused ? 'pi pi-pause-circle' : 'pi pi-cloud-download';
  }

  get metadataFetchLabel(): string {
    if (!this.mobileUx.isPhone) {
      if (this.topbarMetadataPhase === MetadataBatchPhase.ISBN_DISCOVERY) {
        return this.translocoService.translate('layout.topbar.isbnFetch');
      }
      if (this.topbarMetadataPhase === MetadataBatchPhase.ISBN_FAILED) {
        return this.translocoService.translate('layout.topbar.isbnFetchFailed');
      }
    }
    return this.translocoService.translate('layout.topbar.metadataFetch');
  }

  get fullscreenTooltip(): string {
    return this.translocoService.translate(this.isFullscreen ? 'layout.topbar.exitFullscreen' : 'layout.topbar.fullscreen');
  }

  get fullscreenIconClass(): string {
    return this.isFullscreen ? 'pi pi-window-minimize' : 'pi pi-window-maximize';
  }

  get sidecarBackupSummary(): string {
    return this.translocoService.translate('layout.topbar.sidecarBackupWorking');
  }

  get writeStatusSummary(): string {
    if (!this.writeProgress) return '';
    return this.writeProgress.message;
  }

  get metadataFlushSummary(): string {
    if (!this.metadataFlushProgress) return '';
    const s = this.metadataFlushProgress.taskStatus;
    if (s === TaskStatus.COMPLETED) return this.translocoService.translate('layout.topbar.metadataFlushCompleted');
    if (s === TaskStatus.CANCELLED) return this.translocoService.translate('layout.topbar.metadataFlushCancelled');
    if (s === TaskStatus.FAILED) return this.translocoService.translate('layout.topbar.metadataFlushFailed');
    return this.translocoService.translate('layout.topbar.metadataFlushProgress', {progress: this.metadataFlushProgress.progress});
  }

  get importScanSummary(): string {
    if (!this.importScanProgress) return '';
    const s = this.importScanProgress.taskStatus;
    if (s === TaskStatus.COMPLETED) return this.translocoService.translate('layout.topbar.importScanCompleted');
    if (s === TaskStatus.CANCELLED) return this.translocoService.translate('layout.topbar.importScanCancelled');
    if (s === TaskStatus.FAILED) return this.translocoService.translate('layout.topbar.importScanFailed');
    return this.importScanProgress.message;
  }

  get directoryTaggingSummary(): string {
    if (!this.directoryTaggingProgress) return '';
    const s = this.directoryTaggingProgress.taskStatus;
    if (s === TaskStatus.COMPLETED) return this.translocoService.translate('layout.topbar.directoryTaggingCompleted');
    if (s === TaskStatus.CANCELLED) return this.translocoService.translate('layout.topbar.directoryTaggingCancelled');
    if (s === TaskStatus.FAILED) return this.translocoService.translate('layout.topbar.directoryTaggingFailed');
    return this.directoryTaggingProgress.message || this.translocoService.translate('layout.topbar.directoryTaggingProgress', {
      progress: this.directoryTaggingProgress.progress,
    });
  }

  get metadataFetchSummary(): string {
    const progress = this.displayedMetadataFetchProgress;
    if (!progress) return '';

    const s = progress.taskStatus;
    if (s === TaskStatus.COMPLETED) return this.translocoService.translate('layout.topbar.metadataFetchCompleted');
    if (s === TaskStatus.CANCELLED) return this.translocoService.translate('layout.topbar.metadataFetchCancelled');
    if (s === TaskStatus.FAILED) return this.translocoService.translate('layout.topbar.metadataFetchFailed');

    const resumeAt = this.getMetadataFetchResumeAt();
    if (resumeAt) {
      return this.translocoService.translate('layout.topbar.metadataFetchPausedUntil', {time: resumeAt});
    }

    const currentStep = progress.currentStep;
    const totalSteps = progress.totalSteps;
    if (currentStep != null && totalSteps != null && totalSteps > 0) {
      return `${currentStep}/${totalSteps}`;
    }

    return this.translocoService.translate('layout.topbar.metadataFetchProgress', {progress: progress.progress});
  }

  get metadataFetchLogStatus(): string {
    const task = this.metadataFetchLog;
    if (!task) {
      return '';
    }

    if (this.isMetadataFetchPausedMessage(task.message)) {
      return this.translocoService.translate('layout.topbar.metadataFetchLogStatusPaused');
    }

    switch (task.status) {
      case TaskStatus.COMPLETED:
        return this.translocoService.translate('layout.topbar.metadataFetchLogStatusCompleted');
      case TaskStatus.CANCELLED:
        return this.translocoService.translate('layout.topbar.metadataFetchLogStatusCancelled');
      case TaskStatus.FAILED:
      case 'ERROR':
        return this.translocoService.translate('layout.topbar.metadataFetchLogStatusFailed');
      default:
        return this.translocoService.translate('layout.topbar.metadataFetchLogStatusRunning');
    }
  }

  get aiScanTone(): 'ok' | 'warning' | 'error' {
    if (!this.aiBatchProgress) {
      return 'warning';
    }

    if (this.aiBatchProgress.event === 'FAILED') {
      return 'error';
    }

    if (this.aiBatchProgress.event === 'COMPLETED') {
      return 'ok';
    }

    return 'warning';
  }


  get aiSearchScanTone(): 'ok' | 'warning' | 'error' {
    if (!this.aiSearchBatchProgress) {
      return 'warning';
    }

    if (this.aiSearchBatchProgress.event === 'FAILED') {
      return 'error';
    }

    if (this.aiSearchBatchProgress.event === 'COMPLETED') {
      return 'ok';
    }

    return 'warning';
  }

  get aiScanSummary(): string {
    if (!this.aiBatchProgress) {
      return '';
    }

    if (this.aiBatchProgress.event === 'FAILED') {
      return this.aiBatchProgress.error || this.aiBatchProgress.message || this.translocoService.translate('layout.topbar.aiScanFailed');
    }

    if (this.aiBatchProgress.event === 'COMPLETED') {
      return this.translocoService.translate('layout.topbar.aiScanCompleted');
    }

    return this.translocoService.translate('layout.topbar.aiScanProgress', {
      completed: this.aiBatchProgress.completedBooks ?? 0,
      total: this.aiBatchProgress.totalBooks ?? 0
    });
  }

  get aiSearchSingleScanTone(): 'ok' | 'warning' | 'error' {
    if (!this.aiSearchSingleProgress) {
      return 'warning';
    }
    if (this.aiSearchSingleProgress.event === 'FAILED') return 'error';
    if (this.aiSearchSingleProgress.event === 'COMPLETED') return 'ok';
    return 'warning';
  }

  get showDesktopAiSearchSingleStatus(): boolean {
    return !!this.aiSearchSingleProgress;
  }

  get aiSearchScanSummary(): string {
    if (this.isAiSearchStopping) {
      return 'Stopping...';
    }
    return this.getConciseSummary(this.aiSearchBatchProgress);
  }

  get aiSearchSingleSummary(): string {
    return this.getConciseSummary(this.aiSearchSingleProgress);
  }

  private getConciseSummary(progress: AiSearchProgressPayload | null): string {
    if (!progress) return '';
    
    let detail = '';
    const ocrMatch = progress.message.match(/OCR \(page (\d+\/\d+)\)/);
    if (ocrMatch) {
      detail = `OCR p. ${ocrMatch[1]}`;
    } else {
      const percentMatch = progress.message.match(/Embedding\.\.\. (\d+%)/);
      if (percentMatch) {
        detail = `${percentMatch[1]}`;
      } else {
        switch (progress.event) {
          case 'STARTED':
            detail = 'Started';
            break;
          case 'COMPLETED':
            detail = 'Completed';
            break;
          case 'FAILED':
            detail = 'Failed';
            break;
          case 'STOPPED':
            detail = 'Stopped';
            break;
        }
      }
    }

    if (progress.mode === 'BATCH') {
      const current = progress.current ?? 0;
      const total = progress.total ?? 0;
      if (total > 0) {
        return detail ? `${current}/${total} (${detail})` : `${current}/${total} books`;
      }
    }

    return detail || progress.message || '';
  }

  private isToolbarItemVisible(item: ToolbarItem): boolean {
    if (!item.visible) {
      return false;
    }

    if (item.type === 'separator') {
      return true;
    }

    return this.toolbarConfig.isAllowed(item.id);
  }

  private normalizeToolbarSequence(items: ToolbarItem[]): ToolbarItem[] {
    const normalized: ToolbarItem[] = [];

    for (const item of items) {
      if (item.type === 'separator') {
        if (!normalized.length || normalized[normalized.length - 1].type === 'separator') {
          continue;
        }
      }

      normalized.push(item);
    }

    while (normalized.length && normalized[normalized.length - 1].type === 'separator') {
      normalized.pop();
    }

    return normalized;
  }

  private updateMobileBookFilterTriggerVisibility(url: string): void {
    const path = (url || '').split('?')[0].split('#')[0];
    const isBookBrowsing = /^\/(all-books|staging|not-shelfed|library\/[^/]+\/books|shelf\/[^/]+\/books|magic-shelf\/[^/]+\/books)\/?$/.test(path);
    this.showMobileBookFilterTrigger = isBookBrowsing;
    this.showMobileDirTrigger = isBookBrowsing;
  }

  private readonly onFullscreenChange = (): void => {
    this.syncFullscreenState();
  };

  private syncFullscreenState(): void {
    this.isFullscreen = isAppFullscreen();
    // Fullscreen enter/exit can leave capture / bl-resizing* stuck on desktop-touch.
    clearFullscreenTransientPointerUi();
  }

  private get isMetadataFetchPaused(): boolean {
    return !!this.getMetadataFetchResumeAt();
  }

  private get displayedMetadataFetchProgress(): TaskProgressPayload | null {
    if (this.metadataFetchProgress?.taskStatus === TaskStatus.IN_PROGRESS) {
      return this.metadataFetchProgress;
    }

    return this.recoveredMetadataFetchProgress ?? this.metadataFetchProgress;
  }

  private get recoveredMetadataFetchProgress(): TaskProgressPayload | null {
    const activeTask = Object.values(this.latestTasks)
      .filter((task) => task.status === 'IN_PROGRESS')
      .sort((left, right) => right.completed - left.completed)[0];

    if (!activeTask) {
      return null;
    }

    const progress = activeTask.total > 0
      ? Math.min(100, Math.max(0, Math.round((activeTask.completed * 100) / activeTask.total)))
      : 0;

    return {
      taskId: activeTask.taskId,
      taskType: TaskType.REFRESH_METADATA_MANUAL,
      message: activeTask.message,
      progress,
      currentStep: activeTask.completed,
      totalSteps: activeTask.total,
      taskStatus: TaskStatus.IN_PROGRESS,
    };
  }

  private syncTopbarMetadataPhase(): void {
    const incomingPhase = this.resolveTopbarMetadataPhase();
    const now = Date.now();

    if (incomingPhase === MetadataBatchPhase.ISBN_FAILED) {
      this.topbarMetadataPhase = MetadataBatchPhase.ISBN_FAILED;
      this.topbarIsbnFailedLockedUntil = now + 2500;
      clearTimeout(this.topbarIsbnFailedTimer);
      this.topbarIsbnFailedTimer = setTimeout(() => {
        this.topbarIsbnFailedLockedUntil = 0;
        this.topbarIsbnFailedTimer = undefined;
        this.syncTopbarMetadataPhase();
      }, 2500);
      return;
    }

    if (this.topbarIsbnFailedLockedUntil > now) {
      this.topbarMetadataPhase = MetadataBatchPhase.ISBN_FAILED;
      return;
    }

    clearTimeout(this.topbarIsbnFailedTimer);
    this.topbarIsbnFailedTimer = undefined;
    this.topbarIsbnFailedLockedUntil = 0;
    this.topbarMetadataPhase = incomingPhase;
  }

  private resolveTopbarMetadataPhase(): MetadataBatchPhase | null {
    const displayedTaskId = this.displayedMetadataFetchProgress?.taskId;
    return selectTopbarMetadataPhase(this.latestTasks, displayedTaskId);
  }

  private getMetadataFetchResumeAt(): string | null {
    const message = this.displayedMetadataFetchProgress?.message;
    if (!message) {
      return null;
    }

    const match = message.match(AppTopBarComponent.METADATA_FETCH_RESUME_AT_PATTERN);
    return match?.[1]?.trim() || null;
  }

  private isMetadataFetchPausedMessage(message: string | null | undefined): boolean {
    return !!message && AppTopBarComponent.METADATA_FETCH_RESUME_AT_PATTERN.test(message);
  }
}
