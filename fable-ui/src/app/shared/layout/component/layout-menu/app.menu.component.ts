import {Component, inject, OnDestroy, OnInit} from '@angular/core';
import {AppMenuitemComponent, AppMenuItem} from './app.menuitem.component';
import {AsyncPipe, NgTemplateOutlet} from '@angular/common';
import {MenuModule} from 'primeng/menu';
import {LibraryService} from '../../../../features/book/service/library.service';
import {LibraryHealthService} from '../../../../features/book/service/library-health.service';
import {BehaviorSubject, combineLatest, Observable, of, Subscription} from 'rxjs';
import {catchError, filter, map, shareReplay, startWith, switchMap} from 'rxjs/operators';
import {ShelfService} from '../../../../features/book/service/shelf.service';
import {BookService} from '../../../../features/book/service/book.service';
import {LibraryShelfMenuService} from '../../../../features/book/service/library-shelf-menu.service';
import {AppVersion, VersionService} from '../../../service/version.service';
import {DynamicDialogRef} from 'primeng/dynamicdialog';
import {UserService} from '../../../../features/settings/user-management/user.service';
import {MagicShelfService, MagicShelfState} from '../../../../features/magic-shelf/service/magic-shelf.service';
import {SeriesDataService} from '../../../../features/series-browser/service/series-data.service';
import {AuthorService} from '../../../../features/author-browser/service/author.service';
import {MenuItem, MessageService} from 'primeng/api';
import {FailureNotificationService} from '../../../../shared/service/failure-notification.service';
import {StoryArcService} from '../../../../features/story-arc/service/story-arc.service';
import {DialogLauncherService} from '../../../services/dialog-launcher.service';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {Menu} from 'primeng/menu';
import {Router} from '@angular/router';
import {TooltipModule} from 'primeng/tooltip';
import {Popover} from 'primeng/popover';
import {CheckboxModule} from 'primeng/checkbox';
import {FormsModule} from '@angular/forms';
import {AVAILABLE_LANGS, LANG_LABELS} from '../../../../core/config/transloco-loader';
import {LANG_STORAGE_KEY} from '../../../../core/config/language-initializer';
import {LocalStorageService} from '../../../service/local-storage.service';
import {CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList, moveItemInArray} from '@angular/cdk/drag-drop';
import {BookDialogHelperService} from '../../../../features/book/components/book-browser/book-dialog-helper.service';
import {MediaTypePreferencesService} from '../../../../features/book/service/media-type-preferences.service';
import {SidebarBadgeRefreshService} from '../../../../features/book/service/sidebar-badge-refresh.service';
import {NotebookService} from '../../../../features/notebook/service/notebook.service';
import {environment} from '../../../../../environments/environment';

type HomeItemVisibilityKey = 'dashboard' | 'staging' | 'allBooks' | 'physicalBooks' | 'series' | 'authors' | 'notebook';

@Component({
  selector: 'app-menu',
  standalone: true,
  imports: [AppMenuitemComponent, MenuModule, AsyncPipe, NgTemplateOutlet, TranslocoDirective, Menu, TooltipModule, CdkDropList, CdkDrag, CdkDragHandle, Popover, CheckboxModule, FormsModule],
  templateUrl: './app.menu.component.html',
  styleUrl: './app.menu.component.scss',
})
export class AppMenuComponent implements OnInit, OnDestroy {
  libraryMenu$: Observable<AppMenuItem[]> | undefined;
  usersMenu$: Observable<AppMenuItem[]> | undefined;
  shelfMenu$: Observable<AppMenuItem[]> | undefined;
  homeMenu$: Observable<AppMenuItem[]> | undefined;
  magicShelfMenu$: Observable<AppMenuItem[]> | undefined;
  storyArcMenu$: Observable<AppMenuItem[]> | undefined;
  bookTypeMenu$: Observable<AppMenuItem[]> | undefined;
  isReorderMode = false;
  activeBookTypeFilter: string | null = null;

  versionInfo: AppVersion | null = null;
  dynamicDialogRef: DynamicDialogRef | undefined | null;

  private libraryService = inject(LibraryService);
  private libraryHealthService = inject(LibraryHealthService);
  private shelfService = inject(ShelfService);
  private bookService = inject(BookService);
  private versionService = inject(VersionService);
  private libraryShelfMenuService = inject(LibraryShelfMenuService);
  private dialogLauncherService = inject(DialogLauncherService);
  private userService = inject(UserService);
  private magicShelfService = inject(MagicShelfService);
  private seriesDataService = inject(SeriesDataService);
  private authorService = inject(AuthorService);
  private t = inject(TranslocoService);
  private storyArcService = inject(StoryArcService);
  private localStorageService = inject(LocalStorageService);
  private bookDialogHelperService = inject(BookDialogHelperService);
  private messageService = inject(MessageService);
  private failureNotifications = inject(FailureNotificationService);
  private mediaTypePreferences = inject(MediaTypePreferencesService);
  private sidebarBadgeRefresh = inject(SidebarBadgeRefreshService);
  private notebookService = inject(NotebookService);

  activeLang = '';
  langMenuItems: MenuItem[] = [];
  private router = inject(Router);

  librarySortField: 'name' | 'id' = 'name';
  librarySortOrder: 'asc' | 'desc' = 'desc';
  shelfSortField: 'name' | 'id' = 'name';
  shelfSortOrder: 'asc' | 'desc' = 'asc';
  magicShelfSortField: 'name' | 'id' = 'name';
  magicShelfSortOrder: 'asc' | 'desc' = 'asc';
  sectionOrder: string[] = ['home', 'storyArc', 'library', 'users', 'shelf', 'magicShelf', 'bookType'];
  sectionVisibility: Record<string, boolean> = {
    home: true,
    library: true,
    users: true,
    shelf: true,
    magicShelf: true,
    storyArc: true,
    bookType: false,
  };
  homeItemVisibility: Record<HomeItemVisibilityKey, boolean> = {
    dashboard: true,
    staging: true,
    allBooks: true,
    physicalBooks: true,
    series: true,
    authors: true,
    notebook: true,
  };

  private readonly sectionOrderKey = 'sidebarSectionOrder';
  private readonly sectionVisibilityKey = 'sidebarSectionVisibility';
  private readonly versionLatestCacheKey = 'sidebarLatestStableVersion';
  private readonly nestedOrderPrefix = 'sidebarNestedOrder_';
  private readonly homeItemVisibilitySubject = new BehaviorSubject<Record<HomeItemVisibilityKey, boolean>>(this.homeItemVisibility);
  private readonly subscriptions = new Subscription();
  private initialSectionVisibilityFromStorage: Record<string, boolean> | null = null;
  private sectionVisibilityUserId: number | null = null;
  private touchStartX: number | null = null;
  private touchStartY: number | null = null;
  private suppressTapUntil = 0;

  readonly sectionOptions: {key: string; label: string}[] = [
    {key: 'home', label: 'layout.menu.home'},
    {key: 'library', label: 'layout.menu.libraries'},
    {key: 'users', label: 'layout.menu.users'},
    {key: 'shelf', label: 'layout.menu.shelves'},
    {key: 'magicShelf', label: 'layout.menu.magicShelves'},
    {key: 'storyArc', label: 'layout.menu.storyArcs'},
    {key: 'bookType', label: 'layout.menu.mediaType'},
  ];
  readonly homeItemOptions: {key: HomeItemVisibilityKey; label: string}[] = [
    {key: 'dashboard', label: 'layout.menu.dashboard'},
    {key: 'staging', label: 'layout.menu.staging'},
    {key: 'allBooks', label: 'layout.menu.allBooks'},
    {key: 'physicalBooks', label: 'layout.menu.physicalBooks'},
    {key: 'series', label: 'layout.menu.series'},
    {key: 'authors', label: 'layout.menu.authors'},
    {key: 'notebook', label: 'layout.menu.notebook'},
  ];

  get visibleSectionOrder(): string[] {
    const isAdmin = !!this.userService.getCurrentUser()?.permissions?.admin;
    return this.sectionOrder.filter(section => {
      if (this.sectionVisibility[section] === false) {
        return false;
      }
      if (section === 'users' && !isAdmin) {
        return false;
      }
      return true;
    });
  }

  private toastError(summary: string, detail: string, life = 3000): void {
    this.messageService.add({severity: 'error', summary, detail, life});
    this.failureNotifications.reportSafe(summary, detail);
  }

  ngOnInit(): void {
    const savedSectionOrder = this.localStorageService.get<string[]>(this.sectionOrderKey);
    if (savedSectionOrder?.length) {
      this.sectionOrder = this.normalizeSectionOrder(savedSectionOrder);
    } else {
      this.localStorageService.set(this.sectionOrderKey, this.sectionOrder);
    }

    const savedSectionVisibility = this.localStorageService.get<Record<string, boolean>>(this.sectionVisibilityKey);
    this.initialSectionVisibilityFromStorage = savedSectionVisibility ?? null;
    this.applySidebarVisibility(savedSectionVisibility);
    if (!savedSectionVisibility) {
      this.localStorageService.set(this.sectionVisibilityKey, this.buildSidebarVisibilitySettings());
    }

    this.activeLang = this.t.getActiveLang();
    this.buildLangMenu();
    this.subscriptions.add(this.t.langChanges$.subscribe((lang: string) => { this.activeLang = lang; this.buildLangMenu(); }));
    this.subscriptions.add(this.localStorageService.keyChanges$.subscribe((key: string) => {
      if (key === this.sectionOrderKey) {
        const updatedOrder = this.localStorageService.get<string[]>(this.sectionOrderKey);
        if (updatedOrder?.length) {
          this.sectionOrder = this.normalizeSectionOrder(updatedOrder);
        }
      }
      if (key === this.sectionVisibilityKey) {
        const updatedVisibility = this.localStorageService.get<Record<string, boolean>>(this.sectionVisibilityKey);
        this.applySidebarVisibility(updatedVisibility);
      }
    }));

    this.syncActiveBookTypeFilterFromUrl();
    this.subscriptions.add(this.router.events.subscribe(() => this.syncActiveBookTypeFilterFromUrl()));

    this.versionService.getVersion().pipe(
      map(data => this.resolveVersionInfoWithCache(data)),
      catchError(() => of(this.resolveVersionInfoWithCache({current: 'unknown', latest: 'unknown'})))
    ).subscribe((data) => {
      this.versionInfo = data;
      const runningVersion = environment.version || 'development';
      const serverVersion = data.current;
      if (runningVersion !== 'development' && serverVersion !== 'unknown' && runningVersion !== serverVersion) {
        this.messageService.add({
          severity: 'warn',
          summary: 'Application Update Available',
          detail: 'Fable has been upgraded. Please reload the page to ensure stability.',
          sticky: true
        });
      }
    });

    this.authorService.getAllAuthors().subscribe();
    this.storyArcService.loadStoryArcs();

    this.subscriptions.add(this.userService.userState$.pipe(
      filter(userState => !!userState?.user && userState.loaded))
      .subscribe(userState => {
        if (this.sectionVisibilityUserId !== userState.user?.id) {
          this.sectionVisibilityUserId = userState.user?.id ?? null;
          this.initializeSectionVisibilityForUser(userState.user?.id ?? null, userState.user?.userSettings.sidebarSectionVisibility);
        }

        if (userState.user?.userSettings.sidebarLibrarySorting) {
          this.librarySortField = this.validateSortField(userState.user.userSettings.sidebarLibrarySorting.field);
          this.librarySortOrder = this.validateSortOrder(userState.user.userSettings.sidebarLibrarySorting.order);
        }
        if (userState.user?.userSettings.sidebarShelfSorting) {
          this.shelfSortField = this.validateSortField(userState.user.userSettings.sidebarShelfSorting.field);
          this.shelfSortOrder = this.validateSortOrder(userState.user.userSettings.sidebarShelfSorting.order);
        }
        if (userState.user?.userSettings.sidebarMagicShelfSorting) {
          this.magicShelfSortField = this.validateSortField(userState.user.userSettings.sidebarMagicShelfSorting.field);
          this.magicShelfSortOrder = this.validateSortOrder(userState.user.userSettings.sidebarMagicShelfSorting.order);
        }
        this.initMenus();
      }));

    const allBooksCount$ = this.createRefreshableCount$(() => this.bookService.getBooksCount());
    const physicalBooksCount$ = this.createRefreshableCount$(() => this.bookService.getBooksCount({bookType: 'PHYSICAL'}));
    const stagingBooksCount$ = this.createRefreshableCount$(() => this.bookService.getBooksCount({staged: true}));

    this.homeMenu$ = combineLatest([
      this.t.langChanges$,
      this.homeItemVisibilitySubject,
    ]).pipe(
      map(() => {
        const items: AppMenuItem[] = [
          {
            label: this.t.translate('layout.menu.dashboard'),
            visibilityKey: 'dashboard',
            icon: 'pi pi-fw pi-home',
            routerLink: ['/dashboard'],
            endActionIcon: 'pi pi-cog',
            endActionTooltip: this.t.translate('dashboard.main.customizeDashboard'),
            endActionAriaLabel: this.t.translate('dashboard.main.customizeDashboard'),
            endActionClass: 'dashboard-row-end-action',
            endActionCommand: () => this.dialogLauncherService.openDashboardSettingsDialog(),
          },
          {
            label: this.t.translate('layout.menu.staging'),
            visibilityKey: 'staging',
            type: 'Staging',
            icon: 'pi pi-fw pi-inbox',
            routerLink: ['/staging'],
            bookCount$: stagingBooksCount$,
            showBookCount: true,
          },
          {
            label: this.t.translate('layout.menu.allBooks'),
            visibilityKey: 'allBooks',
            type: 'All Books',
            icon: 'pi pi-fw pi-book',
            routerLink: ['/all-books'],
            bookCount$: allBooksCount$,
          },
          {
            label: this.t.translate('layout.menu.physicalBooks'),
            visibilityKey: 'physicalBooks',
            type: 'Physical Books',
            icon: 'pi pi-fw pi-box',
            routerLink: ['/physical-books'],
            bookCount$: physicalBooksCount$,
          },
          {
            label: this.t.translate('layout.menu.series'),
            visibilityKey: 'series',
            type: 'Series',
            icon: 'pi pi-fw pi-objects-column',
            routerLink: ['/series'],
            bookCount$: this.seriesDataService.allSeries$.pipe(map(series => series.length)),
          },
          {
            label: this.t.translate('layout.menu.authors'),
            visibilityKey: 'authors',
            type: 'Authors',
            icon: 'pi pi-fw pi-users',
            routerLink: ['/authors'],
            bookCount$: this.authorService.allAuthors$.pipe(map(authors => authors?.length ?? 0)),
          },
          {
            label: this.t.translate('layout.menu.notebook'),
            visibilityKey: 'notebook',
            type: 'Notebook',
            icon: 'pi pi-fw pi-pencil',
            routerLink: ['/notebook'],
            bookCount$: this.createRefreshableCount$(() => this.notebookService.countEntries()),
          }
        ];

        return [
          {
            label: this.t.translate('layout.menu.home'),
            items: items.filter(item => !item.visibilityKey || this.isHomeItemVisible(item.visibilityKey as HomeItemVisibilityKey)),
          },
        ];
      }),
      map(menuItems => this.applyNestedItemOrder('home', menuItems))
    );

    this.bookTypeMenu$ = this.mediaTypePreferences.settings$.pipe(
      switchMap(mediaTypeSettings => {
        const mediaTypes = mediaTypeSettings.customTypes
          .map(type => type.trim())
          .filter(type => type.length > 0 && type.toUpperCase() !== 'PHYSICAL');

        if (mediaTypes.length === 0) {
          return of([
            {
              label: this.t.translate('layout.menu.mediaType'),
              type: 'mediaType',
              hasDropDown: true,
              hasCreate: true,
              onCreate: () => this.openMediaTypeCreatorDialog(),
              onItemsReorder: (items: AppMenuItem[]) => this.mediaTypePreferences.setSidebarOrder(items.map(item => item.label ?? '')),
              items: [],
            }
          ]);
        }

        return combineLatest(
          mediaTypes.map(label => {
            const count$ = this.createRefreshableCount$(() => this.bookService.getBooksCount({mediaTypes: [label]}));

            return count$.pipe(
              map(count => ({label, count, count$}))
            );
          })
        ).pipe(
          map(entries => {
            const sortedBookTypes = entries
              .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label));
            const orderedBookTypes = this.applyBookTypeOrder(sortedBookTypes, mediaTypeSettings.sidebarOrder);

            return [
              {
                label: this.t.translate('layout.menu.mediaType'),
                type: 'mediaType',
                hasDropDown: true,
                hasCreate: true,
                onCreate: () => this.openMediaTypeCreatorDialog(),
                onItemsReorder: (items: AppMenuItem[]) => this.mediaTypePreferences.setSidebarOrder(items.map(item => item.label ?? '')),
                items: orderedBookTypes.map(entry => this.createMediaTypeMenuItem(entry.label, entry.count$)),
              }
            ];
          })
        );
      })
    );
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  onSectionDrop(event: CdkDragDrop<string[]>): void {
    if (!this.isReorderMode) {
      return;
    }

    if (event.previousIndex === event.currentIndex) {
      return;
    }

    const visibleSections = [...this.visibleSectionOrder];
    moveItemInArray(visibleSections, event.previousIndex, event.currentIndex);
    this.applyVisibleSectionOrder(visibleSections);
  }

  moveSectionOrder(section: string, direction: 'up' | 'down'): void {
    if (!this.isReorderMode) {
      return;
    }

    const visibleSections = [...this.visibleSectionOrder];
    const index = visibleSections.indexOf(section);
    if (index < 0) {
      return;
    }

    const targetIndex = direction === 'up' ? index - 1 : index + 1;
    if (targetIndex < 0 || targetIndex >= visibleSections.length) {
      return;
    }

    moveItemInArray(visibleSections, index, targetIndex);
    this.applyVisibleSectionOrder(visibleSections);
  }

  toggleReorderMode(): void {
    this.isReorderMode = !this.isReorderMode;
  }

  private applyVisibleSectionOrder(visibleSections: string[]): void {
    let visibleIndex = 0;
    this.sectionOrder = this.sectionOrder.map(section => {
      if (this.sectionVisibility[section] !== false) {
        return visibleSections[visibleIndex++];
      }
      return section;
    });

    const saved = this.localStorageService.trySet(this.sectionOrderKey, this.sectionOrder);
    this.notifyReorderPersistence(saved);
  }

  private notifyReorderPersistence(saved: boolean): void {
    if (saved) {
      this.messageService.add({
        severity: 'success',
        summary: this.t.translate('layout.menu.reorderSaved'),
        detail: this.t.translate('layout.menu.reorderSavedDetail'),
        life: 1600,
      });
      return;
    }

    this.messageService.add({
      severity: 'warn',
      summary: this.t.translate('layout.menu.reorderSaveFailed'),
      detail: this.t.translate('layout.menu.reorderSaveFailedDetail'),
      life: 4000,
    });
  }

  getSectionHeading(section: string): string {
    switch (section) {
      case 'home':
        return this.t.translate('layout.menu.home');
      case 'library':
        return this.t.translate('layout.menu.libraries');
      case 'users':
        return this.t.translate('layout.menu.users');
      case 'shelf':
        return this.t.translate('layout.menu.shelves');
      case 'magicShelf':
        return this.t.translate('layout.menu.magicShelves');
      case 'storyArc':
        return this.t.translate('layout.menu.storyArcs');
      case 'bookType':
        return this.t.translate('layout.menu.mediaType');
      default:
        return section;
    }
  }

  selectBookTypeFilter(bookType: string, event?: Event): void {
    if (this.isReorderMode) {
      event?.preventDefault();
      event?.stopPropagation();
      return;
    }

    if (this.shouldSuppressTap()) {
      event?.preventDefault();
      event?.stopPropagation();
      return;
    }

    this.router.navigate(['/all-books'], {
      queryParams: {
        filter: `customMediaType:${encodeURIComponent(bookType)}`,
      }
    });
  }

  isBookTypeFilterActive(bookType: string): boolean {
    return this.activeBookTypeFilter === bookType;
  }

  onTouchStart(event: TouchEvent): void {
    const touch = event.touches[0];
    if (!touch) {
      return;
    }
    this.touchStartX = touch.clientX;
    this.touchStartY = touch.clientY;
  }

  onTouchEnd(event: TouchEvent): void {
    const touch = event.changedTouches[0];
    if (touch && this.touchStartX != null && this.touchStartY != null) {
      const deltaX = Math.abs(touch.clientX - this.touchStartX);
      const deltaY = Math.abs(touch.clientY - this.touchStartY);
      if (deltaX > 8 || deltaY > 8) {
        this.suppressTapUntil = Date.now() + 250;
      }
    }
    this.touchStartX = null;
    this.touchStartY = null;
  }

  onTouchCancel(): void {
    this.touchStartX = null;
    this.touchStartY = null;
    this.suppressTapUntil = Date.now() + 250;
  }

  openMediaTypeCreatorDialog(): void {
    if (this.isReorderMode) {
      return;
    }

    const dialogRef = this.bookDialogHelperService.openBookTypeCreatorDialog();
    dialogRef.onClose.subscribe((result: {created?: boolean; type?: string} | boolean) => {
      if (!result) {
        return;
      }
      const created = typeof result === 'boolean' ? result : !!result.created;
      if (created) {
        const type = typeof result === 'object' ? result.type : undefined;
        if (type) {
          this.selectBookTypeFilter(type);
        }
      }
    });
  }

  getMediaTypeMenuItems(mediaType: string): AppMenuItem[] {
    return [
      {
        label: 'Edit Media Type',
        icon: 'pi pi-pencil',
        command: () => this.editMediaType(mediaType)
      },
      {
        label: 'Delete Media Type',
        icon: 'pi pi-trash',
        command: () => this.deleteMediaType(mediaType)
      }
    ];
  }

  toggleSectionVisibility(section: string): void {
    const currentlyVisible = this.sectionVisibility[section] !== false;
    const visibleCount = this.visibleSectionOrder.length;

    if (currentlyVisible && visibleCount <= 1) {
      return;
    }

    this.sectionVisibility = {
      ...this.sectionVisibility,
      [section]: !currentlyVisible,
    };

    this.localStorageService.set(this.sectionVisibilityKey, this.buildSidebarVisibilitySettings());
    this.persistSectionVisibility();
  }

  isSectionVisible(section: string): boolean {
    return this.sectionVisibility[section] !== false;
  }

  getSectionOptionLabel(option: {key: string; label: string}): string {
    return this.t.translate(option.label);
  }

  onSectionVisibilityCheckboxChange(section: string, visible: boolean): void {
    const currentlyVisible = this.sectionVisibility[section] !== false;
    if (visible === currentlyVisible) {
      return;
    }
    this.toggleSectionVisibility(section);
  }

  isHomeItemVisible(key: HomeItemVisibilityKey): boolean {
    return this.homeItemVisibility[key] !== false;
  }

  getHomeItemOptionLabel(option: {key: HomeItemVisibilityKey; label: string}): string {
    return this.t.translate(option.label);
  }

  onHomeItemVisibilityCheckboxChange(key: HomeItemVisibilityKey, visible: boolean): void {
    const currentlyVisible = this.isHomeItemVisible(key);
    if (visible === currentlyVisible) {
      return;
    }

    const visibleCount = Object.values(this.homeItemVisibility).filter(Boolean).length;
    if (currentlyVisible && visibleCount <= 1) {
      return;
    }

    this.homeItemVisibility = {
      ...this.homeItemVisibility,
      [key]: visible,
    };
    this.homeItemVisibilitySubject.next({...this.homeItemVisibility});
    this.localStorageService.set(this.sectionVisibilityKey, this.buildSidebarVisibilitySettings());
    this.persistSectionVisibility();
  }

  navigateToSettings(): void {
    this.router.navigate(['/settings'], {queryParams: {returnTo: this.router.url}});
  }

  openAcknowledgementsDialog(): void {
    this.dialogLauncherService.openAcknowledgementsDialog();
  }

  switchLanguage(lang: string): void {
    if (lang === this.activeLang) return;
    this.t.load(lang).subscribe(() => {
      this.t.setActiveLang(lang);
      localStorage.setItem(LANG_STORAGE_KEY, lang);
      this.activeLang = lang;
      this.buildLangMenu();
    });
  }

  private buildLangMenu(): void {
    this.langMenuItems = AVAILABLE_LANGS.map((lang: string) => ({
      label: LANG_LABELS[lang] || lang,
      icon: lang === this.activeLang ? 'pi pi-check' : undefined,
      command: () => this.switchLanguage(lang),
    }));
  }

  private syncActiveBookTypeFilterFromUrl(): void {
    const filterParam = this.router.parseUrl(this.router.url).queryParams['filter'];
    if (typeof filterParam !== 'string' || !filterParam) {
      this.activeBookTypeFilter = null;
      return;
    }

    const entries = filterParam.split(',');
    const customTypeEntry = entries.find(entry => entry.startsWith('customMediaType:'))
      ?? entries.find(entry => entry.startsWith('customBookType:'));
    if (!customTypeEntry) {
      this.activeBookTypeFilter = null;
      return;
    }


    const keyLength = customTypeEntry.startsWith('customMediaType:')
      ? 'customMediaType:'.length
      : 'customBookType:'.length;
    const rawValue = customTypeEntry.substring(keyLength).split('|')[0]?.trim();
    const decodedValue = rawValue ? decodeURIComponent(rawValue) : null;
    this.activeBookTypeFilter = decodedValue === 'PHYSICAL' ? null : decodedValue;
  }

  private getStoredCustomBookTypes(): string[] {
    return this.mediaTypePreferences.getCustomTypes();
  }

  private setStoredMediaTypes(types: string[]): void {
    this.mediaTypePreferences.setCustomTypes(types);
  }

  private createMediaTypeMenuItem(label: string, count$: Observable<number>): AppMenuItem {
    return {
      label,
      type: 'MediaType',
      icon: 'pi pi-file',
      menu: this.getMediaTypeMenuItems(label),
      routerLink: ['/all-books'],
      queryParams: {
        filter: `customMediaType:${encodeURIComponent(label)}`,
      },
      activeMatch: () => this.isBookTypeFilterActive(label),
      bookCount$: count$,
    };
  }

  private createRefreshableCount$(countFactory: () => Observable<number>): Observable<number> {
    return this.sidebarBadgeRefresh.refresh$.pipe(
      startWith(void 0),
      switchMap(() => countFactory().pipe(catchError(() => of(0)))),
      shareReplay({bufferSize: 1, refCount: true})
    );
  }

  private getNavigationMediaType(book: { fileType?: string | null; isPhysical?: boolean }): string | null {
    const fileType = (book.fileType ?? '').trim();
    if (!fileType || fileType.toUpperCase() === 'PHYSICAL') {
      return null;
    }

    return fileType;
  }

  private openMediaTypeManagerDialog(): void {
    this.bookDialogHelperService.openMediaTypeManagerDialog();
  }

  private editMediaType(mediaType: string): void {
    const next = window.prompt('Edit Media Type', mediaType)?.trim();
    if (!next || next === mediaType) {
      return;
    }

    const existing = this.getStoredCustomBookTypes();
    if (existing.some(type => type.toLowerCase() === next.toLowerCase() && type.toLowerCase() !== mediaType.toLowerCase())) {
      this.messageService.add({severity: 'warn', summary: 'Media Type exists', detail: 'That Media Type already exists.'});
      return;
    }

    const updated = existing.map(type => type.toLowerCase() === mediaType.toLowerCase() ? next : type);
    this.setStoredMediaTypes([...new Set(updated)].sort((a, b) => a.localeCompare(b)));

    const ids = new Set((this.bookService.getCurrentBookState().books ?? [])
      .filter(book => (book.fileType ?? '').trim().toLowerCase() === mediaType.toLowerCase())
      .map(book => book.id));

    if (!ids.size) {
      if (this.activeBookTypeFilter?.toLowerCase() === mediaType.toLowerCase()) {
        this.selectBookTypeFilter(next);
      }
      this.messageService.add({severity: 'success', summary: 'Success', detail: 'Media Type renamed.'});
      return;
    }

    this.bookService.updateFileType(ids, next).subscribe({
      next: () => {
        if (this.activeBookTypeFilter?.toLowerCase() === mediaType.toLowerCase()) {
          this.selectBookTypeFilter(next);
        }
        this.messageService.add({severity: 'success', summary: 'Success', detail: 'Media Type renamed.'});
      },
      error: () => {
        this.toastError('Error', 'Failed to rename Media Type.', 3000);
      }
    });
  }

  private deleteMediaType(mediaType: string): void {
    if (!window.confirm(`Delete Media Type "${mediaType}"?`)) {
      return;
    }

    const updated = this.getStoredCustomBookTypes().filter(type => type.toLowerCase() !== mediaType.toLowerCase());
    this.setStoredMediaTypes(updated);

    const ids = new Set((this.bookService.getCurrentBookState().books ?? [])
      .filter(book => (book.fileType ?? '').trim().toLowerCase() === mediaType.toLowerCase())
      .map(book => book.id));

    if (!ids.size) {
      if (this.activeBookTypeFilter?.toLowerCase() === mediaType.toLowerCase()) {
        this.router.navigate(['/all-books'], {
          queryParams: {filter: null},
          queryParamsHandling: 'merge'
        });
      }
      this.messageService.add({severity: 'success', summary: 'Success', detail: 'Media Type deleted.'});
      return;
    }

    this.bookService.updateFileType(ids, null).subscribe({
      next: () => {
        if (this.activeBookTypeFilter?.toLowerCase() === mediaType.toLowerCase()) {
          this.router.navigate(['/all-books'], {
            queryParams: {filter: null},
            queryParamsHandling: 'merge'
          });
        }
        this.messageService.add({severity: 'success', summary: 'Success', detail: 'Media Type deleted.'});
      },
      error: () => {
        this.toastError('Error', 'Failed to delete Media Type.', 3000);
      }
    });
  }

  private initMenus(): void {
    this.libraryMenu$ = combineLatest([this.libraryService.libraryState$, this.t.langChanges$]).pipe(
      map(([state]) => {
        const isAdmin = !!this.userService.getCurrentUser()?.permissions?.admin;
        // Guests: all assigned libs (including personal). Admin: shared only — personal/shown go under USERS.
        const libraries = (state.libraries ?? []).filter(lib => !isAdmin || !lib.ownerUserId);
        const sortedLibraries = this.sortArray(libraries, this.librarySortField, this.librarySortOrder);
        return [
          {
            label: this.t.translate('layout.menu.libraries'),
            type: 'library',
            hasDropDown: true,
            hasCreate: true,
            items: sortedLibraries.map((library) => ({
              menu: this.libraryShelfMenuService.initializeLibraryMenuItems(library),
              label: library.name,
              type: 'Library',
              icon: library.icon || undefined,
              iconType: (library.iconType || undefined) as 'PRIME_NG' | 'CUSTOM_SVG' | undefined,
              routerLink: [`/library/${library.id}/books`],
              prefetchLibraryId: library.id ?? undefined,
              bookCount$: this.createRefreshableCount$(() => this.libraryService.getBookCount(library.id ?? 0)),
              unhealthy$: this.libraryHealthService.isUnhealthy$(library.id ?? 0),
              unhealthyTooltip$: this.libraryHealthService.getInaccessibleTooltip$(
                library.id ?? 0,
                (library.paths ?? []).filter(p => p.accessible === false).map(p => p.path),
                this.t.translate('common.libraryPathInaccessible')
              ),
            })),
          },
        ];
      }),
      map(menuItems => this.applyNestedItemOrder('library', menuItems))
    );

    this.usersMenu$ = combineLatest([this.libraryService.libraryState$, this.t.langChanges$]).pipe(
      map(([state]) => {
        const userLibraries = (state.libraries ?? []).filter(
          lib => !!lib.ownerUserId && !!lib.showInAdminCatalog
        );
        if (userLibraries.length === 0) {
          return [];
        }
        const sortedLibraries = this.sortArray(userLibraries, this.librarySortField, this.librarySortOrder);
        return [
          {
            label: this.t.translate('layout.menu.users'),
            type: 'users',
            hasDropDown: true,
            hasCreate: false,
            items: sortedLibraries.map((library) => ({
              menu: this.libraryShelfMenuService.initializeLibraryMenuItems(library),
              label: library.name,
              type: 'Library',
              icon: library.icon || 'pi pi-user',
              iconType: (library.iconType || 'PRIME_NG') as 'PRIME_NG' | 'CUSTOM_SVG' | undefined,
              routerLink: [`/library/${library.id}/books`],
              prefetchLibraryId: library.id ?? undefined,
              bookCount$: this.createRefreshableCount$(() => this.libraryService.getBookCount(library.id ?? 0)),
              unhealthy$: this.libraryHealthService.isUnhealthy$(library.id ?? 0),
              unhealthyTooltip$: this.libraryHealthService.getInaccessibleTooltip$(
                library.id ?? 0,
                (library.paths ?? []).filter(p => p.accessible === false).map(p => p.path),
                this.t.translate('common.libraryPathInaccessible')
              ),
            })),
          },
        ];
      }),
      map(menuItems => this.applyNestedItemOrder('users', menuItems))
    );

    this.magicShelfMenu$ = combineLatest([this.magicShelfService.shelvesState$, this.t.langChanges$]).pipe(
      map(([state]: [MagicShelfState, string]) => {
        const shelves = state.shelves ?? [];
        const sortedShelves = this.sortArray(shelves, this.magicShelfSortField, this.magicShelfSortOrder);
        return [
          {
            label: this.t.translate('layout.menu.magicShelves'),
            type: 'magicShelf',
            hasDropDown: true,
            hasCreate: true,
            items: sortedShelves.map((shelf) => ({
              label: shelf.name,
              type: 'magicShelfItem',
              icon: shelf.icon || undefined,
              iconType: (shelf.iconType || undefined) as 'PRIME_NG' | 'CUSTOM_SVG' | undefined,
              menu: this.libraryShelfMenuService.initializeMagicShelfMenuItems(shelf),
              routerLink: [`/magic-shelf/${shelf.id}/books`],
              bookCount$: this.createRefreshableCount$(() => this.magicShelfService.getBookCount(shelf.id ?? 0)),
            })),
          },
        ];
      }),
      map(menuItems => this.applyNestedItemOrder('magicShelf', menuItems))
    );

    this.storyArcMenu$ = combineLatest([this.storyArcService.storyArcs$, this.t.langChanges$]).pipe(
      map(([arcs]) => {
        return [
          {
            label: this.t.translate('layout.menu.storyArcs') || 'Story Arcs',
            type: 'storyArc',
            hasDropDown: true,
            routerLink: ['/story-arcs'],
            items: arcs.map(arc => ({
              label: arc.storyArcName,
              type: 'StoryArc',
              icon: 'pi pi-compass',
              // Pass the raw name — Angular's routerLink encodes once.
              // Pre-encoding with encodeURIComponent caused The%2520Rocketeer and Tomcat 400 Whitelabel.
              routerLink: [`/story-arc/${arc.storyArcName}`],
              bookCount$: of(arc.bookCount)
            }))
          }
        ];
      }),
      map(menuItems => this.applyNestedItemOrder('storyArc', menuItems))
    );

    this.shelfMenu$ = combineLatest([this.shelfService.shelfState$, this.t.langChanges$]).pipe(
      map(([state]) => {
        const shelves = state.shelves ?? [];
        const sortedShelves = this.sortArray(shelves, this.shelfSortField, this.shelfSortOrder);

        const koboShelfIndex = sortedShelves.findIndex(shelf => shelf.name === 'Kobo');
        let koboShelf = null;
        if (koboShelfIndex !== -1) {
          koboShelf = sortedShelves.splice(koboShelfIndex, 1)[0];
        }

        const shelfItems = sortedShelves.map((shelf) => ({
          menu: this.libraryShelfMenuService.initializeShelfMenuItems(shelf),
          label: shelf.name,
          type: 'Shelf',
          icon: shelf.icon || undefined,
          iconType: (shelf.iconType || undefined) as 'PRIME_NG' | 'CUSTOM_SVG' | undefined,
          routerLink: [`/shelf/${shelf.id}/books`],
          bookCount$: this.createRefreshableCount$(() => this.shelfService.getBookCount(shelf.id ?? 0)),
          shelfId: shelf.id,
          isPublicShelf: shelf.publicShelf,
        }));

        const notShelfedItem = {
          label: this.t.translate('layout.menu.unshelved'),
          type: 'Shelf',
          icon: 'pi pi-inbox',
          iconType: 'PRIME_NG' as 'PRIME_NG' | 'CUSTOM_SVG',
          routerLink: ['/not-shelfed'],
          bookCount$: this.createRefreshableCount$(() => this.shelfService.getUnshelvedBookCount?.() ?? of(0)),
          showBookCount: true,
        };

          const items: AppMenuItem[] = [notShelfedItem];
        if (koboShelf) {
          items.push({
            label: koboShelf.name,
            type: 'Shelf',
            icon: koboShelf.icon || undefined,
            iconType: (koboShelf.iconType || undefined) as 'PRIME_NG' | 'CUSTOM_SVG' | undefined,
            routerLink: [`/shelf/${koboShelf.id}/books`],
            bookCount$: this.createRefreshableCount$(() => this.shelfService.getBookCount(koboShelf.id ?? 0)),
            shelfId: koboShelf.id,
            isKoboShelf: true,
            isPublicShelf: koboShelf.publicShelf,
          });
        }
        items.push(...shelfItems);

        return [
          {
            type: 'shelf',
            label: this.t.translate('layout.menu.shelves'),
            hasDropDown: true,
            hasCreate: true,
            items,
          },
        ];
      }),
      map(menuItems => this.applyNestedItemOrder('shelf', menuItems))
    );
  }

  openChangelogDialog() {
    this.dialogLauncherService.openVersionChangelogDialog();
  }

  getVersionUrl(current: string | undefined, latest?: string | undefined): string {
    const displayedTag = this.getDisplayedTag(current, latest);
    if (displayedTag) {
      return `https://github.com/opensourcefan/Fable/releases/tag/${displayedTag}`;
    }

    const version = this.getNormalizedDisplayVersion(current);
    if (!version) return '#';
    return `https://github.com/opensourcefan/Fable/commit/${version}`;
  }

  isSemanticVersion(current: string | undefined, latest?: string | undefined): boolean {
    return !!this.getDisplayedTag(current, latest);
  }

  shouldShowUpdateLink(current: string | undefined, latest?: string | undefined): boolean {
    const comparison = this.compareSemanticVersions(current, latest);
    return comparison !== null && comparison < 0;
  }

  getDisplayVersion(current: string | undefined, latest?: string | undefined): string {
    return this.getDisplayedTag(current, latest)
      ?? this.getNormalizedDisplayVersion(current)
      ?? this.getNormalizedDisplayVersion(latest)
      ?? 'unknown';
  }

  getVersionTooltip(current: string | undefined, latest?: string | undefined): string {
    const normalizedCurrent = this.getNormalizedDisplayVersion(current);
    const latestTagValue = this.getSemanticTagValue(latest);
    const currentTagValue = this.getCurrentTagValue(current);
    const comparison = this.compareSemanticVersions(current, latest);

    if (currentTagValue && latestTagValue) {
      return comparison !== null && comparison < 0
        ? `Current tag: ${currentTagValue}. Latest tag: ${latestTagValue}. Update available.`
        : `Current tag: ${currentTagValue}. Latest tag: ${latestTagValue}.`;
    }

    if (currentTagValue) {
      return `Current tag: ${currentTagValue}.`;
    }

    if (normalizedCurrent && latestTagValue) {
      return `Current build: ${normalizedCurrent}. Latest tag: ${latestTagValue}.`;
    }

    if (normalizedCurrent) {
      return `Current build: ${normalizedCurrent}. Latest tag unavailable.`;
    }

    if (latestTagValue) {
      return `Latest tag: ${latestTagValue}.`;
    }

    return 'Version information is temporarily unavailable.';
  }

  private getNormalizedDisplayVersion(version: string | undefined): string | undefined {
    if (!version) {
      return undefined;
    }

    const trimmed = version.trim();
    return trimmed.length ? trimmed : undefined;
  }

  private getNormalizedSemanticVersion(version: string | undefined): string | null {
    return this.parseSemanticVersion(version)?.normalized ?? null;
  }

  private getSemanticTagValue(version: string | undefined): string | null {
    return this.parseSemanticVersion(version)?.raw ?? null;
  }

  private resolveVersionInfoWithCache(versionInfo: AppVersion): AppVersion {
    const normalizedCurrent = this.getNormalizedDisplayVersion(versionInfo.current) ?? 'unknown';
    const latestTagValue = this.getSemanticTagValue(versionInfo.latest);

    if (latestTagValue) {
      this.localStorageService.set(this.versionLatestCacheKey, latestTagValue);
      return {
        current: normalizedCurrent,
        latest: latestTagValue,
      };
    }

    const cachedLatest = this.getCachedLatestStableVersion();
    if (cachedLatest) {
      return {
        current: normalizedCurrent,
        latest: cachedLatest,
      };
    }

    return {
      current: normalizedCurrent,
      latest: this.getNormalizedDisplayVersion(versionInfo.latest) ?? 'unknown',
    };
  }

  private getCachedLatestStableVersion(): string | null {
    const cached = this.localStorageService.get<string>(this.versionLatestCacheKey);
    return this.getSemanticTagValue(cached ?? undefined);
  }

  private getDisplayedTag(current: string | undefined, latest?: string | undefined): string | null {
    return this.getCurrentTagValue(current) ?? this.getSemanticTagValue(latest);
  }

  private getCurrentTagValue(version: string | undefined): string | null {
    if (!version || !version.trim().match(/^[vV]/)) {
      return null;
    }

    return this.getSemanticTagValue(version);
  }

  private parseSemanticVersion(version: string | undefined): { major: number; minor: number; patch: number; preRelease: string[]; normalized: string; raw: string } | null {
    if (!version) {
      return null;
    }

    const raw = version.trim();
    const match = raw.match(/^[vV]?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+([0-9A-Za-z.-]+))?$/);
    if (!match) {
      return null;
    }

    const normalized = `v${match[1]}.${match[2]}.${match[3]}`
      + (match[4] ? `-${match[4]}` : '')
      + (match[5] ? `+${match[5]}` : '');

    return {
      major: Number(match[1]),
      minor: Number(match[2]),
      patch: Number(match[3]),
      preRelease: match[4] ? match[4].split('.') : [],
      normalized,
      raw,
    };
  }

  private compareSemanticVersions(version1: string | undefined, version2: string | undefined): number | null {
    const parsedVersion1 = this.parseSemanticVersion(version1);
    const parsedVersion2 = this.parseSemanticVersion(version2);
    if (!parsedVersion1 || !parsedVersion2) {
      return null;
    }

    const majorComparison = parsedVersion1.major - parsedVersion2.major;
    if (majorComparison !== 0) {
      return majorComparison;
    }

    const minorComparison = parsedVersion1.minor - parsedVersion2.minor;
    if (minorComparison !== 0) {
      return minorComparison;
    }

    const patchComparison = parsedVersion1.patch - parsedVersion2.patch;
    if (patchComparison !== 0) {
      return patchComparison;
    }

    const version1HasPreRelease = parsedVersion1.preRelease.length > 0;
    const version2HasPreRelease = parsedVersion2.preRelease.length > 0;
    if (!version1HasPreRelease && !version2HasPreRelease) {
      return 0;
    }
    if (!version1HasPreRelease) {
      return 1;
    }
    if (!version2HasPreRelease) {
      return -1;
    }

    const maxIdentifiers = Math.max(parsedVersion1.preRelease.length, parsedVersion2.preRelease.length);
    for (let index = 0; index < maxIdentifiers; index += 1) {
      const identifier1 = parsedVersion1.preRelease[index];
      const identifier2 = parsedVersion2.preRelease[index];

      if (identifier1 === undefined) {
        return -1;
      }
      if (identifier2 === undefined) {
        return 1;
      }

      const identifierComparison = this.comparePreReleaseIdentifier(identifier1, identifier2);
      if (identifierComparison !== 0) {
        return identifierComparison;
      }
    }

    return 0;
  }

  private comparePreReleaseIdentifier(identifier1: string, identifier2: string): number {
    const numericIdentifierPattern = /^\d+$/;
    const identifier1IsNumeric = numericIdentifierPattern.test(identifier1);
    const identifier2IsNumeric = numericIdentifierPattern.test(identifier2);

    if (identifier1IsNumeric && identifier2IsNumeric) {
      return Number(identifier1) - Number(identifier2);
    }
    if (identifier1IsNumeric) {
      return -1;
    }
    if (identifier2IsNumeric) {
      return 1;
    }

    return identifier1.localeCompare(identifier2);
  }

  private sortArray<T>(array: T[], field: 'name' | 'id', order: 'asc' | 'desc'): T[] {
    return [...array].sort((a, b) => {
      const aVal = (a as Record<string, unknown>)[field] ?? '';
      const bVal = (b as Record<string, unknown>)[field] ?? '';
      let comparison = 0;

      if (typeof aVal === 'string' && typeof bVal === 'string') {
        comparison = aVal.localeCompare(bVal);
      } else if (typeof aVal === 'number' && typeof bVal === 'number') {
        comparison = aVal - bVal;
      }

      return order === 'asc' ? comparison : -comparison;
    });
  }

  private validateSortField(field: string): 'name' | 'id' {
    return field === 'id' ? 'id' : 'name';
  }

  private validateSortOrder(order: string): 'asc' | 'desc' {
    return order === 'desc' ? 'desc' : 'asc';
  }

  private normalizeSectionOrder(savedOrder: string[]): string[] {
    const defaults = ['home', 'storyArc', 'library', 'users', 'shelf', 'magicShelf', 'bookType'];
    const filtered = savedOrder.filter(section => defaults.includes(section));
    for (const section of defaults) {
      if (!filtered.includes(section)) {
        const homeIndex = filtered.indexOf('home');
        const libraryIndex = filtered.indexOf('library');
        if (section === 'storyArc' && homeIndex !== -1) {
          filtered.splice(homeIndex + 1, 0, 'storyArc');
        } else if (section === 'users' && libraryIndex !== -1) {
          filtered.splice(libraryIndex + 1, 0, 'users');
        } else {
          filtered.push(section);
        }
      }
    }
    return filtered;
  }

  private normalizeSectionVisibility(savedVisibility: Record<string, boolean> | null | undefined): Record<string, boolean> {
    return {
      home: savedVisibility?.['home'] ?? true,
      library: savedVisibility?.['library'] ?? true,
      users: savedVisibility?.['users'] ?? true,
      shelf: savedVisibility?.['shelf'] ?? true,
      magicShelf: savedVisibility?.['magicShelf'] ?? true,
      storyArc: savedVisibility?.['storyArc'] ?? true,
      bookType: savedVisibility?.['bookType'] ?? false,
    };
  }

  private normalizeHomeItemVisibility(savedVisibility: Record<string, boolean> | null | undefined): Record<HomeItemVisibilityKey, boolean> {
    return {
      dashboard: savedVisibility?.['home.dashboard'] ?? true,
      staging: savedVisibility?.['home.staging'] ?? true,
      allBooks: savedVisibility?.['home.allBooks'] ?? true,
      physicalBooks: savedVisibility?.['home.physicalBooks'] ?? true,
      series: savedVisibility?.['home.series'] ?? true,
      authors: savedVisibility?.['home.authors'] ?? true,
      notebook: savedVisibility?.['home.notebook'] ?? true,
    };
  }

  private initializeSectionVisibilityForUser(userId: number | null, persistedVisibility: Record<string, boolean> | null | undefined): void {
    const normalizedPersisted = this.buildNormalizedSidebarVisibility(persistedVisibility);

    if (persistedVisibility) {
      this.applySidebarVisibility(normalizedPersisted);
      this.localStorageService.set(this.sectionVisibilityKey, normalizedPersisted);
      if (userId != null && this.requiresSidebarVisibilityMigration(persistedVisibility, normalizedPersisted)) {
        this.userService.updateUserSetting(userId, 'sidebarSectionVisibility', normalizedPersisted);
      }
      return;
    }

    if (this.initialSectionVisibilityFromStorage) {
      const migrated = this.buildNormalizedSidebarVisibility(this.initialSectionVisibilityFromStorage);
      this.applySidebarVisibility(migrated);
      if (userId != null) {
        this.userService.updateUserSetting(userId, 'sidebarSectionVisibility', migrated);
      }
      return;
    }

    this.applySidebarVisibility(undefined);
  }

  private persistSectionVisibility(): void {
    const userId = this.userService.getCurrentUser()?.id;
    if (userId == null) {
      return;
    }

    this.userService.updateUserSetting(userId, 'sidebarSectionVisibility', this.buildSidebarVisibilitySettings());
  }

  private applySidebarVisibility(savedVisibility: Record<string, boolean> | null | undefined): void {
    this.sectionVisibility = this.normalizeSectionVisibility(savedVisibility);
    this.homeItemVisibility = this.normalizeHomeItemVisibility(savedVisibility);
    this.homeItemVisibilitySubject.next({...this.homeItemVisibility});
  }

  private buildNormalizedSidebarVisibility(savedVisibility: Record<string, boolean> | null | undefined): Record<string, boolean> {
    const homeItemVisibility = this.normalizeHomeItemVisibility(savedVisibility);

    return {
      ...this.normalizeSectionVisibility(savedVisibility),
      'home.dashboard': homeItemVisibility.dashboard,
      'home.staging': homeItemVisibility.staging,
      'home.allBooks': homeItemVisibility.allBooks,
      'home.physicalBooks': homeItemVisibility.physicalBooks,
      'home.series': homeItemVisibility.series,
      'home.authors': homeItemVisibility.authors,
      'home.notebook': homeItemVisibility.notebook,
    };
  }

  private buildSidebarVisibilitySettings(): Record<string, boolean> {
    return {
      ...this.sectionVisibility,
      'home.dashboard': this.homeItemVisibility.dashboard,
      'home.staging': this.homeItemVisibility.staging,
      'home.allBooks': this.homeItemVisibility.allBooks,
      'home.physicalBooks': this.homeItemVisibility.physicalBooks,
      'home.series': this.homeItemVisibility.series,
      'home.authors': this.homeItemVisibility.authors,
      'home.notebook': this.homeItemVisibility.notebook,
    };
  }

  private requiresSidebarVisibilityMigration(
    persistedVisibility: Record<string, boolean>,
    normalizedVisibility: Record<string, boolean>
  ): boolean {
    return Object.keys(normalizedVisibility).some(key => persistedVisibility[key] !== normalizedVisibility[key]);
  }


  private applyNestedItemOrder(menuKey: string, menuItems: AppMenuItem[]): AppMenuItem[] {
    const savedOrder = this.localStorageService.get<string[]>(`${this.nestedOrderPrefix}${menuKey}`);
    if (!savedOrder?.length) {
      return menuItems;
    }

    return menuItems.map(item => {
      if (!item.items?.length) {
        return item;
      }

      const items = [...item.items];
      const lookup = new Map(items.map(child => [this.getMenuItemOrderId(child), child]));
      const ordered: AppMenuItem[] = [];

      for (const id of savedOrder) {
        const match = lookup.get(id);
        if (match) {
          ordered.push(match);
          lookup.delete(id);
        }
      }

      for (const child of items) {
        const id = this.getMenuItemOrderId(child);
        if (!lookup.has(id)) {
          continue;
        }
        lookup.delete(id);
        // New Staging item should sit directly under Dashboard even when a prior nested order exists.
        if (menuKey === 'home' && child.visibilityKey === 'staging') {
          const dashboardIdx = ordered.findIndex(entry => entry.visibilityKey === 'dashboard');
          if (dashboardIdx >= 0) {
            ordered.splice(dashboardIdx + 1, 0, child);
            continue;
          }
        }
        ordered.push(child);
      }

      return {
        ...item,
        items: ordered,
      };
    });
  }

  private applyBookTypeOrder<T extends {label: string}>(bookTypes: T[], savedOrder: string[]): T[] {
    if (!savedOrder.length) {
      return bookTypes;
    }

    const lookup = new Map(bookTypes.map(type => [type.label, type]));
    const ordered: T[] = [];

    for (const label of savedOrder) {
      const match = lookup.get(label);
      if (match) {
        ordered.push(match);
        lookup.delete(label);
      }
    }

    for (const type of bookTypes) {
      if (lookup.has(type.label)) {
        ordered.push(type);
        lookup.delete(type.label);
      }
    }

    return ordered;
  }

  private getMenuItemOrderId(item: MenuItem): string {
    const link = Array.isArray(item.routerLink) ? item.routerLink[0] : item.routerLink;
    return String(link ?? item.label ?? '');
  }
  private shouldSuppressTap(): boolean {
    return Date.now() < this.suppressTapUntil;
  }
}
