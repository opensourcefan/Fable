import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {AppComponent} from './app.component';
import {AuthInitializationService} from './core/security/auth-initialization-service';
import {BehaviorSubject, of, Subject} from 'rxjs';
import {RxStompService} from './shared/websocket/rx-stomp.service';
import {BookService} from './features/book/service/book.service';
import {NotificationEventService} from './shared/websocket/notification-event.service';
import {AppConfigService} from './shared/service/app-config.service';
import {MetadataProgressService} from './shared/service/metadata-progress.service';
import {BookdropFileService} from './features/bookdrop/service/bookdrop-file.service';
import {TaskService} from './features/settings/task-management/task.service';
import {LibraryService} from './features/book/service/library.service';
import {LibraryHealthService} from './features/book/service/library-health.service';
import {LibraryLoadingService} from './features/library-creator/library-loading.service';
import {TranslocoTestingModule} from '@jsverse/transloco';
import {MessageService, ConfirmationService} from 'primeng/api';
import {AuthService} from './shared/service/auth.service';
import {AiPanelScanProgressService} from './shared/service/ai-panel-scan-progress.service';
import {AiSearchScanProgressService} from './shared/service/ai-search-scan-progress.service';
import {PagedGridPilotService} from './features/book/service/paged-grid-pilot.service';
import {MobileBackNavigationService} from './shared/service/mobile-back-navigation.service';
import {TaskStatus, TaskType} from './features/settings/task-management/task.service';

describe('AppComponent offline detection', () => {
  let fixture: ComponentFixture<AppComponent>;
  let component: AppComponent;
  let authInitSubject: BehaviorSubject<boolean>;

  beforeEach(() => {
    authInitSubject = new BehaviorSubject<boolean>(false);

    TestBed.configureTestingModule({
      imports: [TranslocoTestingModule.forRoot({langs: {}})],
      providers: [
        {provide: AuthInitializationService, useValue: {initialized$: authInitSubject.asObservable()}},
        {provide: RxStompService, useValue: {watch: vi.fn(() => of())}},
        {provide: BookService, useValue: {}},
        {provide: NotificationEventService, useValue: {}},
        {provide: AppConfigService, useValue: {}},
        {provide: MetadataProgressService, useValue: {}},
        {provide: BookdropFileService, useValue: {}},
        {provide: TaskService, useValue: {}},
        {provide: LibraryService, useValue: {largeLibraryLoading$: of({isLoading: false, expectedCount: 0})}},
        {provide: LibraryHealthService, useValue: {initialize: vi.fn()}},
        {provide: LibraryLoadingService, useValue: {hide: vi.fn()}},
        {provide: AuthService, useValue: {forceLogout: vi.fn()}},
        {provide: AiPanelScanProgressService, useValue: {handleIncomingProgress: vi.fn()}},
        {provide: AiSearchScanProgressService, useValue: {handleIncomingProgress: vi.fn()}},
        {provide: PagedGridPilotService, useValue: {invalidateAllBooksCache: vi.fn()}},
        {provide: ConfirmationService, useValue: {requireConfirmation$: of(null), close: vi.fn()}},
        {provide: MobileBackNavigationService, useValue: {register: vi.fn(() => ({release: vi.fn()}))}},
        MessageService,
      ]
    });

    fixture = TestBed.createComponent(AppComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('should initialize with offline set to false', () => {
    expect(component.offline).toBe(false);
  });

  it('should render the animated splash loader asset while initializing', () => {
    const loaderIndicator = fixture.nativeElement.querySelector('app-loading-indicator') as HTMLElement | null;
    const loaderImage = loaderIndicator?.querySelector('img') as HTMLImageElement | null;

    expect(loaderIndicator).not.toBeNull();
    expect(loaderIndicator?.getAttribute('aria-hidden')).toBe('true');
    expect(loaderImage).not.toBeNull();
    expect(loaderImage?.getAttribute('src')).toBe('assets/images/loaders/loading3-transparent.webp');
  });

  it('should set offline to false when online event fires', () => {
    component.offline = true;
    window.dispatchEvent(new Event('online'));
    expect(component.offline).toBe(false);
  });

  it('should not show offline when server is reachable despite browser offline event', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, {status: 200}));

    window.dispatchEvent(new Event('offline'));

    await vi.waitFor(() => {
      expect(component.offline).toBe(false);
    });
  });

  it('should show offline when server is unreachable on browser offline event', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'));

    window.dispatchEvent(new Event('offline'));

    await vi.waitFor(() => {
      expect(component.offline).toBe(true);
    });
  });

  it('should ping server with HEAD method and no-store cache', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, {status: 200}));

    window.dispatchEvent(new Event('offline'));

    await vi.waitFor(() => {
      expect(fetchSpy).toHaveBeenCalledWith('/api/public/settings', {
        method: 'HEAD',
        cache: 'no-store',
        signal: expect.any(AbortSignal)
      });
    });
  });

  it('should treat server errors as reachable', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, {status: 500}));

    window.dispatchEvent(new Event('offline'));

    await vi.waitFor(() => {
      expect(component.offline).toBe(false);
    });
  });

  it('should treat network timeout as unreachable', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('network timeout'));

    window.dispatchEvent(new Event('offline'));

    await vi.waitFor(() => {
      expect(component.offline).toBe(true);
    });
  });
});

describe('AppComponent task progress refresh handling', () => {
  let authInitSubject: BehaviorSubject<boolean>;
  let taskProgressSubject: Subject<{body: string}>;
  let rxStompServiceMock: {watch: ReturnType<typeof vi.fn>};
  let bookServiceMock: {refreshBooks: ReturnType<typeof vi.fn>};
  let taskServiceMock: {handleTaskProgress: ReturnType<typeof vi.fn>};
  let pagedGridPilotServiceMock: {invalidateAllBooksCache: ReturnType<typeof vi.fn>};

  beforeEach(() => {
    authInitSubject = new BehaviorSubject<boolean>(false);
    taskProgressSubject = new Subject<{body: string}>();
    rxStompServiceMock = {
      watch: vi.fn((destination: string) => destination === '/user/queue/task-progress' ? taskProgressSubject.asObservable() : of()),
    };
    bookServiceMock = {
      refreshBooks: vi.fn(() => of([])),
    };
    taskServiceMock = {
      handleTaskProgress: vi.fn(),
    };
    pagedGridPilotServiceMock = {
      invalidateAllBooksCache: vi.fn(),
    };

    TestBed.configureTestingModule({
      imports: [TranslocoTestingModule.forRoot({langs: {}})],
      providers: [
        {provide: AuthInitializationService, useValue: {initialized$: authInitSubject.asObservable()}},
        {provide: RxStompService, useValue: rxStompServiceMock},
        {provide: BookService, useValue: bookServiceMock},
        {provide: NotificationEventService, useValue: {handleNewNotification: vi.fn()}},
        {provide: AppConfigService, useValue: {}},
        {provide: MetadataProgressService, useValue: {handleIncomingProgress: vi.fn()}},
        {provide: BookdropFileService, useValue: {handleIncomingFile: vi.fn()}},
        {provide: TaskService, useValue: taskServiceMock},
        {provide: LibraryService, useValue: {largeLibraryLoading$: of({isLoading: false, expectedCount: 0}), setLargeLibraryLoading: vi.fn()}},
        {provide: LibraryHealthService, useValue: {initialize: vi.fn()}},
        {provide: LibraryLoadingService, useValue: {hide: vi.fn(), showBookLoadingProgress: vi.fn()}},
        {provide: AuthService, useValue: {forceLogout: vi.fn()}},
        {provide: AiPanelScanProgressService, useValue: {handleIncomingProgress: vi.fn()}},
        {provide: AiSearchScanProgressService, useValue: {handleIncomingProgress: vi.fn()}},
        {provide: PagedGridPilotService, useValue: pagedGridPilotServiceMock},
        {provide: ConfirmationService, useValue: {requireConfirmation$: of(null), close: vi.fn()}},
        {provide: MobileBackNavigationService, useValue: {register: vi.fn(() => ({release: vi.fn()}))}},
        MessageService,
      ]
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('refreshes books and invalidates paged browser caches when a library sync task completes', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    authInitSubject.next(true);

    taskProgressSubject.next({
      body: JSON.stringify({
        taskId: 'task-1',
        taskType: TaskType.SYNC_LIBRARY_FILES,
        message: 'Completed',
        progress: 100,
        taskStatus: TaskStatus.COMPLETED,
      }),
    });

    expect(taskServiceMock.handleTaskProgress).toHaveBeenCalledWith(expect.objectContaining({
      taskType: TaskType.SYNC_LIBRARY_FILES,
      taskStatus: TaskStatus.COMPLETED,
    }));
    expect(bookServiceMock.refreshBooks).toHaveBeenCalledTimes(1);
    expect(pagedGridPilotServiceMock.invalidateAllBooksCache).toHaveBeenCalledTimes(1);
  });

  it('does not refresh paged browser state for unrelated task completions', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    authInitSubject.next(true);

    taskProgressSubject.next({
      body: JSON.stringify({
        taskId: 'task-2',
        taskType: TaskType.REFRESH_LIBRARY_METADATA,
        message: 'Completed',
        progress: 100,
        taskStatus: TaskStatus.COMPLETED,
      }),
    });

    expect(taskServiceMock.handleTaskProgress).toHaveBeenCalledWith(expect.objectContaining({
      taskType: TaskType.REFRESH_LIBRARY_METADATA,
      taskStatus: TaskStatus.COMPLETED,
    }));
    expect(bookServiceMock.refreshBooks).not.toHaveBeenCalled();
    expect(pagedGridPilotServiceMock.invalidateAllBooksCache).not.toHaveBeenCalled();
  });
});
