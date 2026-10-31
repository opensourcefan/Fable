import {TestBed} from '@angular/core/testing';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {BehaviorSubject, firstValueFrom, Observable, Subject} from 'rxjs';
import {NEVER, of} from 'rxjs';
import {MessageService} from 'primeng/api';
import {Router} from '@angular/router';
import {TranslocoService} from '@jsverse/transloco';
import {AppMenuComponent} from './app.menu.component';
import {AppMenuItem} from './app.menuitem.component';
import {LibraryService} from '../../../../features/book/service/library.service';
import {LibraryHealthService} from '../../../../features/book/service/library-health.service';
import {ShelfService} from '../../../../features/book/service/shelf.service';
import {BookService} from '../../../../features/book/service/book.service';
import {VersionService} from '../../../service/version.service';
import {LibraryShelfMenuService} from '../../../../features/book/service/library-shelf-menu.service';
import {DialogLauncherService} from '../../../services/dialog-launcher.service';
import {UserService} from '../../../../features/settings/user-management/user.service';
import {MagicShelfService} from '../../../../features/magic-shelf/service/magic-shelf.service';
import {SeriesDataService} from '../../../../features/series-browser/service/series-data.service';
import {AuthorService} from '../../../../features/author-browser/service/author.service';
import {LocalStorageService} from '../../../service/local-storage.service';
import {BookDialogHelperService} from '../../../../features/book/components/book-browser/book-dialog-helper.service';
import {MediaTypePreferencesService} from '../../../../features/book/service/media-type-preferences.service';
import {SidebarBadgeRefreshService} from '../../../../features/book/service/sidebar-badge-refresh.service';

describe('AppMenuComponent reorder mode', () => {
  let component: AppMenuComponent;
  let routerMock: {navigate: ReturnType<typeof vi.fn>; url: string; events: typeof NEVER; parseUrl: ReturnType<typeof vi.fn>};
  let localStorageMock: {get: ReturnType<typeof vi.fn>; set: ReturnType<typeof vi.fn>; trySet: ReturnType<typeof vi.fn>; keyChanges$: typeof NEVER};
  let mediaTypePreferencesMock: {setSidebarOrder: ReturnType<typeof vi.fn>; settings$: Observable<{customTypes: string[]; sidebarOrder: string[]}>};
  let versionServiceMock: {getVersion: ReturnType<typeof vi.fn>};
  let libraryServiceMock: {libraryState$: Observable<{libraries: {id?: number; name: string; watch: boolean; paths: never[]}[]}>; getBookCount: ReturnType<typeof vi.fn>};
  let magicShelfServiceMock: {shelvesState$: Observable<{shelves: {id?: number; name: string; filterJson: string}[]}>; getBookCount: ReturnType<typeof vi.fn>};
  let shelfServiceMock: {shelfState$: Observable<{shelves: {id?: number; name: string}[]}>; getUnshelvedBookCount: ReturnType<typeof vi.fn>; getBookCount: ReturnType<typeof vi.fn>};
  let getBooksCount: ReturnType<typeof vi.fn>;
  let langChanges$: BehaviorSubject<string>;
  let keyChanges$: Subject<string>;
  let sidebarBadgeRefreshService: SidebarBadgeRefreshService;

  beforeEach(() => {
    langChanges$ = new BehaviorSubject<string>('en');
    keyChanges$ = new Subject<string>();
    routerMock = {
      navigate: vi.fn(),
      url: '/all-books',
      events: NEVER,
      parseUrl: vi.fn().mockReturnValue({queryParams: {}})
    };
    localStorageMock = {
      get: vi.fn(),
      set: vi.fn(),
      trySet: vi.fn().mockImplementation((key: string, value: unknown) => {
        localStorageMock.set(key, value);
        return true;
      }),
      keyChanges$: keyChanges$.asObservable() as typeof NEVER
    };
    mediaTypePreferencesMock = {
      setSidebarOrder: vi.fn(),
      settings$: of({customTypes: ['CBZ', 'PHYSICAL'], sidebarOrder: []})
    };
    getBooksCount = vi.fn((params?: {bookType?: string; mediaTypes?: string[]}) => {
      if (params?.bookType === 'PHYSICAL') {
        return of(3);
      }
      if (params?.mediaTypes?.[0] === 'CBZ') {
        return of(5);
      }
      return of(9);
    });
    versionServiceMock = {
      getVersion: vi.fn().mockReturnValue(of({current: '3.9.7', latest: '3.9.7'}))
    };
    libraryServiceMock = {
      libraryState$: of({libraries: []}),
      getBookCount: vi.fn(() => of(0))
    };
    magicShelfServiceMock = {
      shelvesState$: of({shelves: []}),
      getBookCount: vi.fn(() => of(0))
    };
    shelfServiceMock = {
      shelfState$: of({shelves: []}),
      getUnshelvedBookCount: vi.fn(() => of(7)),
      getBookCount: vi.fn(() => of(0))
    };

    TestBed.configureTestingModule({
      providers: [
        {provide: Router, useValue: routerMock},
        {provide: LibraryService, useValue: libraryServiceMock},
        {provide: LibraryHealthService, useValue: {isUnhealthy$: vi.fn(() => of(false)), getInaccessibleTooltip$: vi.fn(() => of(''))}},
        {provide: ShelfService, useValue: shelfServiceMock},
        {provide: BookService, useValue: {getBooksCount}},
        {provide: VersionService, useValue: versionServiceMock},
        {provide: LibraryShelfMenuService, useValue: {
          initializeLibraryMenuItems: vi.fn(() => []),
          initializeMagicShelfMenuItems: vi.fn(() => []),
          initializeShelfMenuItems: vi.fn(() => []),
        }},
        {provide: DialogLauncherService, useValue: {openAcknowledgementsDialog: vi.fn()}},
        {provide: UserService, useValue: {
          userState$: NEVER,
          getCurrentUser: () => ({permissions: {admin: true}}),
        }},
        {provide: MagicShelfService, useValue: magicShelfServiceMock},
        {provide: SeriesDataService, useValue: {allSeries$: of([])}},
        {provide: AuthorService, useValue: {getAllAuthors: vi.fn().mockReturnValue(of([])), allAuthors$: of([])}},
        {provide: TranslocoService, useValue: {translate: (key: string) => key, getActiveLang: () => 'en', langChanges$: langChanges$.asObservable(), load: vi.fn().mockReturnValue(of(void 0)), setActiveLang: vi.fn()}},
        {provide: LocalStorageService, useValue: localStorageMock},
        {provide: BookDialogHelperService, useValue: {openBookTypeCreatorDialog: vi.fn().mockReturnValue({onClose: of(false)})}},
        {provide: MessageService, useValue: {add: vi.fn()}},
        {provide: MediaTypePreferencesService, useValue: mediaTypePreferencesMock}
      ]
    });

    component = TestBed.runInInjectionContext(() => new AppMenuComponent());
    sidebarBadgeRefreshService = TestBed.inject(SidebarBadgeRefreshService);
    component.sectionOrder = ['home', 'library', 'shelf'];
    component.sectionVisibility = {home: true, library: true, shelf: false, magicShelf: false, bookType: false};
  });

  it('blocks section reordering until reorder mode is enabled', () => {
    component.onSectionDrop({
      previousIndex: 0,
      currentIndex: 1,
      container: {data: ['home', 'library']}
    } as never);

    expect(component.sectionOrder).toEqual(['home', 'library', 'shelf']);
    expect(localStorageMock.set).not.toHaveBeenCalled();

    component.isReorderMode = true;
    component.onSectionDrop({
      previousIndex: 0,
      currentIndex: 1,
      container: {data: ['home', 'library']}
    } as never);

    expect(component.sectionOrder).toEqual(['library', 'home', 'shelf']);
    expect(localStorageMock.set).toHaveBeenCalledWith('sidebarSectionOrder', ['library', 'home', 'shelf']);
    expect(localStorageMock.trySet).toHaveBeenCalledWith('sidebarSectionOrder', ['library', 'home', 'shelf']);
  });

  it('moves visible sections with up/down controls while reorder mode is enabled', () => {
    component.isReorderMode = true;
    component.moveSectionOrder('home', 'down');

    expect(component.sectionOrder).toEqual(['library', 'home', 'shelf']);
    expect(localStorageMock.trySet).toHaveBeenCalledWith('sidebarSectionOrder', ['library', 'home', 'shelf']);
  });

  it('blocks media type navigation while reorder mode is enabled', () => {
    const event = {
      preventDefault: vi.fn(),
      stopPropagation: vi.fn()
    } as unknown as Event;

    component.isReorderMode = true;
    component.selectBookTypeFilter('CBZ', event);

    expect(routerMock.navigate).not.toHaveBeenCalled();
    expect(event.preventDefault).toHaveBeenCalled();
    expect(event.stopPropagation).toHaveBeenCalled();
  });

  it('builds media type rows through the shared menu item model with reorder persistence', async () => {
    component.ngOnInit();

    const menu = await new Promise<AppMenuItem[]>((resolve) => {
      component.bookTypeMenu$?.subscribe(resolve);
    });

    expect(menu).toHaveLength(1);
    expect(menu[0].type).toBe('mediaType');
    expect(menu[0].items?.map(item => item.label)).not.toContain('PHYSICAL');
    expect(menu[0].items?.[0].type).toBe('MediaType');
    expect(menu[0].items?.[0].menu?.length).toBeGreaterThan(0);
    expect(menu[0].items?.[0].showBookCount).toBeUndefined();

    menu[0].onItemsReorder?.([
      {label: 'PDF'} as AppMenuItem,
      {label: 'CBZ'} as AppMenuItem
    ]);

    expect(mediaTypePreferencesMock.setSidebarOrder).toHaveBeenCalledWith(['PDF', 'CBZ']);
  });

  it('does not treat physical books without file types as media type entries', () => {
    const navigationType = (component as unknown as {
      getNavigationMediaType: (book: { fileType?: string | null; isPhysical?: boolean }) => string | null;
    }).getNavigationMediaType({ isPhysical: true, fileType: null });

    expect(navigationType).toBeNull();
  });

  it('marks the Not Shelfed row to always show its count badge', async () => {
    (component as unknown as { initMenus: () => void }).initMenus();

    const menu = await new Promise<AppMenuItem[]>((resolve) => {
      component.shelfMenu$?.subscribe(resolve);
    });

    expect(menu).toHaveLength(1);
    expect(menu[0].items?.[0].routerLink).toEqual(['/not-shelfed']);
    expect(menu[0].items?.[0].showBookCount).toBe(true);
  });

  it('adds staging under dashboard in the home menu', async () => {
    component.ngOnInit();

    const menu = await firstValueFrom(component.homeMenu$!);
    const items = menu[0].items ?? [];
    const dashboardIdx = items.findIndex(item => item.routerLink?.[0] === '/dashboard');
    const stagingIdx = items.findIndex(item => item.routerLink?.[0] === '/staging');
    const stagingItem = items[stagingIdx];
    const subscription = stagingItem?.bookCount$?.subscribe();

    expect(dashboardIdx).toBeGreaterThanOrEqual(0);
    expect(stagingIdx).toBe(dashboardIdx + 1);
    expect(stagingItem?.showBookCount).toBe(true);
    expect(getBooksCount).toHaveBeenCalledWith({staged: true});

    subscription?.unsubscribe();
  });

  it('adds the dashboard settings end action to the dashboard row', async () => {
    component.ngOnInit();

    const menu = await new Promise<AppMenuItem[]>((resolve) => {
      component.homeMenu$?.subscribe(resolve);
    });

    const dashboardItem = menu[0].items?.find(item => item.routerLink?.[0] === '/dashboard');

    expect(dashboardItem?.endActionIcon).toBe('pi pi-cog');
    expect(dashboardItem?.endActionClass).toBe('dashboard-row-end-action');
    expect(dashboardItem?.endActionCommand).toBeTypeOf('function');
  });

  it('builds home counts from paged totals instead of shared full-state books', async () => {
    component.ngOnInit();

    const menu = await firstValueFrom(component.homeMenu$!);

    const allBooksItem = menu[0].items?.find(item => item.routerLink?.[0] === '/all-books');
    const physicalBooksItem = menu[0].items?.find(item => item.routerLink?.[0] === '/physical-books');
    const subscriptions = [
      allBooksItem?.bookCount$?.subscribe(),
      physicalBooksItem?.bookCount$?.subscribe(),
    ];

    expect(allBooksItem?.bookCount$).toBeDefined();
    expect(physicalBooksItem?.bookCount$).toBeDefined();
    expect(getBooksCount).toHaveBeenCalledWith();
    expect(getBooksCount).toHaveBeenCalledWith({bookType: 'PHYSICAL'});

    subscriptions.forEach(subscription => subscription?.unsubscribe());
  });

  it('refreshes home and media type badge counts after sidebar badge invalidation', async () => {
    const allBooksResponses = [9, 10];
    const physicalResponses = [3, 4];
    const mediaTypeResponses = [5, 6];

    getBooksCount.mockReset();
    getBooksCount.mockImplementation((params?: {bookType?: string; mediaTypes?: string[]}) => {
      if (params?.bookType === 'PHYSICAL') {
        return of(physicalResponses.shift() ?? 4);
      }

      if (params?.mediaTypes?.[0] === 'CBZ') {
        return of(mediaTypeResponses.shift() ?? 6);
      }

      return of(allBooksResponses.shift() ?? 10);
    });

    component.ngOnInit();

    const homeMenu = await firstValueFrom(component.homeMenu$!);
    let bookTypeMenu: AppMenuItem[] | undefined;
    const bookTypeMenuSubscription = component.bookTypeMenu$?.subscribe(menu => {
      bookTypeMenu = menu;
    });
    const allBooksValues: number[] = [];
    const physicalBooksValues: number[] = [];
    const mediaTypeValues: number[] = [];
    const allBooksItem = homeMenu[0].items?.find(item => item.routerLink?.[0] === '/all-books');
    const physicalBooksItem = homeMenu[0].items?.find(item => item.routerLink?.[0] === '/physical-books');
    const mediaTypeItem = bookTypeMenu?.[0].items?.find(item => item.label === 'CBZ');
    const subscriptions = [
      allBooksItem?.bookCount$?.subscribe(value => allBooksValues.push(value)),
      physicalBooksItem?.bookCount$?.subscribe(value => physicalBooksValues.push(value)),
      mediaTypeItem?.bookCount$?.subscribe(value => mediaTypeValues.push(value)),
    ];

    expect(allBooksValues.at(-1)).toBe(9);
    expect(physicalBooksValues.at(-1)).toBe(3);
    expect(mediaTypeValues.at(-1)).toBe(5);

    sidebarBadgeRefreshService.requestRefresh();

    expect(allBooksValues.at(-1)).toBe(10);
    expect(physicalBooksValues.at(-1)).toBe(4);
    expect(mediaTypeValues.at(-1)).toBe(6);

    bookTypeMenuSubscription?.unsubscribe();
    subscriptions.forEach(subscription => subscription?.unsubscribe());
  });

  it('refreshes library, shelf, unshelved, and magic shelf badge counts after sidebar badge invalidation', async () => {
    const libraryResponses = [2, 3];
    const magicShelfResponses = [4, 5];
    const shelfResponses = [6, 7];
    const unshelvedResponses = [8, 9];

    libraryServiceMock.libraryState$ = of({libraries: [{id: 11, name: 'Library A', watch: true, paths: []}]});
    libraryServiceMock.getBookCount = vi.fn(() => of(libraryResponses.shift() ?? 3));
    magicShelfServiceMock.shelvesState$ = of({shelves: [{id: 21, name: 'Magic A', filterJson: '{}'}]});
    magicShelfServiceMock.getBookCount = vi.fn(() => of(magicShelfResponses.shift() ?? 5));
    shelfServiceMock.shelfState$ = of({shelves: [{id: 31, name: 'Shelf A'}]});
    shelfServiceMock.getBookCount = vi.fn(() => of(shelfResponses.shift() ?? 7));
    shelfServiceMock.getUnshelvedBookCount = vi.fn(() => of(unshelvedResponses.shift() ?? 9));

    (component as unknown as { initMenus: () => void }).initMenus();

    const libraryMenu = await firstValueFrom(component.libraryMenu$!);
    const magicShelfMenu = await firstValueFrom(component.magicShelfMenu$!);
    const shelfMenu = await firstValueFrom(component.shelfMenu$!);
    const libraryValues: number[] = [];
    const magicShelfValues: number[] = [];
    const shelfValues: number[] = [];
    const unshelvedValues: number[] = [];
    const subscriptions = [
      libraryMenu[0].items?.[0].bookCount$?.subscribe(value => libraryValues.push(value)),
      magicShelfMenu[0].items?.[0].bookCount$?.subscribe(value => magicShelfValues.push(value)),
      shelfMenu[0].items?.find(item => item.routerLink?.[0] === '/shelf/31/books')?.bookCount$?.subscribe(value => shelfValues.push(value)),
      shelfMenu[0].items?.find(item => item.routerLink?.[0] === '/not-shelfed')?.bookCount$?.subscribe(value => unshelvedValues.push(value)),
    ];

    expect(libraryValues.at(-1)).toBe(2);
    expect(magicShelfValues.at(-1)).toBe(4);
    expect(shelfValues.at(-1)).toBe(6);
    expect(unshelvedValues.at(-1)).toBe(8);

    sidebarBadgeRefreshService.requestRefresh();

    expect(libraryValues.at(-1)).toBe(3);
    expect(magicShelfValues.at(-1)).toBe(5);
    expect(shelfValues.at(-1)).toBe(7);
    expect(unshelvedValues.at(-1)).toBe(9);

    subscriptions.forEach(subscription => subscription?.unsubscribe());
  });

  it('reuses cached stable tag when latest tag is temporarily unavailable', () => {
    localStorageMock.get.mockImplementation((key: string) => {
      if (key === 'sidebarLatestStableVersion') {
        return 'v3.11.1';
      }
      return null;
    });
    versionServiceMock.getVersion.mockReturnValue(of({current: 'develop-abc123', latest: 'unknown'}));

    component.ngOnInit();

    expect(component.versionInfo).toEqual({current: 'develop-abc123', latest: 'v3.11.1'});
  });

  it('caches stable latest tags for future fallbacks', () => {
    versionServiceMock.getVersion.mockReturnValue(of({current: 'develop-abc123', latest: 'v3.11.2'}));

    component.ngOnInit();

    expect(localStorageMock.set).toHaveBeenCalledWith('sidebarLatestStableVersion', 'v3.11.2');
  });

  it('caches milestone tags for future fallbacks', () => {
    versionServiceMock.getVersion.mockReturnValue(of({current: 'develop-abc123', latest: 'v3.17.15-milestone.8'}));

    component.ngOnInit();

    expect(localStorageMock.set).toHaveBeenCalledWith('sidebarLatestStableVersion', 'v3.17.15-milestone.8');
  });

  it('preserves uppercase milestone tags for future fallbacks', () => {
    versionServiceMock.getVersion.mockReturnValue(of({current: 'develop-abc123', latest: 'V3.17.17-milestone.8'}));

    component.ngOnInit();

    expect(localStorageMock.set).toHaveBeenCalledWith('sidebarLatestStableVersion', 'V3.17.17-milestone.8');
    expect(component.versionInfo).toEqual({current: 'develop-abc123', latest: 'V3.17.17-milestone.8'});
  });

  it('shows only the current tag in the sidebar label when running a tagged build', () => {
    expect(component.getDisplayVersion('v3.17.15-milestone.8', 'v3.17.15')).toBe('v3.17.15-milestone.8');
  });

  it('falls back to the latest tag in the sidebar label when the current build is untagged', () => {
    expect(component.getDisplayVersion('develop-abc123', 'v3.17.15-milestone.8')).toBe('v3.17.15-milestone.8');
  });

  it('keeps full version details in the tooltip while showing only a single tag in the label', () => {
    expect(component.getVersionTooltip('develop-abc123', 'v3.17.15-milestone.8'))
      .toBe('Current build: develop-abc123. Latest tag: v3.17.15-milestone.8.');
  });

  it('treats milestone tags as semantic versions for linking and update checks', () => {
    expect(component.isSemanticVersion('v3.17.15-milestone.8', 'v3.17.15-milestone.8')).toBe(true);
    expect(component.shouldShowUpdateLink('v3.17.15-milestone.8', 'v3.17.15')).toBe(true);
    expect(component.shouldShowUpdateLink('v3.17.15', 'v3.17.15-milestone.8')).toBe(false);
  });

  it('treats plain semantic build versions as builds instead of tagged releases', () => {
    expect(component.getDisplayVersion('3.17.17', 'V3.17.17-milestone.8')).toBe('V3.17.17-milestone.8');
    expect(component.getVersionTooltip('3.17.17', 'V3.17.17-milestone.8'))
      .toBe('Current build: 3.17.17. Latest tag: V3.17.17-milestone.8.');
  });

  it('builds version links against this fork repository', () => {
    expect(component.getVersionUrl('3.17.17', 'V3.17.17-milestone.8'))
      .toBe('https://github.com/opensourcefan/Fable/releases/tag/V3.17.17-milestone.8');
    expect(component.getVersionUrl('develop-abc123'))
      .toBe('https://github.com/opensourcefan/Fable/commit/develop-abc123');
  });

  it('stops reacting to long-lived streams after destroy', () => {
    localStorageMock.get.mockImplementation((key: string) => {
      if (key === 'sidebarSectionOrder') {
        return ['library', 'home', 'shelf'];
      }
      return null;
    });

    component.ngOnInit();

    langChanges$.next('fr');
    keyChanges$.next('sidebarSectionOrder');

    expect(component.activeLang).toBe('fr');
    expect(component.sectionOrder).toEqual(['library', 'users', 'home', 'storyArc', 'shelf', 'magicShelf', 'bookType']);

    component.ngOnDestroy();

    localStorageMock.get.mockImplementation((key: string) => {
      if (key === 'sidebarSectionOrder') {
        return ['shelf', 'home', 'library'];
      }
      return null;
    });

    langChanges$.next('de');
    keyChanges$.next('sidebarSectionOrder');

    expect(component.activeLang).toBe('fr');
    expect(component.sectionOrder).toEqual(['library', 'users', 'home', 'storyArc', 'shelf', 'magicShelf', 'bookType']);
  });
});