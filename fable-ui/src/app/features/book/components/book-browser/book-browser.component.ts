import {
  AfterViewInit,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  ElementRef,
  inject,
  OnDestroy,
  OnInit,
  QueryList,
  NgZone,
  signal,
  ViewChild,
  ViewChildren
} from '@angular/core';
import {ActivatedRoute, NavigationStart, Router} from '@angular/router';
import {ConfirmationService, MenuItem, MessageService} from 'primeng/api';
import {AiSearchScanProgressService} from '../../../../shared/service/ai-search-scan-progress.service';
import {PageTitleService} from '../../../../shared/service/page-title.service';
import {BookService, RemoveFromLibraryMode} from '../../service/book.service';
import {BookMetadataManageService} from '../../service/book-metadata-manage.service';
import {catchError, debounceTime, filter, map, shareReplay, switchMap, take, takeUntil, tap} from 'rxjs/operators';
import {BehaviorSubject, combineLatest, interval, Observable, of, Subject, Subscription} from 'rxjs';
import {DynamicDialogRef} from 'primeng/dynamicdialog';
import {Library} from '../../model/library.model';
import {Shelf} from '../../model/shelf.model';
import {SortDirection, SortOption} from '../../model/sort.model';
import {BookState} from '../../model/state/book-state.model';
import {Book} from '../../model/book.model';
import {LibraryShelfMenuService} from '../../service/library-shelf-menu.service';
import {BookTableComponent, TableViewportMetrics} from './book-table/book-table.component';
import {animate, style, transition, trigger} from '@angular/animations';
import {Button} from 'primeng/button';
import {AsyncPipe, NgClass, NgTemplateOutlet} from '@angular/common';
import {BookCardComponent} from './book-card/book-card.component';
import {Menu} from 'primeng/menu';
import {InputText} from 'primeng/inputtext';
import {FormsModule} from '@angular/forms';
import {BookFilterComponent} from './book-filter/book-filter.component';
import {Tooltip} from 'primeng/tooltip';
import {BookFilterMode, DEFAULT_VISIBLE_SORT_FIELDS, EntityViewPreferences, SortCriterion, UserService} from '../../../settings/user-management/user.service';
import {SeriesCollapseFilter} from './filters/SeriesCollapseFilter';
import {normalizeFilterMode, SideBarFilter} from './filters/sidebar-filter';
import {HeaderFilter} from './filters/HeaderFilter';
import {CoverScalePreferenceService} from './cover-scale-preference.service';
import {BookSorter} from './sorting/BookSorter';
import {BookDialogHelperService} from './book-dialog-helper.service';
import {Checkbox} from 'primeng/checkbox';
import {Popover} from 'primeng/popover';
import {Slider} from 'primeng/slider';
import {Divider} from 'primeng/divider';
import {MultiSelect} from 'primeng/multiselect';
import {TableColumnPreferenceService} from './table-column-preference.service';
import {TieredMenu} from 'primeng/tieredmenu';
import {BookMenuService} from '../../service/book-menu.service';
import {MagicShelf} from '../../../magic-shelf/service/magic-shelf.service';
import {SidebarFilterTogglePrefService} from './filters/sidebar-filter-toggle-pref.service';
import {MetadataRefreshType} from '../../../metadata/model/request/metadata-refresh-type.enum';
import {TaskHelperService} from '../../../settings/task-management/task-helper.service';
import {
  MetadataTaskService,
  PendingMetadataReviewTask,
  StagingTriage,
  StagingTriageMode
} from '../../service/metadata-task';
import {DialogLauncherService} from '../../../../shared/services/dialog-launcher.service';
import {MetadataProgressService} from '../../../../shared/service/metadata-progress.service';
import {FilterLabelHelper} from './filter-label.helper';
import {LocalStorageService} from '../../../../shared/service/local-storage.service';
import {WriteProgressService} from '../../../../shared/service/write-progress.service';
import {FailureNotificationService} from '../../../../shared/service/failure-notification.service';
import {BookNavigationService} from '../../service/book-navigation.service';
import {BookCardOverlayPreferenceService} from './book-card-overlay-preference.service';
import {BookSelectionService, CheckboxClickEvent} from './book-selection.service';
import {BookBrowserQueryParamsService, QUERY_PARAMS, VIEW_MODES} from './book-browser-query-params.service';
import {BookBrowserEntityService, EntityInfo} from './book-browser-entity.service';
import {BookFilterOrchestrationService} from './book-filter-orchestration.service';
import {BookBrowserScrollService} from './book-browser-scroll.service';
import {AppSettingsService} from '../../../../shared/service/app-settings.service';
import {MultiSortPopoverComponent} from './sorting/multi-sort-popover/multi-sort-popover.component';
import {SortService} from '../../service/sort.service';
import {injectVirtualGrid} from '../../../../shared/util/virtual-grid.util';
import {PagedGridPilotService} from '../../service/paged-grid-pilot.service';
import {PagedBookBrowserStateService} from '../../service/paged-book-browser-state.service';
import {BookSocketService} from '../../service/book-socket.service';

import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {ResizableDividerDirective} from '../../../../shared/directives/resizable-divider.directive';
import {CoverPreviewComponent} from '../../../../shared/components/cover-preview/cover-preview.component';
import {LoadingIndicatorComponent} from '../../../../shared/components/loading-indicator/loading-indicator.component';
import {UrlHelperService} from '../../../../shared/service/url-helper.service';
import {DirectoryFilterService} from '../../service/directory-filter.service';
import {DirectoryPanelService} from '../../service/directory-panel.service';
import {DirectoryMobilePanelComponent} from '../directory-mobile-panel/directory-mobile-panel.component';
import {MediaTypePreferencesService} from '../../service/media-type-preferences.service';
import {MobileBackHandle, MobileBackNavigationService} from '../../../../shared/service/mobile-back-navigation.service';
import {isDirectoryScopeActive} from './book-browser-directory-scope.util';
import {MobileUxService} from '../../../../core/services/mobile-ux.service';
import {getDirectoryScopedSortCriteria} from './book-browser-directory-scope.util';
import {
  buildGridViewportContext,
  GridViewportContext,
  shouldResetGridViewport,
} from './book-browser-grid-reset.util';
import {filterBooksByStagingTriage, findReviewTaskIdForBook} from './staging-triage.filter';
import { ProgressBar } from 'primeng/progressbar';
import { AiSearchDialogService } from '../ai-search-dialog/ai-search-dialog.component';
import { PagedBookBrowserEntity } from '../../model/state/paged-book-browser-state.model';
import {
  blurSearchOverlayInput,
  focusSearchOverlayInput,
  SearchOverlayFocusHandle
} from '../../../../shared/util/search-overlay-focus.util';

export enum EntityType {
  LIBRARY = 'Library',
  SHELF = 'Shelf',
  MAGIC_SHELF = 'Magic Shelf',
  ALL_BOOKS = 'All Books',
  NOT_SHELFED = 'Not Shelfed',
  STAGING = 'Staging',
}

@Component({
  selector: 'app-book-browser',
  standalone: true,
  templateUrl: './book-browser.component.html',
  styleUrls: ['./book-browser.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    Button, BookCardComponent, AsyncPipe, NgTemplateOutlet, Menu, InputText, FormsModule,
    BookTableComponent, BookFilterComponent, Tooltip, NgClass, Popover,
    Checkbox, Slider, Divider, MultiSelect, TieredMenu, MultiSortPopoverComponent, TranslocoDirective,
    ResizableDividerDirective, CoverPreviewComponent, LoadingIndicatorComponent, ProgressBar, DirectoryMobilePanelComponent,
  ],
  providers: [SeriesCollapseFilter],
  animations: [
    trigger('slideInOut', [
      transition(':enter', [
        style({transform: 'translateY(100%)'}),
        animate('0.1s ease-in', style({transform: 'translateY(0)'}))
      ]),
      transition(':leave', [
        style({transform: 'translateY(0)'}),
        animate('0.1s ease-out', style({transform: 'translateY(100%)'}))
      ])
    ])
  ]
})
export class BookBrowserComponent implements OnInit, AfterViewInit, OnDestroy {

  protected userService = inject(UserService);
  protected coverScalePreferenceService = inject(CoverScalePreferenceService);
  protected columnPreferenceService = inject(TableColumnPreferenceService);
  protected sidebarFilterTogglePrefService = inject(SidebarFilterTogglePrefService);
  protected seriesCollapseFilter = inject(SeriesCollapseFilter);
  protected confirmationService = inject(ConfirmationService);
  protected taskHelperService = inject(TaskHelperService);
  private metadataTaskService = inject(MetadataTaskService);
  private dialogLauncherService = inject(DialogLauncherService);
  private metadataProgressService = inject(MetadataProgressService);
  protected bookCardOverlayPreferenceService = inject(BookCardOverlayPreferenceService);
  protected bookSelectionService = inject(BookSelectionService);
  protected appSettingsService = inject(AppSettingsService);
  protected aiSearchDialogService = inject(AiSearchDialogService);
  private ngZone = inject(NgZone);

  private cdr = inject(ChangeDetectorRef);
  private activatedRoute = inject(ActivatedRoute);
  private router = inject(Router);
  private messageService = inject(MessageService);
  private bookService = inject(BookService);
  private bookMetadataManageService = inject(BookMetadataManageService);
  private dialogHelperService = inject(BookDialogHelperService);
  private bookMenuService = inject(BookMenuService);
  private libraryShelfMenuService = inject(LibraryShelfMenuService);
  private pageTitle = inject(PageTitleService);
  private writeProgressService = inject(WriteProgressService);
  private failureNotifications = inject(FailureNotificationService);
  private bookNavigationService = inject(BookNavigationService);
  private queryParamsService = inject(BookBrowserQueryParamsService);
  private entityService = inject(BookBrowserEntityService);
  private filterOrchestrationService = inject(BookFilterOrchestrationService);
  private localStorageService = inject(LocalStorageService);
  private scrollService = inject(BookBrowserScrollService);
  private readonly aiSearchScanProgressService = inject(AiSearchScanProgressService);
  private readonly t = inject(TranslocoService);
  private urlHelper = inject(UrlHelperService);
  private directoryFilterService = inject(DirectoryFilterService);
  readonly dirPanelService = inject(DirectoryPanelService);
  private mediaTypePreferences = inject(MediaTypePreferencesService);
  private mobileBackNavigation = inject(MobileBackNavigationService);
  protected mobileUx = inject(MobileUxService);
  private pagedGridPilotService = inject(PagedGridPilotService);
  private pagedBookBrowserStateService = inject(PagedBookBrowserStateService);
  private bookSocketService = inject(BookSocketService);
  readonly pagedGridPilotStatus$ = this.pagedGridPilotService.status$;

  bookState$: Observable<BookState> | undefined;
  entity$: Observable<Library | Shelf | MagicShelf | null> | undefined;
  entityType$: Observable<EntityType> | undefined;
  private entityRouteInfo$!: Observable<EntityInfo>;
  searchTerm$ = new BehaviorSubject<string>('');
  selectedFilter = new BehaviorSubject<Record<string, string[]> | null>(null);
  selectedFilterMode = new BehaviorSubject<BookFilterMode>('and');
  protected resetFilterSubject = new Subject<void>();

  parsedFilters: Record<string, string[]> = {};
  entity: Library | Shelf | MagicShelf | null = null;
  entityType: EntityType | undefined;
  bookTitle = '';
  entityOptions: MenuItem[] | undefined;
  isDrawerVisible = false;
  dynamicDialogRef: DynamicDialogRef | undefined | null;
  EntityType = EntityType;
  currentFilterLabel: string | null = null;
  rawFilterParamFromUrl: string | null = null;
  hasSearchTerm = false;
  activeDirFilterPath: string | null = null;
  visibleColumns: { field: string; header: string }[] = [];
  entityViewPreferences: EntityViewPreferences | undefined;
  currentViewMode: string | undefined;
  lastAppliedSortCriteria: SortOption[] = [];
  private baseSortCriteria: SortOption[] = [];
  private hasExplicitSortQuery = false;
  visibleSortOptions: SortOption[] = [];
  showFilter = false;
  screenWidth = typeof window !== 'undefined' ? window.innerWidth : 1024;
  mobileColumnCount = 3;
  mobileTitleRows = 2;
  mobileScaleFactor = 1.0;
  desktopTitleRows = 2;
  selectedCount = 0;
  selectedBookIds = new Set<number>();
  hiddenSelectedCount = 0;
  private visibleBookIds = new Set<number>();
  allowFileDeletion = false;
  aiSearchEnabled = false;
  isbnDiscoveryEnabled = false;
  stagingTriageMode: StagingTriageMode = 'staging';
  stagingInboxCount = 0;
  stagingCompletedCount = 0;
  pendingReviewCount = 0;
  private stagingInboxBookIds = new Set<number>();
  private stagingCompletedBookIds = new Set<number>();
  private pendingReviewBookIds = new Set<number>();
  private pendingReviewPrimaryTaskId: string | null = null;
  private pendingReviewTasks: PendingMetadataReviewTask[] = [];
  private lastStagingTriageSignature = '';
  private readonly stagingTriageVersion$ = new BehaviorSubject<number>(0);
  aiSearchStatus: 'READY' | 'STARTING' | 'ERROR' = 'READY';
  isAiSearchActive = false;
  isAiSearchError = false;
  isBatchEmbedding = false;
  aiSearchDialogVisible = false;
  private aiSearchPollingSub?: Subscription;
  isSelectionActionPanelOpen = false;
  isDisplaySettingsOpen = false;
  isSortPopoverOpen = false;
  isColumnPopoverOpen = false;

  // Cover preview state
  selectedCoverUrl: string | null = null;
  selectedBookTitle = '';
  private hoverPreviewTimer: ReturnType<typeof setTimeout> | null = null;
  private pendingPreviewBookId: number | null = null;

  private readonly MOBILE_BREAKPOINT = 767;
  private readonly CARD_ASPECT_RATIO = 7 / 5;
  private readonly MOBILE_GAP = 8;
  private readonly MOBILE_PADDING = 48;
  private readonly MOBILE_TITLE_BAR_HEIGHT = 32;
  private readonly MOBILE_COLUMNS_STORAGE_KEY = 'mobileColumnsPreference';
  private readonly MOBILE_TITLE_ROWS_STORAGE_KEY = 'mobileTitleRowsPreference';
  private readonly MOBILE_SCALE_STORAGE_KEY = 'mobileScalePreference';
  private readonly MOBILE_SCALE_MIN = 0.5;
  private readonly MOBILE_SCALE_MAX = 1.5;
  private readonly MOBILE_SCALE_STEP = 0.05;
  private readonly DESKTOP_TITLE_ROWS_STORAGE_KEY = 'desktopTitleRowsPreference';
  private readonly SHOW_SUBTITLES_STORAGE_KEY = 'bookBrowserShowSubtitlesPreference';
  private readonly COVER_PREVIEW_HOVER_DELAY_MS = 120;

  private settingFiltersFromUrl = false;
  private hasInitializedFilterMode = false;
  private isRouteAttached = true;
  private destroy$ = new Subject<void>();
  private lastEntityKey: string | null = null;
  private previousSelectedCount = 0;
  protected metadataMenuItems: MenuItem[] | undefined;
  protected moreActionsMenuItems: MenuItem[] | undefined;
  protected aiSearchMenuItems: MenuItem[] | undefined;

  mediaTypeActionsMenuItems: MenuItem[] = [];
  showSubtitles = false;
  protected gridRenderVersion = 0;

  private sideBarFilter = new SideBarFilter(this.selectedFilter, this.selectedFilterMode);

  /** Search-filtered books observable for the filter sidebar.
   *  Derived from the global book state + search term so the sidebar
   *  always reflects the currently visible (search-filtered) book set. */
  searchFilteredForSidebar$: Observable<Book[]> = of([]);

  useDistractionLoadingScreen = false;
  distractionCoverUrl: string | null = null;
  private isFetchingDistractionCover = false;
  private headerFilter = new HeaderFilter(this.searchTerm$);
  protected bookSorter = new BookSorter(
    sortCriteria => this.onMultiSortChange(sortCriteria),
    this.t
  );
  private sortService = inject(SortService);

  private bookStateSubscription: Subscription | undefined;
  private pendingGridViewportReset = false;
  private pendingScrollRestorePosition: number | null = null;
  private lastGridViewportContext: GridViewportContext | null = null;

  @ViewChild(BookTableComponent)
  bookTableComponent!: BookTableComponent;
  @ViewChildren(BookFilterComponent)
  bookFilterComponents!: QueryList<BookFilterComponent>;
  private _scrollContainer?: ElementRef<HTMLElement>;
  @ViewChild('scrollContainer')
  set scrollContainer(el: ElementRef<HTMLElement> | undefined) {
    this._scrollContainer = el;
    this.updateVirtualGridDomBindings();
  }
  get scrollContainer(): ElementRef<HTMLElement> | undefined {
    return this._scrollContainer;
  }

  private _gridContainer?: ElementRef<HTMLElement>;
  @ViewChild('gridContainer')
  set gridContainer(el: ElementRef<HTMLElement> | undefined) {
    this._gridContainer = el;
    this.updateVirtualGridDomBindings();
  }
  get gridContainer(): ElementRef<HTMLElement> | undefined {
    return this._gridContainer;
  }
  @ViewChild('mobileRightSidebarPop')
  mobileRightSidebarPop: Popover | undefined;
  @ViewChild('sortPopover')
  sortPopover: Popover | undefined;
  @ViewChild('seriesCollapseOverlay')
  seriesCollapseOverlay: Popover | undefined;
  @ViewChild('columnPopover')
  columnPopover: Popover | undefined;
  @ViewChild('mobileDirPop')
  mobileDirPop: Popover | undefined;
  @ViewChild('mobileSearchInput')
  mobileSearchInput?: ElementRef<HTMLInputElement>;
  isMobileRightSidebarOpen = false;
  private mobileRightSidebarBackHandle: MobileBackHandle | null = null;
  private sortPopoverBackHandle: MobileBackHandle | null = null;
  private displaySettingsBackHandle: MobileBackHandle | null = null;
  private columnPopoverBackHandle: MobileBackHandle | null = null;
  private mobileDirectoryBackHandle: MobileBackHandle | null = null;
  private mobileSearchFocusHandle: SearchOverlayFocusHandle | null = null;

  mobileDirectoryPopoverOpen = false;

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

  onMobileSearchPopoverShow(): void {
    this.mobileSearchFocusHandle?.clear();
    this.mobileSearchFocusHandle = focusSearchOverlayInput(
      () => this.mobileSearchInput?.nativeElement ?? null
    );
  }

  onMobileSearchPopoverHide(): void {
    this.mobileSearchFocusHandle?.clear();
    this.mobileSearchFocusHandle = null;
    blurSearchOverlayInput(() => this.mobileSearchInput?.nativeElement ?? null);
  }

  get isMobile(): boolean {
    return this.mobileUx.isPhone;
  }

  get showFilterAsPopover(): boolean {
    return this.mobileUx.isMobileOrTablet;
  }

  get mobileCardSize(): { width: number; height: number } {
    const columns = this.mobileColumnCount;
    const totalGaps = (columns - 1) * this.MOBILE_GAP;
    const availableWidth = this.screenWidth - totalGaps - this.MOBILE_PADDING;
    const baseCardWidth = Math.floor(availableWidth / columns);
    const cardWidth = Math.round(baseCardWidth * this.mobileScaleFactor);
    const coverHeight = this.isAudiobookOnlyLibrary ? cardWidth : Math.floor(cardWidth * this.CARD_ASPECT_RATIO);
    const cardHeight = coverHeight + this.getMobileTitleBarHeight();
    return {width: cardWidth, height: cardHeight};
  }

  get selectedBooks(): Set<number> {
    return this.selectedBookIds;
  }

  get hasHiddenSelections(): boolean {
    return this.hiddenSelectedCount > 0;
  }

  get currentCardSize() {
    if (this.isMobile) {
      return this.mobileCardSize;
    }
    const base = this.coverScalePreferenceService.currentCardSize;
    if (this.isAudiobookOnlyLibrary) {
      const squareSide = Math.round(base.width * 1.1);
      return {
        width: squareSide,
        height: squareSide + this.coverScalePreferenceService.TITLE_BAR_HEIGHT
      };
    }
    return base;
  }

  get gridColumnMinWidth(): string {
    if (this.isMobile) {
      return `${this.mobileCardSize.width}px`;
    }
    if (this.isAudiobookOnlyLibrary) {
      return `${this.currentCardSize.width}px`;
    }
    return this.coverScalePreferenceService.gridColumnMinWidth;
  }

  getCardHeight(_book: Book): number {
    if (this.isMobile) {
      return this.mobileCardSize.height;
    }
    const extraRows = (this.desktopTitleRows - 1) * this.coverScalePreferenceService.TITLE_ROW_EXTRA_HEIGHT;
    if (this.isAudiobookOnlyLibrary) {
      return this.currentCardSize.height + extraRows;
    }
    return this.coverScalePreferenceService.getCardHeightForTitleRows(this.desktopTitleRows);
  }

  get viewIcon(): string {
    return this.currentViewMode === VIEW_MODES.GRID ? 'pi pi-objects-column' : 'pi pi-table';
  }

  get isFilterActive(): boolean {
    return !!this.selectedFilter.value && Object.keys(this.selectedFilter.value).length > 0;
  }

  get isWaitingForDirectorySelection(): boolean {
    return this.canShowDirectoryExplorer && this.dirPanelService.isVisible && this.activeDirFilterPath === null;
  }

  get isDirectoryScopedView(): boolean {
    return isDirectoryScopeActive(this.activeDirFilterPath);
  }

  get shouldShowDirectoryReset(): boolean {
    return this.canShowDirectoryExplorer && isDirectoryScopeActive(this.activeDirFilterPath);
  }

  get canShowDirectoryExplorer(): boolean {
    if (this.isMobile || !this.entityType) {
      return false;
    }

    return this.entityType === EntityType.ALL_BOOKS
      || this.entityType === EntityType.LIBRARY
      || this.entityType === EntityType.SHELF;
  }

  get canShowDirectoryExplorerOnMobile(): boolean {
    if (!this.entityType) {
      return false;
    }

    return this.entityType === EntityType.ALL_BOOKS
      || this.entityType === EntityType.LIBRARY
      || this.entityType === EntityType.SHELF;
  }

  get isPagedPilotActive(): boolean {
    return this.pagedGridPilotService.isPagedActive();
  }

  get computedFilterLabel(): string {
    const filters = this.selectedFilter.value;

    if (!filters || Object.keys(filters).length === 0) {
      return this.t.translate('book.browser.labels.allBooks');
    }

    const filterEntries = Object.entries(filters);

    if (filterEntries.length === 1) {
      const [filterType, values] = filterEntries[0];
      if ((filterType === 'customMediaType' || filterType === 'customBookType') && values.length === 1) {
        if (values[0] === 'PHYSICAL') {
          return this.t.translate('layout.menu.physicalBooks');
        }
        return `Media: ${values[0]}`;
      }
      const filterName = FilterLabelHelper.getFilterTypeName(filterType);

      if (values.length === 1) {
        const displayValue = FilterLabelHelper.getFilterDisplayValue(filterType, values[0]);
        return `${filterName}: ${displayValue}`;
      }

      return `${filterName} (${values.length})`;
    }

    const filterSummary = filterEntries
      .map(([type, values]) => `${FilterLabelHelper.getFilterTypeName(type)} (${values.length})`)
      .join(', ');

    return filterSummary.length > 50
      ? this.t.translate('book.browser.labels.activeFilters', {count: filterEntries.length})
      : filterSummary;
  }

  get activeMediaTypeFilter(): string | null {
    const filters = this.selectedFilter.value;
    if (!filters) {
      return null;
    }

    const mediaTypes = filters['customMediaType'] ?? filters['customBookType'];
    if (!mediaTypes || mediaTypes.length !== 1) {
      return null;
    }

    const mediaType = mediaTypes[0] ?? null;
    return mediaType === 'PHYSICAL' ? null : mediaType;
  }

  openMediaTypeActionsMenu(event: Event, menu: Menu): void {
    const mediaType = this.activeMediaTypeFilter;
    if (!mediaType) {
      this.mediaTypeActionsMenuItems = [];
      return;
    }

    this.mediaTypeActionsMenuItems = [
      {
        label: 'Edit Media Type',
        icon: 'pi pi-pencil',
        command: () => this.openMediaTypeManagerDialog()
      },
      {
        label: 'Delete Media Type',
        icon: 'pi pi-trash',
        command: () => this.openMediaTypeDeleteDialog(mediaType)
      }
    ];

    menu.toggle(event);
  }

  get isAudiobookOnlyLibrary(): boolean {
    if (!this.entity || this.entityType !== EntityType.LIBRARY) return false;
    const library = this.entity as Library;
    return !!library.allowedFormats && library.allowedFormats.length === 1 && library.allowedFormats[0] === 'AUDIOBOOK';
  }

  get seriesViewEnabled(): boolean {
    return Boolean(this.userService.getCurrentUser()?.userSettings?.enableSeriesView);
  }

  get hasMetadataMenuItems(): boolean {
    return (this.metadataMenuItems?.length ?? 0) > 0;
  }

  get hasMoreActionsItems(): boolean {
    return (this.moreActionsMenuItems?.length ?? 0) > 0;
  }

  // Handle book card hover for cover preview
  onBookClicked(book: Book): void {
    this.clearHoverPreviewTimer();
    this.pendingPreviewBookId = book.id;

    this.ngZone.runOutsideAngular(() => {
      this.hoverPreviewTimer = setTimeout(() => {
        this.ngZone.run(() => {
          if (this.pendingPreviewBookId !== book.id) {
            return;
          }
          const isAudiobook = book.primaryFile?.bookType === 'AUDIOBOOK';
          this.selectedCoverUrl = isAudiobook
            ? this.urlHelper.getAudiobookCoverUrl(book.id, book.metadata?.audiobookCoverUpdatedOn)
            : this.urlHelper.getCoverUrl(book.id, book.metadata?.coverUpdatedOn);
          this.selectedBookTitle = this.getDisplayTitle(book);
          this.pendingPreviewBookId = null;
          this.hoverPreviewTimer = null;
          this.cdr.detectChanges();
        });
      }, this.COVER_PREVIEW_HOVER_DELAY_MS);
    });
  }

  onBookHoverEnded(bookId: number): void {
    if (this.pendingPreviewBookId === bookId) {
      this.pendingPreviewBookId = null;
      this.clearHoverPreviewTimer();
    }
  }

  private readonly gridItemCountSig = signal(0);
  private readonly cardWidthSig = signal(this.currentCardSize.width);
  private readonly cardHeightSig = signal(this.getUniformCardHeight());
  private readonly gapSig = signal(this.isMobile ? this.MOBILE_GAP : 20.8);

  readonly virtualGrid = injectVirtualGrid(() => ({
    itemCount: this.gridItemCountSig(),
    cardWidth: this.cardWidthSig(),
    cardHeight: this.cardHeightSig(),
    gap: this.gapSig(),
    overscan: 5,
  }));

  private resizeSub?: Subscription;


  private toastError(summary: string, detail: string): void {
    this.failureNotifications.reportSafe(summary, detail);
    this.messageService.add({severity: 'error', summary, detail});
  }

  ngOnInit(): void {
    this.pageTitle.setPageTitle('');
    this.coverScalePreferenceService.scaleChange$.pipe(debounceTime(1000), takeUntil(this.destroy$)).subscribe();
    this.loadMobileColumnsPreference();
    this.loadMobileScalePreference();
    this.loadTitleRowsPreference();
    this.loadSubtitlePreference();

    this.resizeSub = this.mobileUx.screenWidth$.subscribe(width => {
      this.screenWidth = width;
      this.cardWidthSig.set(this.currentCardSize.width);
      this.cardHeightSig.set(this.getUniformCardHeight());
      this.gapSig.set(this.isMobile ? this.MOBILE_GAP : 20.8);
      this.updateVirtualGridDomBindings();
    });

    this.mobileUx.breakpoint$.pipe(takeUntil(this.destroy$)).subscribe(() => {
      this.cardWidthSig.set(this.currentCardSize.width);
      this.cardHeightSig.set(this.getUniformCardHeight());
      this.gapSig.set(this.isMobile ? this.MOBILE_GAP : 20.8);
      this.updateVirtualGridDomBindings();
      this.cdr.markForCheck();
    });

    this.dirPanelService.visible$
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        requestAnimationFrame(() => this.updateVirtualGridDomBindings());
      });

    this.directoryFilterService.filter$.pipe(takeUntil(this.destroy$)).subscribe(() => {
      this.syncActiveDirectoryFilter();
      this.applyEffectiveSortCriteria();
    });

    this.initializeEntityRouting();
    this.setupRouteChangeHandlers();
    this.setupAppSettingsSubscription();
    this.setupUserStateSubscription();
    this.setupQueryParamSubscription();
    this.setupFilterToggleSubscription();
    this.setupSearchTermSubscription();
    this.setupScrollPositionTracking();
    this.setupSelectionSubscription();
    this.setupPendingReviewSubscription();
  }

  ngAfterViewInit(): void {
    this.sidebarFilterTogglePrefService.mobileFilterToggle$
      .pipe(takeUntil(this.destroy$))
      .subscribe((event: MouseEvent) => {
        if (!this.showFilterAsPopover) {
          return;
        }
        this.toggleMobileRightSidebar(event);
      });

    this.settingFiltersFromUrl = true;
    this.bookFilterComponents?.forEach(comp => {
      comp.setFilters?.(this.parsedFilters);
      comp.onFiltersChanged?.();
    });
    this.settingFiltersFromUrl = false;

    const key = this.getScrollPositionKey();
    const savedPosition = this.scrollService.getPosition(key);
    if (savedPosition !== undefined) {
      this.pendingScrollRestorePosition = savedPosition;
    }

    this.updateVirtualGridDomBindings();
  }

  ngOnDestroy(): void {
    this.mobileSearchFocusHandle?.clear();
    this.mobileSearchFocusHandle = null;
    this.resizeSub?.unsubscribe();
    this.forceCloseMobileRightSidebar(false);
    this.forceCloseHeaderPopovers(false);
    this.clearHoverPreviewTimer();
    this.pagedGridPilotService.resetActiveQuery();
    this.stopAiStatusPolling();
    this.destroy$.next();
    this.destroy$.complete();
  }

  onRouteDetached(): void {
    this.isRouteAttached = false;
    this.bookStateSubscription?.unsubscribe();
    this.bookStateSubscription = undefined;
  }

  onRouteReattached(): void {
    this.isRouteAttached = true;
    this.bookFilterComponents?.forEach(component => component.refreshAfterRouteAttach());

    if (this.entityType === EntityType.ALL_BOOKS || this.entityType === EntityType.NOT_SHELFED || this.entityType === EntityType.STAGING) {
      const currentPath = this.activatedRoute.snapshot.routeConfig?.path;
      const fallbackTitle = currentPath === 'not-shelfed'
        ? this.t.translate('book.browser.labels.unshelvedBooks')
        : currentPath === 'staging'
          ? this.t.translate('book.browser.labels.stagingBooks')
          : this.t.translate('book.browser.labels.allBooks');
      this.pageTitle.setPageTitle(this.currentFilterLabel || fallbackTitle);
    } else if (this.entity?.name) {
      this.pageTitle.setPageTitle(this.entity.name);
    }

    if (this.entityType !== EntityType.ALL_BOOKS) {
      this.pagedBookBrowserStateService.invalidateEntity('ALL_BOOKS');
    }

    const effectiveSortCriteria = this.getEffectiveSortCriteria(
      this.lastAppliedSortCriteria.length > 0
        ? this.lastAppliedSortCriteria
        : this.bookSorter.selectedSortCriteria
    );
    this.lastAppliedSortCriteria = [...effectiveSortCriteria];
    this.applySortCriteria(effectiveSortCriteria);

    this.syncSelectionState(this.bookSelectionService.selectedBooks);
    this.bookTableComponent?.refreshSelectionFromInputs();
    this.restoreSavedScrollPosition();

    this.cdr.detectChanges();
  }

  private clearHoverPreviewTimer(): void {
    if (!this.hoverPreviewTimer) {
      return;
    }
    clearTimeout(this.hoverPreviewTimer);
    this.hoverPreviewTimer = null;
  }

  openAiSearch(): void {
    if (this.selectedCount > 0) {
      this.aiSearchDialogService.open(Array.from(this.selectedBooks));
    } else {
      this.aiSearchDialogService.open();
    }
  }

  private startAiStatusPolling(): void {
    if (this.aiSearchPollingSub) return;
    
    const fetchStatus = () => {
      this.appSettingsService.getAiSearchServiceStatus().pipe(
        catchError(() => of({ status: 'ERROR' }))
      ).subscribe((res) => {
        if (res && res.status) {
          this.aiSearchStatus = res.status as 'READY' | 'STARTING' | 'ERROR';
          this.cdr.markForCheck();
        }
      });
    };
    
    fetchStatus();
    this.aiSearchPollingSub = interval(5000).subscribe(() => fetchStatus());
  }

  private stopAiStatusPolling(): void {
    if (this.aiSearchPollingSub) {
      this.aiSearchPollingSub.unsubscribe();
      this.aiSearchPollingSub = undefined;
    }
  }

  private getScrollPositionKey(): string {
    const path = this.activatedRoute.snapshot.routeConfig?.path ?? '';
    return this.scrollService.createKey(path, this.activatedRoute.snapshot.params);
  }

  private setupScrollPositionTracking(): void {
    this.router.events.pipe(
      filter(event => event instanceof NavigationStart),
      takeUntil(this.destroy$)
    ).subscribe((event) => {
      this.handleNavigationStart(event as NavigationStart);
      this.saveScrollPosition();
    });
  }

  private handleNavigationStart(event: NavigationStart): void {
    if (!this.isMobile) {
      return;
    }

    if (event.navigationTrigger !== 'popstate' && this.isMobileRightSidebarOpen) {
      this.forceCloseMobileRightSidebar(false);
    }
    if (event.navigationTrigger !== 'popstate' &&
      (this.isSortPopoverOpen || this.isDisplaySettingsOpen || this.isColumnPopoverOpen)) {
      this.forceCloseHeaderPopovers(false);
    }
  }

  private saveScrollPosition(): void {
    const el = this.scrollContainer?.nativeElement;
    if (!el || !el.isConnected) return;
    const key = this.getScrollPositionKey();
    this.scrollService.savePosition(key, el.scrollTop ?? 0);
  }

  private restoreSavedScrollPosition(): void {
    const savedPosition = this.scrollService.getPosition(this.getScrollPositionKey());
    if (savedPosition === undefined) {
      return;
    }

    requestAnimationFrame(() => {
      const scrollEl = this.scrollContainer?.nativeElement;
      if (!scrollEl) {
        return;
      }

      scrollEl.scrollTop = savedPosition;
      scrollEl.dispatchEvent(new Event('scroll'));
      this.cdr.detectChanges();
    });
  }

  private initializeEntityRouting(): void {
    const currentPath = this.activatedRoute.snapshot.routeConfig?.path;

    if (currentPath === 'all-books' || currentPath === 'not-shelfed' || currentPath === 'staging') {
      const entityType = currentPath === 'all-books'
        ? EntityType.ALL_BOOKS
        : currentPath === 'not-shelfed'
          ? EntityType.NOT_SHELFED
          : EntityType.STAGING;
      this.entityType = entityType;
      this.entityType$ = of(entityType);
      this.entityRouteInfo$ = of({entityId: NaN, entityType});
      this.entity$ = of(null);
      this.seriesCollapseFilter.setContext(null, null);
      this.bookCardOverlayPreferenceService.setContext(null, null);
      const pageTitleKey = currentPath === 'all-books'
        ? 'book.browser.labels.allBooks'
        : currentPath === 'not-shelfed'
          ? 'book.browser.labels.unshelvedBooks'
          : 'book.browser.labels.stagingBooks';
      this.pageTitle.setPageTitle(this.t.translate(pageTitleKey));
      this.handleEntityLoaded(null);
    } else {
      const routeEntityInfo$ = this.entityService.getEntityInfoFromRoute(this.activatedRoute);
      this.entityRouteInfo$ = routeEntityInfo$;
      this.entityType$ = routeEntityInfo$.pipe(map(info => {
        this.entityType = info.entityType;
        return info.entityType;
      }));
      this.entity$ = routeEntityInfo$.pipe(
        switchMap(({entityId, entityType}) => this.entityService.fetchEntity(entityId, entityType))
      );
      this.entity$.pipe(takeUntil(this.destroy$)).subscribe(entity => this.handleEntityLoaded(entity));
    }
  }

  private handleEntityLoaded(entity: Library | Shelf | MagicShelf | null): void {
    if (entity) {
      this.pageTitle.setPageTitle(entity.name);
    }
    this.entity = entity ?? null;
    this.updateSeriesCollapseContext();
    this.updateEntityOptions();
  }

  private updateEntityOptions(): void {
    const baseItems = this.entity
      ? this.entityService.isLibrary(this.entity)
        ? this.libraryShelfMenuService.initializeLibraryMenuItems(this.entity)
        : this.entityService.isMagicShelf(this.entity)
          ? this.libraryShelfMenuService.initializeMagicShelfMenuItems(this.entity)
          : this.libraryShelfMenuService.initializeShelfMenuItems(this.entity)
      : [];

    const user = this.userService.getCurrentUser();
    const canScan = user?.permissions?.admin || user?.permissions?.canManageLibrary;
    const canConfigure = user?.permissions?.admin || user?.permissions?.canManageGlobalPreferences;

    if (this.aiSearchEnabled && (canScan || canConfigure) && this.entityType !== EntityType.STAGING) {
      const aiItems: MenuItem[] = [];
      if (canScan) {
        aiItems.push({
          label: this.t.translate('shared.aiSearchProgress.embedMarkedBtn') || 'Embed Marked Books',
          icon: 'pi pi-bolt',
          command: () => this.embedAllMarkedNow()
        });
      }
      if (canConfigure) {
        aiItems.push({
          label: this.t.translate('shared.aiSearchProgress.configureAiBtn') || 'Configure AI Settings',
          icon: 'pi pi-cog',
          command: () => this.router.navigate(['/settings/ai'])
        });
      }

      if (aiItems.length > 0) {
        if (baseItems.length > 0) {
          baseItems.push({ separator: true });
        }
        baseItems.push(...aiItems);
      }
    }

    this.entityOptions = this.entityType === EntityType.STAGING ? [] : baseItems;
    this.cdr.markForCheck();
  }

  private setupRouteChangeHandlers(): void {
    this.activatedRoute.paramMap.pipe(takeUntil(this.destroy$)).subscribe(() => {
      this.searchTerm$.next('');
      this.bookTitle = '';
      this.bookSelectionService.deselectAll();
      this.clearFilter();
      this.syncActiveDirectoryFilter();
    });
  }

  private setupUserStateSubscription(): void {
    this.userService.userState$.pipe(
      filter(u => !!u?.user && u.loaded),
      takeUntil(this.destroy$)
    ).subscribe(userState => {
        this.useDistractionLoadingScreen = userState.user?.userSettings?.useDistractionLoadingScreen ?? false;
        this.rebuildMetadataMenuItems(userState.user);
        this.updateEntityOptions();
      });

    this.moreActionsMenuItems = this.bookMenuService.getMoreActionsMenu(this.selectedBooks, this.user());
    this.aiSearchMenuItems = [
      {
        label: 'AI-Search Selected',
        icon: 'pi pi-sparkles',
        command: () => this.openAiSearch()
      },
      {
        separator: true
      },
      {
        label: 'Embed Now',
        icon: 'pi pi-bolt',
        command: () => this.embedSelectedNow()
      },
      {
        label: 'Mark for Embedding',
        icon: 'pi pi-bookmark',
        command: () => this.markSelectedForAiSearch()
      },
      {
        separator: true
      },
      {
        label: 'Delete Embeddings',
        icon: 'pi pi-trash',
        command: () => this.deleteSelectedEmbeddings()
      }
    ];
  }

  private rebuildMetadataMenuItems(user = this.user()): void {
    this.metadataMenuItems = this.bookMenuService.getMetadataMenuItems(
      () => this.autoFetchMetadata(),
      () => this.fetchMetadata(),
      () => this.bulkEditMetadata(),
      () => this.multiBookEditMetadata(),
      () => this.restoreTitlesFromFilenamesForSelected(),
      () => this.regenerateCoversForSelected(),
      () => this.generateCustomCoversForSelected(),
      user,
      () => this.isbnDiscoveryFill(),
      this.isbnDiscoveryEnabled && !this.mobileUx.isPhone
    );
  }

  private setupPendingReviewSubscription(): void {
    this.refreshStagingTriage();

    // Metadata wipes, ISBN fills, and review staging arrive as book updates.
    // Refresh the triage buckets while Staging is open so books move immediately
    // without requiring a browser reload. Debounce batch websocket bursts.
    this.bookSocketService.bookUpdates$
      .pipe(debounceTime(100), takeUntil(this.destroy$))
      .subscribe(() => {
        if (this.entityType === EntityType.STAGING) {
          this.refreshStagingTriage();
        }
      });

    this.bookSocketService.bookCatalogChanges$
      .pipe(takeUntil(this.destroy$))
      .subscribe(event => {
        if (this.entityType !== EntityType.STAGING) {
          return;
        }
        if (event.type === 'created' && event.book.staged) {
          this.stagingInboxBookIds.add(event.book.id);
          this.stagingInboxCount = this.stagingInboxBookIds.size;
          this.lastStagingTriageSignature = '';
          this.rebuildStagingBookState();
          this.cdr.markForCheck();
        } else if (event.type === 'removed') {
          let changed = false;
          for (const removedId of event.bookIds) {
            if (this.stagingInboxBookIds.delete(removedId)) changed = true;
            if (this.stagingCompletedBookIds.delete(removedId)) changed = true;
            if (this.pendingReviewBookIds.delete(removedId)) changed = true;
          }
          if (changed) {
            this.stagingInboxCount = this.stagingInboxBookIds.size;
            this.stagingCompletedCount = this.stagingCompletedBookIds.size;
            this.pendingReviewCount = this.pendingReviewBookIds.size;
            this.lastStagingTriageSignature = '';
            this.rebuildStagingBookState();
            this.cdr.markForCheck();
          }
        }
      });

    this.bookSocketService.bookCatalogChanges$
      .pipe(debounceTime(150), takeUntil(this.destroy$))
      .subscribe(() => {
        if (this.entityType === EntityType.STAGING) {
          this.refreshStagingTriage();
        }
      });

    this.metadataProgressService.progressUpdates$
      .pipe(takeUntil(this.destroy$))
      .subscribe(progress => {
        if (progress.status === 'COMPLETED' || progress.status === 'ERROR' || progress.status === 'CANCELLED') {
          this.refreshStagingTriage();
        }
      });

    this.entityType$
      ?.pipe(takeUntil(this.destroy$))
      .subscribe(entityType => {
        if (entityType === EntityType.STAGING) {
          this.refreshStagingTriage();
        } else if (this.stagingTriageMode !== 'staging') {
          this.stagingTriageMode = 'staging';
          this.rebuildStagingBookState();
        }
      });
  }

  private refreshStagingTriage(): void {
    this.metadataTaskService.getStagingTriage()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (triage: StagingTriage) => {
          this.stagingInboxCount = triage.stagingCount ?? 0;
          this.stagingCompletedCount = triage.completedCount ?? 0;
          this.pendingReviewCount = triage.reviewCount ?? 0;
          this.stagingInboxBookIds = new Set(triage.stagingBookIds ?? []);
          this.stagingCompletedBookIds = new Set(triage.completedBookIds ?? []);
          this.pendingReviewBookIds = new Set(triage.reviewBookIds ?? []);
          this.pendingReviewTasks = triage.reviewTasks ?? [];
          this.pendingReviewPrimaryTaskId = this.pendingReviewTasks[0]?.taskId ?? null;

          if (this.stagingTriageMode === 'review' && this.pendingReviewCount === 0) {
            this.stagingTriageMode = 'staging';
            this.bookSelectionService?.deselectAll();
            this.syncStagingTriageModeQuery('staging');
          }
          if (this.stagingTriageMode === 'completed' && this.stagingCompletedCount === 0) {
            this.stagingTriageMode = 'staging';
            this.bookSelectionService?.deselectAll();
            this.syncStagingTriageModeQuery('staging');
          }

          const signature = [
            this.stagingTriageMode,
            this.stagingInboxCount,
            this.stagingCompletedCount,
            this.pendingReviewCount,
            [...this.stagingInboxBookIds].join(','),
            [...this.stagingCompletedBookIds].join(','),
            [...this.pendingReviewBookIds].join(','),
          ].join('|');

          if (this.entityType === EntityType.STAGING && signature !== this.lastStagingTriageSignature) {
            this.lastStagingTriageSignature = signature;
            this.rebuildStagingBookState();
          }
          this.cdr.markForCheck();
        },
        error: (err: {status?: number}) => {
          if (this.entityType === EntityType.STAGING && err?.status !== 401 && err?.status !== 403) {
            this.messageService?.add({
              severity: 'warn',
              summary: this.t.translate('book.browser.toast.stagingTriageRefreshFailedSummary'),
              detail: this.t.translate('book.browser.toast.stagingTriageRefreshFailedDetail'),
              life: 4000,
            });
          }
        }
      });
  }

  setStagingTriageMode(mode: StagingTriageMode): void {
    if (this.entityType !== EntityType.STAGING) {
      return;
    }
    if (mode === 'review' && this.pendingReviewCount === 0) {
      return;
    }
    if (mode === 'completed' && this.stagingCompletedCount === 0) {
      return;
    }
    if (mode === 'review' && this.stagingTriageMode === 'review' && this.pendingReviewPrimaryTaskId) {
      this.openPendingMetadataReview();
      return;
    }
    if (this.stagingTriageMode === mode) {
      return;
    }
    this.bookSelectionService?.deselectAll();
    this.stagingTriageMode = mode;
    this.syncStagingTriageModeQuery(mode);
    this.lastStagingTriageSignature = '';
    // Must rebuild even when sort is unchanged — applyEffectiveSortCriteria() no-ops then,
    // which left Completed/Review tabs looking selected while the grid stayed on Staging.
    this.rebuildStagingBookState();
    this.cdr.markForCheck();
  }

  /**
   * Force Staging bookState$ to reconnect so triage mode / ID set changes are applied.
   */
  private rebuildStagingBookState(): void {
    this.stagingTriageVersion$?.next((this.stagingTriageVersion$?.value ?? 0) + 1);
    if (this.entityType !== EntityType.STAGING || !this.currentViewMode) {
      return;
    }
    const baseSortCriteria = this.baseSortCriteria.length > 0
      ? this.baseSortCriteria
      : this.bookSorter.selectedSortCriteria;
    this.applySortCriteria(this.getEffectiveSortCriteria(baseSortCriteria));
  }

  private syncStagingTriageModeQuery(mode: StagingTriageMode): void {
    const queryValue = mode === 'staging' ? null : mode;
    if (this.activatedRoute.snapshot.queryParamMap.get(QUERY_PARAMS.STAGING_TRIAGE) === queryValue) {
      return;
    }
    void this.router.navigate([], {
      relativeTo: this.activatedRoute,
      queryParams: {[QUERY_PARAMS.STAGING_TRIAGE]: queryValue},
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  openPendingMetadataReview(): void {
    if (!this.pendingReviewPrimaryTaskId) {
      return;
    }
    this.openMetadataReviewAndRefresh(this.pendingReviewPrimaryTaskId);
  }

  openBookMetadataReview(book: Book): void {
    if (this.entityType !== EntityType.STAGING || this.stagingTriageMode !== 'review') {
      return;
    }
    const taskId = findReviewTaskIdForBook(this.pendingReviewTasks, book.id);
    if (!taskId) {
      return;
    }
    this.openMetadataReviewAndRefresh(taskId, book.id);
  }

  private openMetadataReviewAndRefresh(taskId: string, initialBookId?: number): void {
    const dialogRef = this.dialogLauncherService.openMetadataReviewDialog(taskId, initialBookId);
    dialogRef?.onClose
      .pipe(take(1), takeUntil(this.destroy$))
      .subscribe(() => {
        if (this.entityType === EntityType.STAGING) {
          this.refreshStagingTriage();
        }
      });
  }

  get canShowStagingTriageChrome(): boolean {
    return this.entityType === EntityType.STAGING;
  }

  private setupAppSettingsSubscription(): void {
    this.appSettingsService.appSettings$
      .pipe(takeUntil(this.destroy$))
      .subscribe(settings => {
        this.allowFileDeletion = settings?.allowFileDeletion ?? false;
        this.aiSearchEnabled = settings?.aiSearchEnabled ?? false;
        this.isbnDiscoveryEnabled = settings?.isbnDiscoveryEnabled ?? false;
        this.rebuildMetadataMenuItems();
        if (this.aiSearchEnabled) {
          this.startAiStatusPolling();
        } else {
          this.stopAiStatusPolling();
        }
        this.updateEntityOptions();
      });

    this.aiSearchDialogService.searchActive$
      .pipe(takeUntil(this.destroy$))
      .subscribe(active => {
        this.isAiSearchActive = active;
        this.cdr.markForCheck();
      });

    this.aiSearchDialogService.searchError$
      .pipe(takeUntil(this.destroy$))
      .subscribe(error => {
        this.isAiSearchError = error;
        this.cdr.markForCheck();
      });

    this.aiSearchDialogService.dialogVisible$
      .pipe(takeUntil(this.destroy$))
      .subscribe(visible => {
        this.aiSearchDialogVisible = visible;
        this.cdr.markForCheck();
      });

    this.aiSearchScanProgressService.progress$
      .pipe(takeUntil(this.destroy$))
      .subscribe(progress => {
        this.isBatchEmbedding = progress?.mode === 'BATCH' && progress?.event === 'IN_PROGRESS';
        this.cdr.markForCheck();
      });
  }

  private setupQueryParamSubscription(): void {
    this.sidebarFilterTogglePrefService.showFilter$
      .pipe(takeUntil(this.destroy$))
      .subscribe(value => {
        this.showFilter = value;
        requestAnimationFrame(() => this.updateVirtualGridDomBindings());
      });

    combineLatest([
      this.entityRouteInfo$,
      this.activatedRoute.queryParamMap,
      this.userService.userState$.pipe(filter(u => !!u?.user && u.loaded))
    ]).pipe(takeUntil(this.destroy$)).subscribe(([entityInfo, queryParamMap, user]) => {
      this.entityType = entityInfo.entityType;
      if (entityInfo.entityType === EntityType.STAGING) {
        const requestedTriageMode = queryParamMap.get(QUERY_PARAMS.STAGING_TRIAGE);
        const nextTriageMode: StagingTriageMode =
          requestedTriageMode === 'completed' || requestedTriageMode === 'review'
            ? requestedTriageMode
            : 'staging';
        if (this.stagingTriageMode !== nextTriageMode) {
          this.stagingTriageMode = nextTriageMode;
          this.lastStagingTriageSignature = '';
        }
      }
      const previousViewMode = this.currentViewMode;
      const previousFilterMode = this.selectedFilterMode.getValue();
      const previousFilterSignature = JSON.stringify(this.parsedFilters);
      const entityKey = `${entityInfo.entityType}:${entityInfo.entityId}`;
      const entityChanged = this.lastEntityKey !== null && this.lastEntityKey !== entityKey;
      if (this.lastEntityKey !== null && this.lastEntityKey !== entityKey) {
        this.directoryFilterService.clear();
      }
      this.lastEntityKey = entityKey;
      this.syncActiveDirectoryFilter();
      const parseResult = this.queryParamsService.parseQueryParams(
        queryParamMap,
        user.user?.userSettings?.entityViewPreferences,
        entityInfo.entityType,
        entityInfo.entityId,
        this.bookSorter.sortOptions,
        normalizeFilterMode(user.user?.userSettings?.filterMode)
      );

      this.hasExplicitSortQuery = queryParamMap.has('sort');
      this.baseSortCriteria = [...parseResult.sortCriteria];
      const effectiveSortCriteria = this.getEffectiveSortCriteria(parseResult.sortCriteria);


      this.settingFiltersFromUrl = true;
      const hasExplicitFilterMode = queryParamMap.has('fmode');

      if (!this.hasInitializedFilterMode || hasExplicitFilterMode) {
        if (parseResult.filterMode !== this.selectedFilterMode.getValue()) {
          this.selectedFilterMode.next(parseResult.filterMode);
        }
        this.hasInitializedFilterMode = true;
      }

      this.currentFilterLabel = this.t.translate('book.browser.labels.allBooks');
      const filterParams = queryParamMap.get('filter');

      if (filterParams) {
        this.selectedFilter.next(parseResult.filters);

        this.bookFilterComponents?.forEach(comp => {
          comp.setFilters?.(parseResult.filters);
          comp.onFiltersChanged?.();
        });

        if (Object.keys(parseResult.filters).length > 0) {
          this.currentFilterLabel = this.computedFilterLabel;
        }

        this.rawFilterParamFromUrl = filterParams;
      } else {
        this.rawFilterParamFromUrl = null;
      }

      this.settingFiltersFromUrl = false;

      if (!filterParams) {
        this.clearSidebarFiltersState();
      }

      this.parsedFilters = parseResult.filters;
      const currentFilterSignature = JSON.stringify(this.parsedFilters);

      if (entityInfo.entityType === EntityType.ALL_BOOKS || entityInfo.entityType === EntityType.NOT_SHELFED || entityInfo.entityType === EntityType.STAGING) {
        this.pageTitle.setPageTitle(this.currentFilterLabel ?? '');
      }

      this.entityViewPreferences = user.user?.userSettings?.entityViewPreferences;
      this.coverScalePreferenceService.initScaleValue(this.coverScalePreferenceService.scaleFactor);
      this.columnPreferenceService.initPreferences(user.user?.userSettings?.tableColumnPreference);
      this.visibleColumns = this.columnPreferenceService.visibleColumns;

      const visibleFields = user.user?.userSettings?.visibleSortFields ?? DEFAULT_VISIBLE_SORT_FIELDS;
      const sortOptionsByField = new Map(this.bookSorter.sortOptions.map(o => [o.field, o]));
      this.visibleSortOptions = visibleFields.map(f => sortOptionsByField.get(f)).filter((o): o is SortOption => !!o);


      if (!this.areSortCriteriaEqual(this.bookSorter.selectedSortCriteria, effectiveSortCriteria)) {
        this.bookSorter.setSortCriteria(effectiveSortCriteria);
      }
      this.currentViewMode = parseResult.viewMode;
      const dataSourceContextChanged = entityChanged
        || previousViewMode !== this.currentViewMode
        || previousFilterMode !== this.selectedFilterMode.getValue()
        || previousFilterSignature !== currentFilterSignature;

      if (dataSourceContextChanged || !this.areSortCriteriaEqual(this.lastAppliedSortCriteria, this.bookSorter.selectedSortCriteria)) {
        this.lastAppliedSortCriteria = [...this.bookSorter.selectedSortCriteria];
        this.applySortCriteria(this.bookSorter.selectedSortCriteria);
      }


      this.queryParamsService.syncQueryParams(
        this.activatedRoute,
        this.currentViewMode!,
        this.selectedFilterMode.getValue(),
        this.parsedFilters
      );
    });
  }

  private setupSearchTermSubscription(): void {
    this.searchTerm$.pipe(takeUntil(this.destroy$)).subscribe(term => {
      this.hasSearchTerm = !!term && term.trim().length > 0;
    });

    // Derive search-filtered books for the filter sidebar.
    // When a search term is active, apply the same HeaderFilter logic
    // so the sidebar facet counts reflect only the visible books.
    this.searchFilteredForSidebar$ = combineLatest([
      this.bookService.bookState$,
      this.searchTerm$,
      this.stagingTriageVersion$,
    ]).pipe(
      switchMap(([state, term]) => {
        const trimmed = (term || '').trim();
        const baseBooks$ = trimmed.length < 2
          ? of(state.books || [])
          : this.headerFilter.filter(state).pipe(
              map(filtered => filtered.books || [])
            );
        return baseBooks$.pipe(
          map(books => {
            if (this.entityType !== EntityType.STAGING) {
              return books;
            }
            const stagedOnly = books.filter(book => book.staged === true);
            return filterBooksByStagingTriage(
              stagedOnly,
              this.stagingTriageMode,
              this.stagingInboxBookIds,
              this.stagingCompletedBookIds,
              this.pendingReviewBookIds,
            ) ?? [];
          })
        );
      }),
      shareReplay({ bufferSize: 1, refCount: true }),
      takeUntil(this.destroy$)
    );
  }

  private setupFilterToggleSubscription(): void {
    this.sidebarFilterTogglePrefService.showFilter$
      .pipe(takeUntil(this.destroy$))
      .subscribe(value => {
        this.showFilter = value;
      });
  }

  private setupSelectionSubscription(): void {
    this.bookSelectionService.selectedBooks$
      .pipe(takeUntil(this.destroy$))
      .subscribe(selectedBooks => {
        const nextSelectedCount = selectedBooks.size;

        if (nextSelectedCount > 0 && this.previousSelectedCount === 0) {
          this.isSelectionActionPanelOpen = true;
        } else if (nextSelectedCount === 0 && this.previousSelectedCount > 0) {
          this.isSelectionActionPanelOpen = false;
        }

        this.previousSelectedCount = nextSelectedCount;
        this.syncSelectionState(selectedBooks);
        this.cdr.detectChanges();
      });
  }

  private syncSelectionState(selectedBooks: Set<number>): void {
    this.selectedBookIds = new Set(selectedBooks);
    this.selectedCount = this.selectedBookIds.size;
    this.isDrawerVisible = this.selectedCount > 0;
    this.moreActionsMenuItems = this.bookMenuService.getMoreActionsMenu(this.selectedBookIds, this.user());
    this.aiSearchMenuItems = [
      {
        label: 'AI-Search Selected',
        icon: 'pi pi-sparkles',
        command: () => this.openAiSearch()
      },
      {
        separator: true
      },
      {
        label: 'Embed Now',
        icon: 'pi pi-bolt',
        command: () => this.embedSelectedNow()
      },
      {
        label: 'Mark for Embedding',
        icon: 'pi pi-bookmark',
        command: () => this.markSelectedForAiSearch()
      },
      {
        separator: true
      },
      {
        label: 'Delete Embeddings',
        icon: 'pi pi-trash',
        command: () => this.deleteSelectedEmbeddings()
      }
    ];
    this.updateSelectionVisibility();
  }

  private updateSelectionVisibility(): void {
    if (this.selectedBookIds.size === 0) {
      this.hiddenSelectedCount = 0;
      return;
    }

    const visibleBookIds = this.visibleBookIds.size > 0
      ? this.visibleBookIds
      : new Set(this.bookSelectionService.getCurrentBooks().map(book => book.id));
    let visibleSelectedCount = 0;

    this.selectedBookIds.forEach(bookId => {
      if (visibleBookIds.has(bookId)) {
        visibleSelectedCount += 1;
      }
    });

    this.hiddenSelectedCount = Math.max(this.selectedBookIds.size - visibleSelectedCount, 0);
  }

  toggleSelectionActionPanel(): void {
    this.isSelectionActionPanelOpen = !this.isSelectionActionPanelOpen;
  }

  onFilterSelected(filters: Record<string, string[]> | null): void {
    if (this.settingFiltersFromUrl) return;

    this.selectedFilter.next(filters);
    this.rawFilterParamFromUrl = null;
    this.parsedFilters = filters ?? {};

    const hasSidebarFilters = !!filters && Object.keys(filters).length > 0;
    this.currentFilterLabel = hasSidebarFilters ? this.computedFilterLabel : this.t.translate('book.browser.labels.allBooks');

    this.queryParamsService.updateFilters(this.activatedRoute, filters);

    if (this.entityType) {
      this.applySortCriteria(this.getEffectiveSortCriteria(this.bookSorter.selectedSortCriteria));
    }
  }

  onFilterModeChanged(mode: BookFilterMode): void {
    const safe = normalizeFilterMode(mode);
    if (this.settingFiltersFromUrl || safe === this.selectedFilterMode.getValue()) return;

    this.clearSidebarFiltersState(true);
    this.selectedFilterMode.next(safe);
    this.queryParamsService.updateFilterMode(this.activatedRoute, safe, {}, true);
    this.persistFilterModePreference(safe);

    if (this.entityType) {
      this.applySortCriteria(this.getEffectiveSortCriteria(this.bookSorter.selectedSortCriteria));
    }
  }

  private clearSidebarFiltersState(suppressFilterSelectionEvents = false): void {
    const previousSettingFiltersFromUrl = this.settingFiltersFromUrl;

    if (suppressFilterSelectionEvents) {
      this.settingFiltersFromUrl = true;
    }

    this.rawFilterParamFromUrl = null;
    this.parsedFilters = {};
    this.currentFilterLabel = this.t.translate('book.browser.labels.allBooks');

    if (this.selectedFilter.value !== null) {
      this.selectedFilter.next(null);
    }

    this.resetFilterSubject.next();

    if (suppressFilterSelectionEvents) {
      this.settingFiltersFromUrl = previousSettingFiltersFromUrl;
    }
  }

  private persistFilterModePreference(mode: BookFilterMode): void {
    const user = this.userService.getCurrentUser();
    if (!user || user.userSettings?.filterMode === mode) {
      return;
    }

    this.userService.updateUserSetting(user.id, 'filterMode', mode);
  }

  toggleSidebar(): void {
    this.showFilter = !this.showFilter;
    this.sidebarFilterTogglePrefService.selectedShowFilter = this.showFilter;
    requestAnimationFrame(() => this.updateVirtualGridDomBindings());
  }

  onMobileRightSidebarShow(): void {
    this.isMobileRightSidebarOpen = true;
    if (this.mobileRightSidebarBackHandle) {
      return;
    }

    this.mobileRightSidebarBackHandle = this.mobileBackNavigation.register(() => {
      this.mobileRightSidebarPop?.hide();
    });
  }

  onMobileRightSidebarHide(): void {
    this.isMobileRightSidebarOpen = false;
    this.mobileRightSidebarBackHandle?.release();
    this.mobileRightSidebarBackHandle = null;
  }

  onSortPopoverShow(): void {
    this.isSortPopoverOpen = true;
    if (this.sortPopoverBackHandle) {
      return;
    }
    this.sortPopoverBackHandle = this.mobileBackNavigation.register(() => {
      this.sortPopover?.hide();
    });
  }

  onSortPopoverHide(): void {
    this.isSortPopoverOpen = false;
    this.sortPopoverBackHandle?.release();
    this.sortPopoverBackHandle = null;
  }

  onDisplaySettingsShow(): void {
    this.isDisplaySettingsOpen = true;
    if (this.displaySettingsBackHandle) {
      return;
    }
    this.displaySettingsBackHandle = this.mobileBackNavigation.register(() => {
      this.seriesCollapseOverlay?.hide();
    });
  }

  onDisplaySettingsHide(): void {
    this.isDisplaySettingsOpen = false;
    this.displaySettingsBackHandle?.release();
    this.displaySettingsBackHandle = null;
  }

  onColumnPopoverShow(): void {
    this.isColumnPopoverOpen = true;
    if (this.columnPopoverBackHandle) {
      return;
    }
    this.columnPopoverBackHandle = this.mobileBackNavigation.register(() => {
      this.columnPopover?.hide();
    });
  }

  onColumnPopoverHide(): void {
    this.isColumnPopoverOpen = false;
    this.columnPopoverBackHandle?.release();
    this.columnPopoverBackHandle = null;
  }

  private forceCloseHeaderPopovers(removeHistoryEntry: boolean): void {
    if (this.isSortPopoverOpen) {
      this.sortPopover?.hide();
    }
    if (this.isDisplaySettingsOpen) {
      this.seriesCollapseOverlay?.hide();
    }
    if (this.isColumnPopoverOpen) {
      this.columnPopover?.hide();
    }
    if (this.mobileDirectoryPopoverOpen) {
      this.mobileDirPop?.hide();
    }

    this.sortPopoverBackHandle?.release(removeHistoryEntry);
    this.sortPopoverBackHandle = null;
    this.displaySettingsBackHandle?.release(removeHistoryEntry);
    this.displaySettingsBackHandle = null;
    this.columnPopoverBackHandle?.release(removeHistoryEntry);
    this.columnPopoverBackHandle = null;
    this.mobileDirectoryBackHandle?.release(removeHistoryEntry);
    this.mobileDirectoryBackHandle = null;
    this.isSortPopoverOpen = false;
    this.isDisplaySettingsOpen = false;
    this.isColumnPopoverOpen = false;
    this.mobileDirectoryPopoverOpen = false;
  }

  private toggleMobileRightSidebar(event: MouseEvent): void {
    if (!this.mobileRightSidebarPop) {
      return;
    }

    if (!this.isMobileRightSidebarOpen) {
      this.isMobileRightSidebarOpen = true;
    }
    this.mobileRightSidebarPop.toggle(event);
  }

  private forceCloseMobileRightSidebar(removeHistoryEntry: boolean): void {
    if (this.isMobileRightSidebarOpen) {
      this.mobileRightSidebarPop?.hide();
    }

    this.mobileRightSidebarBackHandle?.release(removeHistoryEntry);
    this.mobileRightSidebarBackHandle = null;
    this.isMobileRightSidebarOpen = false;
  }

  updateScale(): void {
    this.coverScalePreferenceService.setScale(this.coverScalePreferenceService.scaleFactor);
    this.cardWidthSig.set(this.currentCardSize.width);
    this.cardHeightSig.set(this.getUniformCardHeight());
    queueMicrotask(() => this.updateVirtualGridDomBindings());
  }

  onVisibleColumnsChange(selected: { field: string; header: string }[]): void {
    const allFields = this.bookTableComponent.allColumns.map(col => col.field);
    this.visibleColumns = selected.sort(
      (a, b) => allFields.indexOf(a.field) - allFields.indexOf(b.field)
    );
  }

  onCheckboxClicked(event: CheckboxClickEvent): void {
    this.bookSelectionService.handleCheckboxClick(event);
  }

  onSelectedBooksChange(selectedBookIds: Set<number>): void {
    this.bookSelectionService.setSelectedBooks(selectedBookIds);
  }

  selectAllBooks(): void {
    this.bookSelectionService.setSelectedBooks(new Set(this.visibleBookIds));
  }

  deselectAllBooks(): void {
    this.bookSelectionService.deselectAll();
  }

  confirmDeleteBooks(): void {
    this.confirmationService.confirm({
      message: this.t.translate('book.browser.confirm.deleteMessage', {count: this.selectedBooks.size}),
      header: this.t.translate('book.browser.confirm.deleteHeader'),
      icon: 'pi pi-exclamation-triangle',
      acceptIcon: 'pi pi-trash',
      rejectIcon: 'pi pi-times',
      acceptLabel: this.t.translate('common.delete'),
      rejectLabel: this.t.translate('common.cancel'),
      acceptButtonStyleClass: 'p-button-danger',
      rejectButtonStyleClass: 'p-button-outlined',
      accept: () => {
        const count = this.selectedBooks.size;
        this.writeProgressService.show(this.t.translate('book.browser.loading.deleting', {count}));

        this.bookService.deleteBooks(this.selectedBooks, true)
          .subscribe(() => {
            this.writeProgressService.complete(`Deleted ${count} book${count === 1 ? '' : 's'}`);
            this.bookSelectionService.deselectAll();
          });
      }
    });
  }

  confirmDeleteBooksLibraryOnly(): void {
    this.confirmationService.confirm({
      message: this.t.translate('book.browser.confirm.removeFromLibraryMessage', {count: this.selectedBooks.size}),
      header: this.t.translate('book.browser.confirm.removeFromLibraryHeader'),
      icon: 'pi pi-exclamation-triangle',
      acceptIcon: 'pi pi-minus-circle',
      rejectIcon: 'pi pi-times',
      acceptLabel: this.t.translate('common.remove'),
      rejectLabel: this.t.translate('common.cancel'),
      acceptButtonStyleClass: 'p-button-warning',
      rejectButtonStyleClass: 'p-button-outlined',
      accept: () => {
        this.removeSelectedBooks('REMOVE_FOREVER');
      }
    });
  }

  confirmDeleteBooksLibraryUntilNextScan(): void {
    this.confirmationService.confirm({
      message: `${this.t.translate('book.browser.confirm.removeFromLibraryMessage', {count: this.selectedBooks.size})}\n\nThis option allows the books to return on the next scan.`,
      header: 'Remove Until Next Scan',
      icon: 'pi pi-exclamation-triangle',
      acceptIcon: 'pi pi-history',
      rejectIcon: 'pi pi-times',
      acceptLabel: this.t.translate('common.remove'),
      rejectLabel: this.t.translate('common.cancel'),
      acceptButtonStyleClass: 'p-button-warning',
      rejectButtonStyleClass: 'p-button-outlined',
      accept: () => {
        this.removeSelectedBooks('REMOVE_UNTIL_NEXT_SCAN');
      }
    });
  }

  private removeSelectedBooks(mode: RemoveFromLibraryMode): void {
    const count = this.selectedBooks.size;
    this.writeProgressService.show(this.t.translate('book.browser.loading.deleting', {count}));

    this.bookService.deleteBooks(this.selectedBooks, false, mode)
      .subscribe(() => {
        const detail = mode === 'REMOVE_FOREVER'
          ? `Removed ${count} book${count === 1 ? '' : 's'} from library permanently`
          : `Removed ${count} book${count === 1 ? '' : 's'} from library until next scan`;
        this.writeProgressService.complete(detail);
        this.bookSelectionService.deselectAll();
      });
  }

  onSeriesCollapseCheckboxChange(value: boolean): void {
    this.seriesCollapseFilter.setCollapsed(value);
  }

  onMultiSortChange(sortCriteria: SortOption[]): void {
    if (this.isDirectoryScopedView) {
      this.baseSortCriteria = [...sortCriteria];
      this.applyEffectiveSortCriteria();
      return;
    }

    this.baseSortCriteria = [...sortCriteria];
    this.hasExplicitSortQuery = true;
    this.applySortCriteria(sortCriteria);
    this.queryParamsService.updateMultiSort(this.activatedRoute, sortCriteria);
  }

  onManualSortChange(sortOption: SortOption): void {
    this.onMultiSortChange([sortOption]);
  }

  applySortCriteria(sortCriteria: SortOption[]): void {
    if (!this.isRouteAttached) {
      return;
    }

    const primarySort = sortCriteria[0] ?? {field: 'addedOn', direction: 'DESCENDING', label: 'Added On'};
    const nextViewportContext = this.createGridViewportContext(sortCriteria);

    if (shouldResetGridViewport(this.lastGridViewportContext, nextViewportContext)) {
      this.pendingGridViewportReset = true;
      this.gridRenderVersion += 1;
      this.cdr.markForCheck();
    }

    this.lastGridViewportContext = nextViewportContext;

    const pagedEntity = this.getPagedPilotEntity();

    if (pagedEntity) {
      // Staging triage tabs (Staging/Completed/Review) require the full staged set.
      // Paged mode only loads staged pages and skipped the client ID filter, so tabs
      // appeared active while the grid still showed every staged book.
      if (pagedEntity === 'STAGING') {
        this.pagedGridPilotService.resetActiveQuery();
        this.bookState$ = this.entityService.fetchStagedBooks(primarySort).pipe(
          map(bookState => this.applyClientSideMultiSort(bookState, sortCriteria)),
          switchMap(bookState => this.applyBookFilters(bookState)),
        );
      } else if (pagedEntity === 'ALL_BOOKS' || pagedEntity === 'NOT_SHELFED') {
        this.bookState$ = this.pagedGridPilotService.connect({
          entity: pagedEntity,
          entityId: null,
          viewMode: this.currentViewMode,
          sortCriteria,
          filters: this.parsedFilters,
          filterMode: this.selectedFilterMode.getValue(),
          isDirectoryScopedView: this.isDirectoryScopedView,
          isSeriesCollapsed: this.seriesCollapseFilter.isSeriesCollapsed,
          searchTerm: this.searchTerm$.getValue(),
        }, () => (pagedEntity === 'NOT_SHELFED'
          ? this.entityService.fetchNotShelfedBooks(primarySort)
          : this.entityService.fetchAllBooks(primarySort)
        ).pipe(
          map(bookState => this.applyClientSideMultiSort(bookState, sortCriteria)),
          switchMap(bookState => this.applyBookFilters(bookState))
        ));
      } else {
        const routeParam$ = this.entityService.getEntityInfoFromRoute(this.activatedRoute);
        this.bookState$ = routeParam$.pipe(
          switchMap(({entityId, entityType}) =>
            this.pagedGridPilotService.connect({
              entity: pagedEntity,
              entityId,
              viewMode: this.currentViewMode,
              sortCriteria,
              filters: this.parsedFilters,
              filterMode: this.selectedFilterMode.getValue(),
              isDirectoryScopedView: this.isDirectoryScopedView,
              isSeriesCollapsed: this.seriesCollapseFilter.isSeriesCollapsed,
              searchTerm: this.searchTerm$.getValue(),
            }, () => this.entityService.fetchBooksByEntity(entityId, entityType, primarySort).pipe(
              map(bookState => this.applyClientSideMultiSort(bookState, sortCriteria)),
              switchMap(bookState => this.applyBookFilters(bookState))
            ))
          )
        );
      }
    } else {
      this.syncExplicitLegacyBrowseStatus();
      const routeParam$ = this.entityService.getEntityInfoFromRoute(this.activatedRoute);
      this.bookState$ = routeParam$.pipe(
        switchMap(({entityId, entityType}) =>
          this.entityService.fetchBooksByEntity(entityId, entityType, primarySort)
        ),
        map(bookState => this.applyClientSideMultiSort(bookState, sortCriteria)),
        switchMap(bookState => this.applyBookFilters(bookState))
      );
    }

    if (this.bookStateSubscription) {
      this.bookStateSubscription.unsubscribe();
    }

    this.visibleBookIds = new Set<number>();

    this.bookStateSubscription = this.bookState$
      .pipe(
        tap((state: BookState) => {
          if (!state.loaded && !state.error && this.useDistractionLoadingScreen && !this.distractionCoverUrl && !this.isFetchingDistractionCover) {
            this.isFetchingDistractionCover = true;
            const libIdParam = this.activatedRoute.snapshot.paramMap.get('libraryId');
            const libId = this.entityType === EntityType.LIBRARY && libIdParam ? Number(libIdParam) : undefined;
            this.bookService.getRandomBooks(0, 1, libId).subscribe({
              next: (res) => {
                if (res.content && res.content.length > 0 && !state.loaded) {
                  const book = res.content[0];
                  const isAudiobook = book.primaryFileType === 'AUDIOBOOK';
                  this.distractionCoverUrl = isAudiobook
                    ? this.urlHelper.getAudiobookThumbnailUrl(book.id, book.audiobookCoverUpdatedOn)
                    : this.urlHelper.getThumbnailUrl(book.id, book.coverUpdatedOn);
                  this.cdr.markForCheck();
                }
                this.isFetchingDistractionCover = false;
              },
              error: () => {
                this.isFetchingDistractionCover = false;
              }
            });
          }
          if (state.loaded) {
            this.distractionCoverUrl = null;
            this.isFetchingDistractionCover = false;
          }
        }),
        filter(state => state.loaded && !state.error),
        takeUntil(this.destroy$),
        map(state => state.books || [])
      )
      .subscribe(books => {
        // Auto-clear filters when a state change (e.g. shelf assignment) causes
        // the active filter to produce zero matching books. Without this, the
        // filter stays "stuck" in the URL and component state even though it no
        // longer matches anything, forcing the user to manually switch filters.
        if (books.length === 0 && this.isFilterActive) {
          this.clearFilter();
          return;
        }

        this.gridItemCountSig.set(books.length);
        this.cardWidthSig.set(this.currentCardSize.width);
        this.cardHeightSig.set(this.getUniformCardHeight());
        this.gapSig.set(this.isMobile ? this.MOBILE_GAP : 20.8);
        this.updateVirtualGridDomBindings();

        const restoredSavedScrollPosition = this.restorePendingScrollPosition();

        if (this.currentViewMode === VIEW_MODES.GRID && this.pendingGridViewportReset && !restoredSavedScrollPosition) {
          this.resetGridViewport();
        }

        this.pendingGridViewportReset = false;

        this.visibleBookIds = new Set(books.map(book => book.id));
        this.bookSelectionService.setCurrentBooks(books);
        this.bookNavigationService.setAvailableBookIds(books.map(book => book.id));
        this.updateSelectionVisibility();
        this.cdr.markForCheck();
      });
  }

  private syncExplicitLegacyBrowseStatus(): void {
    switch (this.entityType) {
      case EntityType.MAGIC_SHELF:
        this.pagedGridPilotService.setExplicitLegacyStatus(
          'Magic Shelf routes currently use the legacy full-state path.',
          ['magic shelf route stays on legacy full-state mode'],
        );
        break;
      default:
        this.pagedGridPilotService.resetActiveQuery();
        break;
    }
  }

  private restorePendingScrollPosition(): boolean {
    if (this.pendingScrollRestorePosition === null) {
      return false;
    }

    const savedPosition = this.pendingScrollRestorePosition;
    this.pendingScrollRestorePosition = null;

    requestAnimationFrame(() => {
      const scrollEl = this.scrollContainer?.nativeElement;
      if (!scrollEl) {
        return;
      }

      scrollEl.scrollTop = savedPosition;
      scrollEl.dispatchEvent(new Event('scroll'));
      this.cdr.detectChanges();
    });

    return true;
  }

  private getUniformCardHeight(): number {
    if (this.isMobile) {
      return this.mobileCardSize.height;
    }
    const extraRows = (this.desktopTitleRows - 1) * this.coverScalePreferenceService.TITLE_ROW_EXTRA_HEIGHT;
    if (this.isAudiobookOnlyLibrary) {
      return this.currentCardSize.height + extraRows;
    }
    return this.coverScalePreferenceService.getCardHeightForTitleRows(this.desktopTitleRows);
  }

  private updateVirtualGridDomBindings(): void {
    const scrollEl = this.scrollContainer?.nativeElement ?? null;
    this.virtualGrid.setScrollElement(scrollEl);

    const widthEl = this.gridContainer?.nativeElement ?? scrollEl;
    if (widthEl) {
      // clientWidth might be 0 during initial view attach, queue microtask to get painted width
      queueMicrotask(() => {
        if (widthEl.clientWidth > 0) {
          this.virtualGrid.setContainerWidth(widthEl.clientWidth);
        }

        requestAnimationFrame(() => {
          this.virtualGrid.virtualizer.measure();

          if (scrollEl) {
            scrollEl.dispatchEvent(new Event('scroll'));
          }

          this.cdr.markForCheck();
        });
      });
    }
  }

  private resetGridViewport(): void {
    const scrollEl = this.scrollContainer?.nativeElement;
    if (!scrollEl) {
      return;
    }

    queueMicrotask(() => {
      scrollEl.scrollTop = 0;
      scrollEl.dispatchEvent(new Event('scroll'));
    });
  }

  onGridScroll(event?: Event): void {
    if (event && !event.isTrusted) {
      return;
    }

    this.loadNextPagedPageIfNeeded(this.scrollContainer?.nativeElement ?? null);
  }

  onTableScroll(metrics: TableViewportMetrics): void {
    if (!this.pagedGridPilotService.isPagedActive()) {
      return;
    }

    this.pagedGridPilotService.loadNextPageIfNeeded(
      metrics.scrollTop,
      metrics.clientHeight,
      metrics.scrollHeight,
    );
  }

  private loadNextPagedPageIfNeeded(scrollElement: HTMLElement | null): void {
    if (!this.pagedGridPilotService.isPagedActive()) {
      return;
    }

    if (!scrollElement) {
      return;
    }

    this.pagedGridPilotService.loadNextPageIfNeeded(
      scrollElement.scrollTop,
      scrollElement.clientHeight,
      scrollElement.scrollHeight,
    );
  }

  private applyClientSideMultiSort(bookState: BookState, sortCriteria: SortOption[]): BookState {
    if (!bookState.books || sortCriteria.length <= 1) {
      return bookState;
    }
    return {
      ...bookState,
      books: this.sortService.applyMultiSort(bookState.books, sortCriteria)
    };
  }

  applySortOption(sortOption: SortOption): void {
    this.applySortCriteria([sortOption]);
  }

  private areSortCriteriaEqual(a: SortOption[], b: SortOption[]): boolean {
    if (a.length !== b.length) return false;
    return a.every((criterion, index) =>
      criterion.field === b[index].field && criterion.direction === b[index].direction
    );
  }

  onSortCriteriaChange(criteria: SortOption[]): void {
    if (this.isDirectoryScopedView) {
      this.baseSortCriteria = [...criteria];
      this.applyEffectiveSortCriteria();
      return;
    }

    this.bookSorter.setSortCriteria(criteria);
    this.onMultiSortChange(criteria);
  }

  onShowSubtitlesChange(value: boolean): void {
    this.showSubtitles = !!value;
    this.localStorageService.set(this.SHOW_SUBTITLES_STORAGE_KEY, this.showSubtitles);

    const previewBook = this.getPreviewBook();
    if (previewBook) {
      this.selectedBookTitle = this.getDisplayTitle(previewBook);
    }
  }

  get canSaveSort(): boolean {
    return this.entityType === EntityType.LIBRARY ||
           this.entityType === EntityType.SHELF ||
           this.entityType === EntityType.MAGIC_SHELF ||
           this.entityType === EntityType.ALL_BOOKS ||
           this.entityType === EntityType.NOT_SHELFED ||
           this.entityType === EntityType.STAGING;
  }

  onSaveSortConfig(criteria: SortOption[]): void {
    if (!this.entityType) return;

    const user = this.userService.getCurrentUser();
    if (!user) return;

    const sortCriteria: SortCriterion[] = criteria.map(c => ({
      field: c.field,
      direction: c.direction === SortDirection.ASCENDING ? 'ASC' as const : 'DESC' as const
    }));

    const prefs: EntityViewPreferences = structuredClone(
      user.userSettings.entityViewPreferences ?? {global: {sortKey: 'title', sortDir: 'ASC', view: 'GRID', coverSize: 1.0, seriesCollapsed: false, overlayBookType: true, overlayAiPanelData: true, overlayAiSearchData: true, overlayIssueNumber: true}, overrides: []}
    );

    if (this.entityType === EntityType.ALL_BOOKS || this.entityType === EntityType.NOT_SHELFED || this.entityType === EntityType.STAGING) {
      prefs.global = {
        ...prefs.global,
        sortKey: sortCriteria[0]?.field ?? 'title',
        sortDir: sortCriteria[0]?.direction ?? 'ASC',
        sortCriteria
      };
    } else {
      if (!this.entity) return;
      if (!prefs.overrides) prefs.overrides = [];

      let overrideEntityType: 'LIBRARY' | 'SHELF' | 'MAGIC_SHELF';
      switch (this.entityType) {
        case EntityType.LIBRARY: overrideEntityType = 'LIBRARY'; break;
        case EntityType.SHELF: overrideEntityType = 'SHELF'; break;
        case EntityType.MAGIC_SHELF: overrideEntityType = 'MAGIC_SHELF'; break;
        default: return;
      }

      const existingIndex = prefs.overrides.findIndex(
        o => o.entityType === overrideEntityType && o.entityId === this.entity!.id
      );

      if (existingIndex >= 0) {
        prefs.overrides[existingIndex].preferences = {
          ...prefs.overrides[existingIndex].preferences,
          sortKey: sortCriteria[0]?.field ?? 'title',
          sortDir: sortCriteria[0]?.direction ?? 'ASC',
          sortCriteria
        };
      } else {
        prefs.overrides.push({
          entityType: overrideEntityType,
          entityId: this.entity!.id!,
          preferences: {
            sortKey: sortCriteria[0]?.field ?? 'title',
            sortDir: sortCriteria[0]?.direction ?? 'ASC',
            sortCriteria,
            view: 'GRID',
            coverSize: 1.0,
            seriesCollapsed: false,
            overlayBookType: true,
            overlayAiPanelData: true,
            overlayAiSearchData: true,
            overlayIssueNumber: true
          }
        });
      }
    }

    this.userService.updateUserSetting(user.id, 'entityViewPreferences', prefs);
    this.messageService.add({
      severity: 'success',
      summary: this.t.translate('book.browser.toast.sortSavedSummary'),
      detail: this.entityType === EntityType.ALL_BOOKS || this.entityType === EntityType.NOT_SHELFED || this.entityType === EntityType.STAGING
        ? this.t.translate('book.browser.toast.sortSavedGlobalDetail')
        : this.t.translate('book.browser.toast.sortSavedEntityDetail', {entityType: this.entityType.toLowerCase()})
    });
  }

  private getStoredMediaTypes(): string[] {
    return this.mediaTypePreferences.getCustomTypes();
  }

  private setStoredMediaTypes(types: string[]): void {
    this.mediaTypePreferences.setCustomTypes(types);
  }

  private openMediaTypeManagerDialog(): void {
    this.dynamicDialogRef = this.dialogHelperService.openMediaTypeManagerDialog();
    if (!this.dynamicDialogRef) {
      return;
    }

    this.dynamicDialogRef.onClose.subscribe((result: {
      changed?: boolean;
      renamed?: {from: string; to: string}[];
      deleted?: string[];
    } | boolean) => {
      if (!result || typeof result === 'boolean' || !result.changed) {
        return;
      }

      const active = this.activeMediaTypeFilter;
      if (!active) {
        return;
      }

      const renamedMatch = (result.renamed ?? [])
        .find(rename => rename.from.toLowerCase() === active.toLowerCase());

      if (renamedMatch) {
        this.queryParamsService.updateFilters(this.activatedRoute, {customMediaType: [renamedMatch.to]});
        return;
      }

      const deletedActive = (result.deleted ?? [])
        .some(type => type.toLowerCase() === active.toLowerCase());
      if (deletedActive) {
        this.clearFilter();
      }
    });
  }

  private openMediaTypeDeleteDialog(mediaType: string): void {
    this.dynamicDialogRef = this.dialogHelperService.openMediaTypeDeleteDialog(mediaType, this.getMediaTypeUsageCount(mediaType));
    if (!this.dynamicDialogRef) {
      return;
    }

    this.dynamicDialogRef.onClose.subscribe((result: {confirmed?: boolean} | boolean) => {
      const confirmed = typeof result === 'boolean' ? result : !!result?.confirmed;
      if (!confirmed) {
        return;
      }

      this.deleteMediaType(mediaType);
    });
  }

  private deleteMediaType(mediaType: string): void {
    const updated = this.getStoredMediaTypes().filter(type => type.toLowerCase() !== mediaType.toLowerCase());
    this.setStoredMediaTypes(updated);

    const ids = new Set((this.bookService.getCurrentBookState().books ?? [])
      .filter(book => (book.fileType ?? '').trim().toLowerCase() === mediaType.toLowerCase())
      .map(book => book.id));

    if (!ids.size) {
      if (this.activeMediaTypeFilter?.toLowerCase() === mediaType.toLowerCase()) {
        this.clearFilter();
      }
      this.messageService.add({severity: 'success', summary: 'Success', detail: 'Media Type deleted.'});
      return;
    }

    this.bookService.updateFileType(ids, null).subscribe({
      next: () => {
        if (this.activeMediaTypeFilter?.toLowerCase() === mediaType.toLowerCase()) {
          this.clearFilter();
        }
        this.messageService.add({severity: 'success', summary: 'Success', detail: 'Media Type deleted.'});
      },
      error: () => {
        this.toastError('Error', 'Failed to delete Media Type.');
      }
    });
  }

  private getMediaTypeUsageCount(mediaType: string): number {
    return (this.bookService.getCurrentBookState().books ?? [])
      .filter(book => (book.fileType ?? '').trim().toLowerCase() === mediaType.toLowerCase())
      .length;
  }

  private getEffectiveSortCriteria(baseSortCriteria: SortOption[]): SortOption[] {
    if (!isDirectoryScopeActive(this.activeDirFilterPath)) {
      return baseSortCriteria;
    }

    return getDirectoryScopedSortCriteria(baseSortCriteria, this.getDirectoryDefaultSortOption());
  }

  private applyEffectiveSortCriteria(): void {
    if (this.entityType === undefined || !this.currentViewMode) {
      return;
    }

    const baseSortCriteria = this.baseSortCriteria.length > 0 ? this.baseSortCriteria : this.bookSorter.selectedSortCriteria;
    const effectiveSortCriteria = this.getEffectiveSortCriteria(baseSortCriteria);

    if (!this.areSortCriteriaEqual(this.bookSorter.selectedSortCriteria, effectiveSortCriteria)) {
      this.bookSorter.setSortCriteria(effectiveSortCriteria);
    }

    if (!this.areSortCriteriaEqual(this.lastAppliedSortCriteria, effectiveSortCriteria)) {
      this.lastAppliedSortCriteria = [...effectiveSortCriteria];
      this.applySortCriteria(effectiveSortCriteria);
    }
  }

  private getDirectoryDefaultSortOption(): SortOption {
    return this.bookSorter.sortOptions.find(option => option.field === 'fileName')
      ?? {field: 'fileName', direction: SortDirection.ASCENDING, label: 'File Name'};
  }

  onSearchTermChange(term: string): void {
    this.searchTerm$.next(term);

    if (this.getPagedPilotEntity()) {
      this.applySortCriteria(this.getEffectiveSortCriteria(this.bookSorter.selectedSortCriteria));
    }
  }

  private getPagedPilotEntity(): PagedBookBrowserEntity | null {
    switch (this.entityType) {
      case EntityType.ALL_BOOKS:
        return 'ALL_BOOKS';
      case EntityType.LIBRARY:
        return 'LIBRARY';
      case EntityType.SHELF:
        return 'SHELF';
      case EntityType.NOT_SHELFED:
        return 'NOT_SHELFED';
      case EntityType.STAGING:
        return 'STAGING';
      default:
        return null;
    }
  }

  clearSearch(): void {
    this.bookTitle = '';
    this.onSearchTermChange('');
    this.resetFilters();
  }

  resetFilters(): void {
    this.resetFilterSubject.next();
  }

  clearFilter(): void {
    if (this.selectedFilter.value !== null) {
      this.selectedFilter.next(null);
    }
    this.parsedFilters = {};
    this.rawFilterParamFromUrl = null;
    this.currentFilterLabel = this.t.translate('book.browser.labels.allBooks');
    this.queryParamsService.updateFilters(this.activatedRoute, null);
    this.clearSearch();
  }

  toggleTableGrid(): void {
    this.currentViewMode = this.currentViewMode === VIEW_MODES.GRID ? VIEW_MODES.TABLE : VIEW_MODES.GRID;
    this.queryParamsService.updateViewMode(this.activatedRoute, this.currentViewMode as 'grid' | 'table');
  }

  unshelfBooks(): void {
    if (!this.entity) return;
    const count = this.selectedBooks.size;
    this.writeProgressService.show(this.t.translate('book.browser.loading.unshelving', {count}));

    this.bookService.updateBookShelves(this.selectedBooks, new Set(), new Set([this.entity.id!]))
      .subscribe({
        next: () => {
          this.writeProgressService.complete(this.t.translate('book.browser.toast.unshelveSuccessDetail'));
          this.messageService.add({severity: 'info', summary: this.t.translate('common.success'), detail: this.t.translate('book.browser.toast.unshelveSuccessDetail')});
          void this.bookService.refreshBooks().subscribe();
          this.bookSelectionService.deselectAll();
        },
        error: () => {
          this.writeProgressService.fail(this.t.translate('book.browser.toast.unshelveFailedDetail'));
        }
      });
  }

  releaseFromStaging(): void {
    if (this.entityType !== EntityType.STAGING || this.selectedBooks.size === 0) {
      return;
    }
    const idsToRelease = new Set(this.selectedBooks);
    if (this.hasHiddenSelections) {
      this.confirmationService.confirm({
        message: this.t.translate('book.browser.confirm.releaseHiddenMessage', {
          count: idsToRelease.size,
          hiddenCount: this.hiddenSelectedCount,
        }),
        header: this.t.translate('book.browser.confirm.releaseHiddenHeader'),
        icon: 'pi pi-exclamation-triangle',
        acceptLabel: this.t.translate('book.browser.labels.release'),
        rejectLabel: this.t.translate('common.cancel'),
        acceptButtonProps: {
          label: this.t.translate('book.browser.labels.release'),
          severity: 'warn',
        },
        rejectButtonProps: {
          label: this.t.translate('common.cancel'),
          severity: 'secondary',
        },
        accept: () => this.executeReleaseFromStaging(idsToRelease),
      });
      return;
    }

    this.executeReleaseFromStaging(idsToRelease);
  }

  private executeReleaseFromStaging(idsToRelease: Set<number>): void {
    const count = idsToRelease.size;
    this.writeProgressService.show(this.t.translate('book.browser.loading.releasingFromStaging', {count}));

    this.bookService.releaseFromStaging(idsToRelease)
      .subscribe({
        next: () => {
          this.writeProgressService.complete(this.t.translate('book.browser.toast.releaseFromStagingSuccessDetail'));
          this.messageService.add({
            severity: 'info',
            summary: this.t.translate('common.success'),
            detail: this.t.translate('book.browser.toast.releaseFromStagingSuccessDetail'),
          });
          this.bookSelectionService.deselectAll();
          this.refreshStagingTriage();
          this.rebuildStagingBookState();
        },
        error: () => {
          this.writeProgressService.fail(this.t.translate('book.browser.toast.releaseFromStagingFailedDetail'));
          // Sync UI in case an earlier 500-ID chunk succeeded before the error.
          this.refreshStagingTriage();
          this.rebuildStagingBookState();
        }
      });
  }

  openShelfAssigner(): void {
    this.dynamicDialogRef = this.dialogHelperService.openShelfAssignerDialog(null, this.selectedBooks);
    if (this.dynamicDialogRef) {
      this.dynamicDialogRef.onClose.subscribe(result => {
        if (result && result.assigned) {
          this.bookSelectionService.deselectAll();
        }
      });
    }
  }

  openMoveShelfAssigner(): void {
    if (this.entityType !== EntityType.SHELF || !this.entity?.id) {
      return;
    }
    this.dynamicDialogRef = this.dialogHelperService.openShelfAssignerDialog(null, this.selectedBooks, this.entity.id);
    if (this.dynamicDialogRef) {
      this.dynamicDialogRef.onClose.subscribe(result => {
        if (result && result.assigned) {
          this.bookSelectionService.deselectAll();
        }
      });
    }
  }

  openBookTypeAssigner(): void {
    this.dynamicDialogRef = this.dialogHelperService.openBookTypeAssignerDialog(null, this.selectedBooks);
    if (this.dynamicDialogRef) {
      this.dynamicDialogRef.onClose.subscribe(result => {
        if (result.assigned) {
          this.bookSelectionService.deselectAll();
        }
      });
    }
  }

  openStoryArcAssigner(): void {
    this.dynamicDialogRef = this.dialogHelperService.openStoryArcAssignerDialog(this.selectedBooks);
    if (this.dynamicDialogRef) {
      this.dynamicDialogRef.onClose.subscribe(result => {
        if (result && result.assigned) {
          this.bookSelectionService.deselectAll();
        }
      });
    }
  }

  lockUnlockMetadata(): void {
    this.dynamicDialogRef = this.dialogHelperService.openLockUnlockMetadataDialog(this.selectedBooks);
    if (this.dynamicDialogRef) {
      this.dynamicDialogRef.onClose.subscribe(() => {
        this.bookSelectionService.deselectAll();
      });
    }
  }

  autoFetchMetadata(): void {
    if (!this.selectedBooks || this.selectedBooks.size === 0) return;

    const count = this.selectedBooks.size;
    this.confirmationService.confirm({
      message: this.t.translate('book.menuService.confirm.autoFetchMetadataMessage', {count}),
      header: this.t.translate('book.menuService.confirm.autoFetchMetadataHeader'),
      icon: 'pi pi-cloud-download',
      acceptLabel: this.t.translate('common.confirm'),
      rejectLabel: this.t.translate('common.cancel'),
      acceptButtonProps: {
        label: this.t.translate('common.confirm'),
        severity: 'info'
      },
      rejectButtonProps: {
        label: this.t.translate('common.cancel'),
        severity: 'secondary'
      },
      accept: () => {
        this.taskHelperService.refreshMetadataTask({
          refreshType: MetadataRefreshType.BOOKS,
          bookIds: Array.from(this.selectedBooks),
        }).subscribe(result => {
          if (result.success) {
            this.deselectAllBooks();
          }
        });
      }
    });
  }

  isbnDiscoveryFill(): void {
    if (!this.selectedBooks || this.selectedBooks.size === 0 || this.mobileUx.isPhone) {
      return;
    }

    const bookIds = Array.from(this.selectedBooks);
    const dialogRef = this.dialogLauncherService.openIsbnDiscoveryDialog(
      bookIds.length,
      this.appSettingsService.getEnabledMetadataProviderNames()
    );
    dialogRef?.onClose
      .pipe(take(1), takeUntil(this.destroy$))
      .subscribe((providers: string[] | undefined) => {
        if (!providers?.length) {
          return;
        }
        this.taskHelperService.isbnDiscoveryTask(bookIds, providers).subscribe(result => {
          if (result.success) {
            this.deselectAllBooks();
          }
        });
      });
  }

  fetchMetadata(): void {
    this.dialogHelperService.openMetadataRefreshDialog(this.selectedBooks);
  }

  bulkEditMetadata(): void {
    this.dynamicDialogRef = this.dialogHelperService.openBulkMetadataEditDialog(this.selectedBooks);
    if (this.dynamicDialogRef) {
      this.dynamicDialogRef.onClose.subscribe(() => {
        this.bookSelectionService.deselectAll();
      });
    }
  }

  multiBookEditMetadata(): void {
    this.dynamicDialogRef = this.dialogHelperService.openMultibookMetadataEditorDialog(this.selectedBooks);
    if (this.dynamicDialogRef) {
      this.dynamicDialogRef.onClose.subscribe(() => {
        this.bookSelectionService.deselectAll();
      });
    }
  }

  wipeSelectedMetadata(): void {
    if (!this.selectedBooks || this.selectedBooks.size === 0) return;

    const count = this.selectedBooks.size;
    this.confirmationService.confirm({
      message: this.t.translate('book.browser.confirm.wipeMetadataMessage', {count}),
      header: this.t.translate('book.browser.confirm.wipeMetadataHeader'),
      icon: 'pi pi-database',
      acceptLabel: this.t.translate('common.delete'),
      rejectLabel: this.t.translate('common.cancel'),
      acceptButtonProps: {
        label: this.t.translate('common.delete'),
        severity: 'danger'
      },
      rejectButtonProps: {
        label: this.t.translate('common.cancel'),
        severity: 'secondary'
      },
      accept: () => {
        this.writeProgressService.show(this.t.translate('book.browser.loading.wipingMetadata', {count}));
        this.bookMetadataManageService.wipeBooksMetadata(Array.from(this.selectedBooks)).subscribe({
          next: () => {
            this.writeProgressService.complete(this.t.translate('book.browser.toast.wipeMetadataSuccessDetail', {count}));
            this.messageService.add({
              severity: 'success',
              summary: this.t.translate('common.success'),
              detail: this.t.translate('book.browser.toast.wipeMetadataSuccessDetail', {count})
            });
            this.bookSelectionService.deselectAll();
          },
          error: () => {
            this.writeProgressService.fail(this.t.translate('book.browser.toast.wipeMetadataFailedDetail'));
          }
        });
      }
    });
  }

  restoreTitlesFromFilenamesForSelected(): void {
    if (!this.selectedBooks || this.selectedBooks.size === 0) return;

    const count = this.selectedBooks.size;
    this.confirmationService.confirm({
      message: this.t.translate('book.browser.confirm.restoreTitlesFromFilenamesMessage', {count}),
      header: this.t.translate('book.browser.confirm.restoreTitlesFromFilenamesHeader'),
      icon: 'pi pi-file-edit',
      acceptLabel: this.t.translate('common.yes'),
      rejectLabel: this.t.translate('common.no'),
      acceptButtonProps: {
        label: this.t.translate('common.yes'),
        severity: 'success'
      },
      rejectButtonProps: {
        label: this.t.translate('common.no'),
        severity: 'secondary'
      },
      accept: () => {
        this.writeProgressService.show(this.t.translate('book.browser.loading.restoringTitlesFromFilenames', {count}));
        this.bookMetadataManageService.restoreTitlesFromFilenames(Array.from(this.selectedBooks)).subscribe({
          next: (updatedCount) => {
            if (updatedCount > 0) {
              this.writeProgressService.complete(this.t.translate('book.browser.toast.restoreTitlesFromFilenamesSuccessDetail', {updatedCount, count}));
              this.messageService.add({
                severity: 'success',
                summary: this.t.translate('book.browser.toast.restoreTitlesFromFilenamesSuccessSummary'),
                detail: this.t.translate('book.browser.toast.restoreTitlesFromFilenamesSuccessDetail', {updatedCount, count})
              });
            } else {
              this.writeProgressService.complete(this.t.translate('book.browser.toast.restoreTitlesFromFilenamesNoEligibleDetail'));
              this.messageService.add({
                severity: 'info',
                summary: this.t.translate('book.browser.toast.restoreTitlesFromFilenamesNoEligibleSummary'),
                detail: this.t.translate('book.browser.toast.restoreTitlesFromFilenamesNoEligibleDetail')
              });
            }
            this.bookSelectionService.deselectAll();
          },
          error: () => {
            this.writeProgressService.fail(this.t.translate('book.browser.toast.restoreTitlesFromFilenamesFailedDetail'));
          }
        });
      }
    });
  }

  regenerateCoversForSelected(): void {
    if (!this.selectedBooks || this.selectedBooks.size === 0) return;
    const count = this.selectedBooks.size;
    this.confirmationService.confirm({
      message: this.t.translate('book.browser.confirm.regenCoverMessage', {count}),
      header: this.t.translate('book.browser.confirm.regenCoverHeader'),
      icon: 'pi pi-image',
      acceptLabel: this.t.translate('common.yes'),
      rejectLabel: this.t.translate('common.no'),
      acceptButtonProps: {
        label: this.t.translate('common.yes'),
        severity: 'success'
      },
      rejectButtonProps: {
        label: this.t.translate('common.no'),
        severity: 'secondary'
      },
      accept: () => {
        this.bookMetadataManageService.regenerateCoversForBooks(Array.from(this.selectedBooks)).subscribe({
          next: () => {
            this.messageService.add({
              severity: 'success',
              summary: this.t.translate('book.browser.toast.regenCoverStartedSummary'),
              detail: this.t.translate('book.browser.toast.regenCoverStartedDetail', {count}),
              life: 3000
            });
            this.deselectAllBooks();
          },
          error: () => {
            this.toastError(this.t.translate('book.browser.toast.failedSummary'), this.t.translate('book.browser.toast.regenCoverFailedDetail'));
          }
        });
      }
    });
  }

  generateCustomCoversForSelected(): void {
    if (!this.selectedBooks || this.selectedBooks.size === 0) return;
    const count = this.selectedBooks.size;
    this.confirmationService.confirm({
      message: this.t.translate('book.browser.confirm.customCoverMessage', {count}),
      header: this.t.translate('book.browser.confirm.customCoverHeader'),
      icon: 'pi pi-palette',
      acceptLabel: this.t.translate('common.yes'),
      rejectLabel: this.t.translate('common.no'),
      acceptButtonProps: {
        label: this.t.translate('common.yes'),
        severity: 'success'
      },
      rejectButtonProps: {
        label: this.t.translate('common.no'),
        severity: 'secondary'
      },
      accept: () => {
        this.bookMetadataManageService.generateCustomCoversForBooks(Array.from(this.selectedBooks)).subscribe({
          next: () => {
            this.messageService.add({
              severity: 'success',
              summary: this.t.translate('book.browser.toast.customCoverStartedSummary'),
              detail: this.t.translate('book.browser.toast.customCoverStartedDetail', {count}),
              life: 3000
            });
            this.deselectAllBooks();
          },
          error: () => {
            this.toastError(this.t.translate('book.browser.toast.failedSummary'), this.t.translate('book.browser.toast.customCoverFailedDetail'));
          }
        });
      }
    });
  }

  moveFiles(): void {
    this.dialogHelperService.openFileMoverDialog(this.selectedBooks);
  }

  markSelectedForAiSearch(): void {
    const selectedBookIds = Array.from(this.selectedBooks);
    if (selectedBookIds.length === 0) return;

    this.appSettingsService.markForAiSearch(selectedBookIds, true).subscribe({
      next: () => {
        this.messageService.add({
          severity: 'success',
          summary: 'Marked for AI Search',
          detail: `Successfully marked ${selectedBookIds.length} books for AI Search embedding.`,
          life: 3000
        });
        this.deselectAllBooks();
        this.bookService.refreshBooks();
      },
      error: () => {
        this.toastError('Error', 'Failed to mark books for AI Search.');
      }
    });
  }

  embedAllMarkedNow(): void {
    this.appSettingsService.scanMarkedAiSearchData().subscribe({
      next: () => {
        this.messageService.add({
          severity: 'success',
          summary: 'Embedding Started',
          detail: 'Started embedding marked books.',
          life: 3000
        });
        this.bookService.refreshBooks().subscribe();
      },
      error: () => {
        this.toastError('Error', 'Failed to start embedding marked books.');
      }
    });
  }

  embedSelectedNow(): void {
    const selectedBookIds = Array.from(this.selectedBooks);
    if (selectedBookIds.length === 0) return;

    this.appSettingsService.markForAiSearch(selectedBookIds, true).subscribe({
      next: () => {
        this.appSettingsService.scanMarkedAiSearchData().subscribe({
          next: () => {
            this.messageService.add({
              severity: 'success',
              summary: 'Embedding Started',
              detail: `Started embedding ${selectedBookIds.length} books.`,
              life: 3000
            });
            this.deselectAllBooks();
            this.bookService.refreshBooks();
          },
          error: () => {
            this.toastError('Error', 'Failed to start embedding marked books.');
          }
        });
      },
      error: () => {
        this.toastError('Error', 'Failed to mark books for AI Search.');
      }
    });
  }

  deleteSelectedEmbeddings(): void {
    const selectedBookIds = Array.from(this.selectedBooks);
    if (selectedBookIds.length === 0) return;

    this.confirmationService.confirm({
      message: `Are you sure you want to delete AI Search embeddings for ${selectedBookIds.length} book(s)? This will remove all embedded text data and the books will need to be re-embedded to use AI Search.`,
      header: 'Delete AI Search Embeddings',
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: 'Delete',
      rejectLabel: 'Cancel',
      acceptButtonStyleClass: 'p-button-danger',
      accept: () => {
        this.appSettingsService.deleteAiSearchEmbeddings(selectedBookIds).subscribe({
          next: (result) => {
            this.messageService.add({
              severity: 'success',
              summary: 'Embeddings Deleted',
              detail: `Successfully deleted ${result.deletedCount} embedding records across ${selectedBookIds.length} books.`,
              life: 3000
            });
            this.deselectAllBooks();
            this.bookService.refreshBooks();
          },
          error: () => {
            this.toastError('Error', 'Failed to delete embeddings.');
          }
        });
      }
    });
  }


  attachFilesToBook(): void {
    const currentState = this.bookService.getCurrentBookState();
    const selectedBookIds = Array.from(this.selectedBooks);
    const sourceBooks = (currentState.books || []).filter(book =>
      selectedBookIds.includes(book.id)
    );

    if (sourceBooks.length === 0) {
      this.messageService.add({
        severity: 'warn',
        summary: this.t.translate('book.browser.toast.noEligibleBooksSummary'),
        detail: this.t.translate('book.browser.toast.noEligibleBooksDetail')
      });
      return;
    }

    const libraryIds = new Set(sourceBooks.map(b => b.libraryId));
    if (libraryIds.size > 1) {
      this.messageService.add({
        severity: 'warn',
        summary: this.t.translate('book.browser.toast.multipleLibrariesSummary'),
        detail: this.t.translate('book.browser.toast.multipleLibrariesDetail')
      });
      return;
    }

    this.dynamicDialogRef = this.dialogHelperService.openBulkBookFileAttacherDialog(sourceBooks);
    if (this.dynamicDialogRef) {
      this.dynamicDialogRef.onClose.subscribe(result => {
        if (result?.success) {
          this.bookSelectionService.deselectAll();
        }
      });
    }
  }

  canAttachFiles(): boolean {
    if (this.selectedBooks.size === 0) return false;

    const currentState = this.bookService.getCurrentBookState();
    const selectedBookIds = Array.from(this.selectedBooks);
    const selectedBooks = (currentState.books || []).filter(book =>
      selectedBookIds.includes(book.id)
    );

    if (selectedBooks.length === 0) return false;

    const libraryIds = new Set(selectedBooks.map(b => b.libraryId));
    return libraryIds.size === 1;
  }

  user() {
    return this.userService.getCurrentUser();
  }

  private updateSeriesCollapseContext(): void {
    let type: 'LIBRARY' | 'SHELF' | 'MAGIC_SHELF' | null = null;
    let id: number | null = null;

    if (this.entity && this.entityType) {
      switch (this.entityType) {
        case EntityType.LIBRARY:
          type = 'LIBRARY';
          id = this.entity.id ?? 0;
          break;
        case EntityType.SHELF:
          type = 'SHELF';
          id = this.entity.id ?? 0;
          break;
        case EntityType.MAGIC_SHELF:
          type = 'MAGIC_SHELF';
          id = this.entity.id ?? 0;
          break;
      }
    }

    this.seriesCollapseFilter.setContext(type, id);
    this.bookCardOverlayPreferenceService.setContext(type, id);
  }

  private applyBookFilters(bookState: BookState): Observable<BookState> {
    const forceExpandSeries = this.filterOrchestrationService.shouldForceExpandSeries(
      this.activatedRoute.snapshot.queryParamMap
    );

    const primarySort: SortOption = this.bookSorter.selectedSort ?? {
      field: 'addedOn',
      direction: SortDirection.DESCENDING,
      label: 'Added On'
    };

    return this.filterOrchestrationService.applyFilters(
      bookState,
      this.headerFilter,
      this.sideBarFilter,
      this.seriesCollapseFilter,
      forceExpandSeries,
      primarySort
    ).pipe(
      map(filtered => this.applyStagingTriageToState(filtered))
    );
  }

  /**
   * Exclusive Staging triage filter. Safe no-op outside Staging.
   * Staging browse uses legacy full-state so these IDs can filter the whole inbox.
   */
  private applyStagingTriageToState(bookState: BookState): BookState {
    if (this.entityType !== EntityType.STAGING) {
      return bookState;
    }

    return {
      ...bookState,
      books: filterBooksByStagingTriage(
        bookState.books,
        this.stagingTriageMode,
        this.stagingInboxBookIds,
        this.stagingCompletedBookIds,
        this.pendingReviewBookIds,
      ),
    };
  }

  private syncActiveDirectoryFilter(): void {
    const scopeKey = this.directoryFilterService.getScopeKeyFromUrl(this.router.url);
    const scopedFilter = this.directoryFilterService.getScopedFilter(scopeKey);
    this.activeDirFilterPath = scopedFilter ? scopedFilter.fileSubPath : null;
  }

  private resetDirectoryScopeForCurrentRoute(): void {
    const scopeKey = this.directoryFilterService.getScopeKeyFromUrl(this.router.url);
    this.directoryFilterService.clearScope(scopeKey);
  }

  resetDirectoryScope(): void {
    if (!isDirectoryScopeActive(this.activeDirFilterPath)) {
      return;
    }

    this.resetDirectoryScopeForCurrentRoute();
  }

  private createGridViewportContext(sortCriteria: SortOption[]): GridViewportContext {
    return buildGridViewportContext({
      viewMode: this.currentViewMode,
      entityType: this.entityType,
      sortCriteria,
      filterMode: this.selectedFilterMode.getValue(),
      searchTerm: this.searchTerm$.getValue(),
      activeDirFilterPath: this.activeDirFilterPath,
      filterSignature: JSON.stringify(this.parsedFilters),
    });
  }

  setMobileColumns(columns: number): void {
    this.mobileColumnCount = columns;
    this.localStorageService.set(this.MOBILE_COLUMNS_STORAGE_KEY, columns);
    this.cardWidthSig.set(this.currentCardSize.width);
    this.cardHeightSig.set(this.getUniformCardHeight());
    queueMicrotask(() => this.updateVirtualGridDomBindings());
  }

  setMobileTitleRows(rows: number): void {
    this.mobileTitleRows = Math.min(3, Math.max(1, rows));
    this.localStorageService.set(this.MOBILE_TITLE_ROWS_STORAGE_KEY, this.mobileTitleRows);
    this.cardHeightSig.set(this.getUniformCardHeight());
    queueMicrotask(() => this.updateVirtualGridDomBindings());
  }

  setMobileScale(scale: number): void {
    this.mobileScaleFactor = Math.round(scale * 100) / 100;
    this.localStorageService.set(this.MOBILE_SCALE_STORAGE_KEY, this.mobileScaleFactor);
    this.cardWidthSig.set(this.currentCardSize.width);
    this.cardHeightSig.set(this.getUniformCardHeight());
    queueMicrotask(() => this.updateVirtualGridDomBindings());
  }

  increaseMobileScale(): void {
    const next = Math.min(this.MOBILE_SCALE_MAX, this.mobileScaleFactor + this.MOBILE_SCALE_STEP);
    this.setMobileScale(next);
  }

  decreaseMobileScale(): void {
    const next = Math.max(this.MOBILE_SCALE_MIN, this.mobileScaleFactor - this.MOBILE_SCALE_STEP);
    this.setMobileScale(next);
  }

  setDesktopTitleRows(rows: number): void {
    this.desktopTitleRows = Math.min(5, Math.max(1, rows));
    this.localStorageService.set(this.DESKTOP_TITLE_ROWS_STORAGE_KEY, this.desktopTitleRows);
    this.cardHeightSig.set(this.getUniformCardHeight());
    queueMicrotask(() => this.updateVirtualGridDomBindings());
  }

  private loadMobileColumnsPreference(): void {
    const saved = this.localStorageService.get<number>(this.MOBILE_COLUMNS_STORAGE_KEY);
    if (saved !== null && [2, 3, 4].includes(saved)) {
      this.mobileColumnCount = saved;
    }
  }

  private loadMobileScalePreference(): void {
    const saved = this.localStorageService.get<number>(this.MOBILE_SCALE_STORAGE_KEY);
    if (saved !== null && !isNaN(saved) && saved >= this.MOBILE_SCALE_MIN && saved <= this.MOBILE_SCALE_MAX) {
      this.mobileScaleFactor = saved;
    }
  }

  private loadTitleRowsPreference(): void {
    const savedMobileRows = this.localStorageService.get<number>(this.MOBILE_TITLE_ROWS_STORAGE_KEY);
    if (savedMobileRows !== null && [1, 2, 3].includes(savedMobileRows)) {
      this.mobileTitleRows = savedMobileRows;
    }

    const savedDesktopRows = this.localStorageService.get<number>(this.DESKTOP_TITLE_ROWS_STORAGE_KEY);
    if (savedDesktopRows !== null && [1, 2, 3, 4, 5].includes(savedDesktopRows)) {
      this.desktopTitleRows = savedDesktopRows;
    }

    // Field-init cardHeightSig runs before preferences load; sync after restore.
    this.cardHeightSig.set(this.getUniformCardHeight());
  }

  private loadSubtitlePreference(): void {
    const saved = this.localStorageService.get<boolean>(this.SHOW_SUBTITLES_STORAGE_KEY);
    if (typeof saved === 'boolean') {
      this.showSubtitles = saved;
    }
  }

  private getPreviewBook(): Book | undefined {
    if (!this.selectedCoverUrl) {
      return undefined;
    }

    return this.bookService.getCurrentBookState().books?.find(book =>
      this.urlHelper.getCoverUrl(book.id, book.metadata?.coverUpdatedOn) === this.selectedCoverUrl
    );
  }

  private getDisplayTitle(book: Book): string {
    const fileName = book.fileName?.trim() || book.primaryFile?.fileName?.trim() || '';
    if (this.isDirectoryScopedView) {
      return fileName;
    }

    const title = book.metadata?.title?.trim() || '';
    const subtitle = book.metadata?.subtitle?.trim() || '';

    if (this.showSubtitles && title && subtitle) {
      return `${title} : ${subtitle}`;
    }

    return title || fileName;
  }

  private getMobileTitleBarHeight(): number {
    return this.MOBILE_TITLE_BAR_HEIGHT + (this.mobileTitleRows - 1) * 16;
  }
}
