import {AfterViewInit, ApplicationRef, ChangeDetectionStrategy, ChangeDetectorRef, Component, ElementRef, EventEmitter, inject, Injector, Input, OnChanges, OnDestroy, OnInit, Output, SimpleChanges, ViewChild} from '@angular/core';
import {CdkPortal, DomPortalOutlet, PortalModule} from '@angular/cdk/portal';
import {TooltipModule} from "primeng/tooltip";
import {AdditionalFile, Book, BookType, IsbnDiscoveryStatus, ReadStatus} from '../../../model/book.model';
import {Button} from 'primeng/button';
import {MenuModule} from 'primeng/menu';
import {ConfirmationService, MenuItem, MessageService} from 'primeng/api';
import {BookService, RemoveFromLibraryMode} from '../../../service/book.service';
import {BookFileService} from '../../../service/book-file.service';
import {BookMetadataManageService} from '../../../service/book-metadata-manage.service';
import {CheckboxChangeEvent, CheckboxModule} from 'primeng/checkbox';
import {FormsModule} from '@angular/forms';
import {MetadataRefreshType} from '../../../../metadata/model/request/metadata-refresh-type.enum';
import {UrlHelperService} from '../../../../../shared/service/url-helper.service';
import {NgClass} from '@angular/common';
import {User, UserService} from '../../../../settings/user-management/user.service';
import {filter, Subject, Subscription} from 'rxjs';
import {EmailService} from '../../../../settings/email-v2/email.service';
import {TieredMenu} from 'primeng/tieredmenu';
import {Router} from '@angular/router';
import {RouterLink} from '@angular/router';
import {ProgressBar} from 'primeng/progressbar';
import {take, takeUntil} from 'rxjs/operators';
import {readStatusLabels} from '../book-filter/book-filter.config';
import {ResetProgressTypes} from '../../../../../shared/constants/reset-progress-type';
import {ReadStatusHelper} from '../../../helpers/read-status.helper';
import {BookDialogHelperService} from '../book-dialog-helper.service';
import {TaskHelperService} from '../../../../settings/task-management/task-helper.service';
import {BookNavigationService} from '../../../service/book-navigation.service';
import {BookCardOverlayPreferenceService} from '../book-card-overlay-preference.service';
import {AppSettingsService} from '../../../../../shared/service/app-settings.service';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {MobileBackHandle, MobileBackNavigationService} from '../../../../../shared/service/mobile-back-navigation.service';
import {MobileUxService} from '../../../../../core/services/mobile-ux.service';
import {FailureNotificationService} from '../../../../../shared/service/failure-notification.service';
import {DialogLauncherService} from '../../../../../shared/services/dialog-launcher.service';

import {AiSearchDialogService} from '../../ai-search-dialog/ai-search-dialog.component';
import {BookSelectionService} from '../book-selection.service';

@Component({
  selector: 'app-book-card',
  templateUrl: './book-card.component.html',
  styleUrls: ['./book-card.component.scss'],
  imports: [Button, MenuModule, CheckboxModule, FormsModule, NgClass, TieredMenu, ProgressBar, TooltipModule, RouterLink, TranslocoPipe, PortalModule],
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class BookCardComponent implements OnInit, OnChanges, AfterViewInit, OnDestroy {
  private readonly MOBILE_BREAKPOINT = 767;
  private readonly MOBILE_LONG_EDGE_MAX_PX = 1200;
  private readonly VIEWER_SWIPE_THRESHOLD_PX = 48;
  // TieredMenu computes final z-index as baseZIndex + PrimeNG menu z-index.
  readonly inlineMobileMenuBaseZIndex = 1500;
  private bodyScrollLockState: {
    bodyOverflow: string;
    bodyTouchAction: string;
    htmlOverflow: string;
    htmlTouchAction: string;
  } | null = null;
  private previewPortalHost: HTMLElement | null = null;
  private previewPortalOutlet: DomPortalOutlet | null = null;
  private inlineMobilePreviewBackHandle: MobileBackHandle | null = null;
  private readonly mobileBackNavigation = inject(MobileBackNavigationService);
  private readonly mobileUx = inject(MobileUxService);
  private readonly hostEl = inject(ElementRef<HTMLElement>);

  @Output() bookClicked = new EventEmitter<Book>();
  @Output() bookHoverEnded = new EventEmitter<number>();
  @Output() checkboxClick = new EventEmitter<{ index: number; book: Book; selected: boolean; shiftKey: boolean }>();
  @Output() menuToggled = new EventEmitter<boolean>();
  @Output() titleAreaActivated = new EventEmitter<Book>();
  @Output() reviewRequested = new EventEmitter<Book>();

  @Input() index!: number;
  @Input() book!: Book;
  @Input() isCheckboxEnabled = false;
  @Input() isSelected = false;
  @Input() bottomBarHidden = false;
  @Input() seriesViewEnabled = false;
  @Input() isSeriesCollapsed = false;
  @Input() overlayPreferenceService?: BookCardOverlayPreferenceService;
  @Input() forceEbookMode = false;
  @Input() useSquareCovers = false;
  @Input() titleRows = 1;
  @Input() showSubtitle = false;
  @Input() forceFileNameTitle = false;
  @Input() titleAreaInteractive = false;
  @Input() mobileViewerBooksContext: Book[] | null = null;
  @Input() reviewActionEnabled = false;
  @Input() showStagingIsbnProblem = false;

  screenWidth = typeof window !== 'undefined' ? window.innerWidth : 1024;
  screenHeight = typeof window !== 'undefined' ? window.innerHeight : 768;
  inlineMobileViewerBooks: Book[] = [];
  inlineMobileViewerIndex = -1;

  private inlineViewerTouchStartX = 0;
  private touchStartX = 0;
  private touchStartY = 0;
  private touchHoldTimer: ReturnType<typeof setTimeout> | undefined;
  private touchLongPressTriggered = false;
  private inlineViewerTouchStartY = 0;
  private inlineViewerTouchMoved = false;

  @ViewChild('checkboxElem') checkboxElem!: ElementRef<HTMLInputElement>;
  @ViewChild('coverImg') private coverImgRef?: ElementRef<HTMLImageElement>;
  @ViewChild('menuTrigger', {read: ElementRef}) private menuTriggerRef?: ElementRef<HTMLElement>;
  @ViewChild('readStatusTrigger', {read: ElementRef}) private readStatusTriggerRef?: ElementRef<HTMLElement>;
  @ViewChild(CdkPortal) private previewPortal?: CdkPortal;

  items: MenuItem[] | undefined;
  readStatusMenuItems: MenuItem[] = [];
  isImageLoaded = false;
  isSubMenuLoading = false;
  private additionalFilesLoaded = false;

  private bookService = inject(BookService);
  private bookFileService = inject(BookFileService);
  private bookMetadataManageService = inject(BookMetadataManageService);
  private taskHelperService = inject(TaskHelperService);
  private userService = inject(UserService);
  private emailService = inject(EmailService);
  private messageService = inject(MessageService);
  private failureNotifications = inject(FailureNotificationService);
  private router = inject(Router);
  protected urlHelper = inject(UrlHelperService);
  private confirmationService = inject(ConfirmationService);
  private bookDialogHelperService = inject(BookDialogHelperService);
  private bookNavigationService = inject(BookNavigationService);
  private cdr = inject(ChangeDetectorRef);
  private appSettingsService = inject(AppSettingsService);
  private dialogLauncherService = inject(DialogLauncherService);
  private readonly t = inject(TranslocoService);
  private readonly appRef = inject(ApplicationRef);
  private readonly injector = inject(Injector);
  private aiSearchDialogService = inject(AiSearchDialogService);
  private bookSelectionService = inject(BookSelectionService);

  protected _progressPercentage: number | null = null;
  protected _koProgressPercentage: number | null = null;
  protected _koboProgressPercentage: number | null = null;
  protected _displayTitle: string | undefined = undefined;
  protected _isSeriesViewActive = false;
  protected _coverImageUrl = '';
  protected _readStatusIcon = '';
  protected _readStatusClass = '';
  protected _readStatusTooltip = '';
  protected _shouldShowStatusIcon = false;
  protected _seriesCountTooltip = '';
  protected _titleTooltip = '';
  protected _hasProgress = false;
  protected _isAudiobook = false;
  protected _progressTooltip = '';
  protected _isContinueReading = false;
  protected _readButtonIcon = 'pi pi-book';

  private metadataCenterViewMode: 'route' | 'dialog' = 'route';
  private destroy$ = new Subject<void>();
  protected readStatusHelper = inject(ReadStatusHelper);
  private user: User | null = null;
  private diskType = 'LOCAL';
  private allowFileDeletion = false;
  protected aiSearchEnabled = false;
  protected isbnDiscoveryEnabled = false;
  private menuInitialized = false;
  private menuContextBook: Book | null = null;
  private activeTieredMenu: TieredMenu | null = null;

  showBookTypePill = true;
  showAiPanelDataOverlay = true;
  showAiSearchDataOverlay = true;
  showIssueNumberOverlay = true;

  private overlayPrefSub?: Subscription;

  private resizeSub?: Subscription;

  ngOnInit(): void {
    this.computeAllMemoizedValues();
    this.resizeSub = this.mobileUx.screenWidth$.subscribe(width => {
      this.screenWidth = width;
    });
    this.resizeSub.add(this.mobileUx.screenHeight$.subscribe(height => {
      this.screenHeight = height;
      if (!this.isMobileInteractionMode && this.isInlineMobilePreviewOpen) {
        this.closeInlineMobilePreview();
      }
    }));
    this.userService.userState$
      .pipe(
        filter(userState => !!userState?.user && userState.loaded),
        take(1),
        takeUntil(this.destroy$)
      )
      .subscribe(userState => {
        this.user = userState.user;
        this.metadataCenterViewMode = userState.user?.userSettings?.metadataCenterViewMode ?? 'route';
      });

    this.appSettingsService.appSettings$
      .pipe(
        filter(settings => !!settings),
        takeUntil(this.destroy$)
      )
      .subscribe(settings => {
        this.diskType = settings?.diskType ?? 'LOCAL';
        this.allowFileDeletion = settings?.allowFileDeletion ?? false;
        this.aiSearchEnabled = settings?.aiSearchEnabled ?? false;
        const nextIsbnDiscoveryEnabled = settings?.isbnDiscoveryEnabled ?? false;
        const isbnDiscoveryChanged = this.isbnDiscoveryEnabled !== nextIsbnDiscoveryEnabled;
        this.isbnDiscoveryEnabled = nextIsbnDiscoveryEnabled;
        if (isbnDiscoveryChanged && this.menuInitialized && this.menuContextBook) {
          this.initMenu(this.menuContextBook);
        }
        this.cdr.markForCheck();
      });

    if (this.overlayPreferenceService) {
      this.overlayPrefSub = new Subscription();
      this.overlayPrefSub.add(this.overlayPreferenceService.showBookTypePill$.subscribe(val => {
        this.showBookTypePill = val;
        this.cdr.markForCheck();
      }));
      this.overlayPrefSub.add(this.overlayPreferenceService.showAiPanelData$.subscribe(val => {
        this.showAiPanelDataOverlay = val;
        this.cdr.markForCheck();
      }));
      this.overlayPrefSub.add(this.overlayPreferenceService.showAiSearchData$.subscribe(val => {
        this.showAiSearchDataOverlay = val;
        this.cdr.markForCheck();
      }));
      this.overlayPrefSub.add(this.overlayPreferenceService.showIssueNumber$.subscribe(val => {
        this.showIssueNumberOverlay = val;
        this.cdr.markForCheck();
      }));
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (
      changes['book']
      || changes['forceEbookMode']
      || changes['useSquareCovers']
      || changes['showSubtitle']
      || changes['forceFileNameTitle']
      || changes['seriesViewEnabled']
      || changes['isSeriesCollapsed']
    ) {
      this.computeAllMemoizedValues();
      if (this.menuContextBook?.id === this.book.id) {
        this.menuContextBook = this.book;
      }
      if (changes['book'] && !changes['book'].firstChange && this.menuInitialized) {
        this.additionalFilesLoaded = false;
        this.initMenu(this.getMenuContextBook());
      }
    }
  }

  private computeAllMemoizedValues(): void {
    this._progressPercentage = this.book.epubProgress?.percentage
      ?? this.book.pdfProgress?.percentage
      ?? this.book.cbxProgress?.percentage
      ?? null;

    this._koProgressPercentage = this.book.koreaderProgress?.percentage ?? null;
    this._koboProgressPercentage = this.book.koboProgress?.percentage ?? null;

    this._hasProgress = this._progressPercentage !== null || this._koProgressPercentage !== null || this._koboProgressPercentage !== null;

    this._isSeriesViewActive = this.seriesViewEnabled && !!this.book.seriesCount && this.book.seriesCount >= 1;
    this._displayTitle = this.resolveDisplayTitle();
    this._isAudiobook = this.book.primaryFile?.bookType === 'AUDIOBOOK' && !this.forceEbookMode;
    this._coverImageUrl = this._isAudiobook
      ? this.urlHelper.getAudiobookThumbnailUrl(this.book.id, this.book.metadata?.audiobookCoverUpdatedOn)
      : this.urlHelper.getThumbnailUrl(this.book.id, this.book.metadata?.coverUpdatedOn);

    this._readStatusIcon = this.readStatusHelper.getReadStatusIcon(this.book.readStatus);
    this._readStatusClass = this.readStatusHelper.getReadStatusClass(this.book.readStatus);
    this._readStatusTooltip = this.readStatusHelper.getReadStatusTooltip(this.book.readStatus);
    this._shouldShowStatusIcon = this.readStatusHelper.shouldShowStatusIcon(this.book.readStatus);

    this._seriesCountTooltip = this.t.translate('book.card.alt.seriesCollapsed', { count: this.book.seriesCount });
    this._titleTooltip = this.t.translate('book.card.alt.titleTooltip', { title: this._displayTitle });

    const progressParts: string[] = [];
    if (this._progressPercentage !== null) {
      progressParts.push(`${this._progressPercentage}% (Fable)`);
    }
    if (this._koProgressPercentage !== null) {
      progressParts.push(`${this._koProgressPercentage}% (KOReader)`);
    }
    if (this._koboProgressPercentage !== null) {
      progressParts.push(`${this._koboProgressPercentage}% (Kobo)`);
    }
    this._progressTooltip = progressParts.join(' | ');

    const maxProgress = Math.max(
      this._progressPercentage ?? 0,
      this._koProgressPercentage ?? 0,
      this._koboProgressPercentage ?? 0
    );
    this._isContinueReading = maxProgress > 0 && maxProgress < 100;

    if (this._isAudiobook) {
      this._readButtonIcon = this._isContinueReading ? 'pi pi-forward' : 'pi pi-play';
    } else {
      this._readButtonIcon = this._isContinueReading ? 'pi pi-forward' : 'pi pi-book';
    }
  }

  private resolveDisplayTitle(): string {
    const fileName = this.book.fileName?.trim() || this.book.primaryFile?.fileName?.trim() || '';
    if (this.forceFileNameTitle) {
      return fileName;
    }

    const collapsedSeriesName = this.isSeriesCollapsed ? this.book.metadata?.seriesName?.trim() : '';
    const metadataTitle = this.book.metadata?.title?.trim() || '';
    const metadataSubtitle = this.book.metadata?.subtitle?.trim() || '';

    if (!collapsedSeriesName && this.showSubtitle && metadataTitle && metadataSubtitle) {
      return `${metadataTitle} : ${metadataSubtitle}`;
    }

    return collapsedSeriesName || metadataTitle || fileName;
  }

  get hasProgress(): boolean {
    return this._hasProgress;
  }

  get seriesCountTooltip(): string {
    return this._seriesCountTooltip;
  }

  get titleTooltip(): string {
    return this._titleTooltip;
  }

  get readStatusTooltip(): string {
    return this._readStatusTooltip;
  }

  get progressTooltip(): string {
    return this._progressTooltip;
  }

  get readButtonIcon(): string {
    return this._readButtonIcon;
  }

  get displayTitle(): string | undefined {
    return this._displayTitle;
  }

  get coverImageUrl(): string {
    return this._coverImageUrl;
  }

  get normalizedTitleRows(): number {
    return Math.min(5, Math.max(1, this.titleRows || 1));
  }

  get isMobileInteractionMode(): boolean {
    const shortEdge = Math.min(this.screenWidth, this.screenHeight);
    const longEdge = Math.max(this.screenWidth, this.screenHeight);
    return shortEdge <= this.MOBILE_BREAKPOINT && longEdge <= this.MOBILE_LONG_EDGE_MAX_PX;
  }

  get isReviewActionEnabled(): boolean {
    return this.reviewActionEnabled && !this.mobileUx.isPhone;
  }

  get stagingIsbnProblemStatus(): IsbnDiscoveryStatus | null {
    if (!this.showStagingIsbnProblem || this.mobileUx.isPhone) {
      return null;
    }
    return this.book.isbnDiscoveryStatus === IsbnDiscoveryStatus.NOT_FOUND
      || this.book.isbnDiscoveryStatus === IsbnDiscoveryStatus.ERROR
      ? this.book.isbnDiscoveryStatus
      : null;
  }

  get stagingIsbnProblemTooltip(): string {
    const status = this.stagingIsbnProblemStatus;
    const titleKey = status === IsbnDiscoveryStatus.NOT_FOUND
      ? 'book.card.isbnDiscoveryStatus.notFound'
      : 'book.card.isbnDiscoveryStatus.error';
    const title = this.t.translate(titleKey);
    const detail = this.book.isbnDiscoveryDetail?.trim();
    const checkedAt = this.book.isbnDiscoveryCheckedAt
      ? new Date(this.book.isbnDiscoveryCheckedAt).toLocaleString()
      : null;
    return [title, detail, checkedAt].filter(Boolean).join(' — ');
  }

  onStagingIsbnProblemClick(event: Event): void {
    event.stopPropagation();
    const status = this.stagingIsbnProblemStatus;
    if (!status) {
      return;
    }
    this.messageService.add({
      severity: status === IsbnDiscoveryStatus.ERROR ? 'error' : 'warn',
      summary: this.t.translate(status === IsbnDiscoveryStatus.NOT_FOUND
        ? 'book.card.isbnDiscoveryStatus.notFound'
        : 'book.card.isbnDiscoveryStatus.error'),
      detail: this.stagingIsbnProblemTooltip,
      life: 5000,
    });
  }

  /**
   * Long-press range multiselect + selection-tap behavior.
   * Preserves the existing phone geometry path; also enables for tablet/desktop
   * layout chrome (including auto-shape on touch tablets). Does not change Phone Mode.
   */
  get isTouchRangeSelectEnabled(): boolean {
    if (this.isMobileInteractionMode) {
      return true;
    }
    return this.mobileUx.isTablet || this.mobileUx.isDesktop;
  }

  /**
   * Tablet/desktop with a touch digitizer: enable per-card focus-within chrome
   * and touch-driven cover preview. Phone Mode geometry is excluded.
   */
  get isTouchDigitizerChrome(): boolean {
    if (this.isMobileInteractionMode || this.mobileUx.isPhone) {
      return false;
    }
    return this.mobileUx.hasTouchInput && (this.mobileUx.isTablet || this.mobileUx.isDesktop);
  }

  /**
   * PrimeNG TieredMenu with autoDisplay=true opens submenus on mouseenter.
   * On desktop-touch (fine pointer + maxTouchPoints), that hover opens the
   * submenu and the synthesized click immediately treats it as selected and
   * collapses it — Delete / Metadata / More Actions never stay open.
   * Phone Mode keeps autoDisplay (≤960px uses accordion click path).
   */
  get tieredMenuAutoDisplay(): boolean {
    return !this.isTouchDigitizerChrome;
  }

  get shouldAutoMobileTitlePreview(): boolean {
    return this.isMobileInteractionMode;
  }

  get isTitleAreaInteractive(): boolean {
    return this.titleAreaInteractive || this.shouldAutoMobileTitlePreview;
  }

  get activeInlineMobileViewerBook(): Book | null {
    if (this.inlineMobileViewerIndex < 0 || this.inlineMobileViewerIndex >= this.inlineMobileViewerBooks.length) {
      return null;
    }

    return this.inlineMobileViewerBooks[this.inlineMobileViewerIndex] ?? null;
  }

  get isInlineMobilePreviewOpen(): boolean {
    return this.activeInlineMobileViewerBook !== null;
  }

  private buildReadStatusMenuItems(book: Book = this.getMenuContextBook()): void {
    this.readStatusMenuItems = Object.entries(readStatusLabels).map(([status, label]) => ({
      label,
      command: () => {
        this.bookService.updateBookReadStatus(book.id, status as ReadStatus).subscribe({
          next: () => {
            this.messageService.add({
              severity: 'success',
              summary: this.t.translate('book.card.toast.readStatusUpdatedSummary'),
              detail: this.t.translate('book.card.toast.readStatusUpdatedDetail', {label}),
              life: 2000
            });
          },
          error: () => {
            this.toastError(this.t.translate('book.card.toast.readStatusFailedSummary'), this.t.translate('book.card.toast.readStatusFailedDetail'), 3000);
          }
        });
      }
    }));
  }

  toggleReadStatusMenu(event: Event, menu: TieredMenu): void {
    event.stopPropagation();
    const menuBook = this.setMenuContextBook(this.book);
    if (this.readStatusMenuItems.length === 0) {
      this.buildReadStatusMenuItems(menuBook);
    }
    const wasVisible = menu.visible;
    this.hideActiveTieredMenu(menu);
    menu.toggle(this.getPopupAnchorEvent(event, this.readStatusTriggerRef?.nativeElement));
    this.syncActiveTieredMenu(menu, wasVisible);
  }

  ngAfterViewInit(): void {
    const img = this.coverImgRef?.nativeElement;
    if (img && img.complete && img.naturalWidth > 0) {
      this.isImageLoaded = true;
      this.cdr.markForCheck();
    }
  }

  onImageLoad(): void {
    this.isImageLoaded = true;
    this.cdr.markForCheck();
  }

  readBook(book: Book): void {
    if (this.forceEbookMode && book.primaryFile?.bookType === 'AUDIOBOOK') {
      const ebookType = this.getEbookType(book);
      if (ebookType) {
        this.bookService.readBook(book.id, undefined, ebookType);
        return;
      }
    }
    this.bookService.readBook(book.id);
  }

  openAiSearch(event: Event, book: Book): void {
    event.stopPropagation();
    event.preventDefault();
    this.aiSearchDialogService.open(book.id);
  }

  private getEbookType(book: Book): BookType | undefined {
    if (book.epubProgress) return 'EPUB';
    if (book.pdfProgress) return 'PDF';
    if (book.cbxProgress) return 'CBX';
    const alternativeFormat = book.alternativeFormats?.find(f =>
      f.bookType && ['EPUB', 'PDF', 'CBX', 'FB2', 'MOBI', 'AZW3'].includes(f.bookType)
    );
    return alternativeFormat?.bookType;
  }

  onMenuShow(): void {
    this.menuToggled.emit(true);
  }

  onMenuHide(): void {
    this.activeTieredMenu = null;
    this.menuToggled.emit(false);
  }

  onReadStatusMenuHide(menu: TieredMenu): void {
    if (this.activeTieredMenu === menu) {
      this.activeTieredMenu = null;
    }
  }

  onMenuToggle(event: Event, menu: TieredMenu): void {
    event.stopPropagation();
    const menuBook = this.setMenuContextBook(this.book);
    if (!this.menuInitialized) {
      this.menuInitialized = true;
      this.initMenu(menuBook);
      this.cdr.markForCheck();
    }

    const wasVisible = menu.visible;
    this.hideActiveTieredMenu(menu);
    menu.toggle(this.getPopupAnchorEvent(event, this.menuTriggerRef?.nativeElement));
    this.syncActiveTieredMenu(menu, wasVisible);

    if (!this.additionalFilesLoaded && !this.isSubMenuLoading && this.needsAdditionalFilesData(menuBook)) {
      this.isSubMenuLoading = true;
      this.cdr.markForCheck();
      this.bookService.getBookByIdFromAPI(menuBook.id, true).subscribe({
        next: (book) => {
          if (this.menuContextBook?.id !== menuBook.id) {
            return;
          }
          this.menuContextBook = book;
          this.additionalFilesLoaded = true;
          this.isSubMenuLoading = false;
          this.initMenu(book);
          this.cdr.markForCheck();
        },
        error: () => {
          this.isSubMenuLoading = false;
          this.cdr.markForCheck();
        }
      });
    }
  }

  onInlineViewerMenuToggle(event: Event, menu: TieredMenu, book: Book): void {
    event.stopPropagation();
    const menuBook = this.setMenuContextBook(book);
    if (!this.menuInitialized) {
      this.menuInitialized = true;
      this.initMenu(menuBook);
      this.cdr.markForCheck();
    }

    const wasVisible = menu.visible;
    this.hideActiveTieredMenu(menu);
    menu.toggle(event);
    this.syncActiveTieredMenu(menu, wasVisible);

    if (!this.additionalFilesLoaded && !this.isSubMenuLoading && this.needsAdditionalFilesData(menuBook)) {
      this.isSubMenuLoading = true;
      this.cdr.markForCheck();
      this.bookService.getBookByIdFromAPI(menuBook.id, true).subscribe({
        next: (loadedBook) => {
          if (this.menuContextBook?.id !== menuBook.id) {
            return;
          }
          this.menuContextBook = loadedBook;
          this.additionalFilesLoaded = true;
          this.isSubMenuLoading = false;
          this.initMenu(loadedBook);
          this.cdr.markForCheck();
        },
        error: () => {
          this.isSubMenuLoading = false;
          this.cdr.markForCheck();
        }
      });
    }
  }

  toggleInlineViewerReadStatusMenu(event: Event, menu: TieredMenu, book: Book): void {
    event.stopPropagation();
    const menuBook = this.setMenuContextBook(book);
    if (this.readStatusMenuItems.length === 0) {
      this.buildReadStatusMenuItems(menuBook);
    }
    const wasVisible = menu.visible;
    this.hideActiveTieredMenu(menu);
    menu.toggle(event);
    this.syncActiveTieredMenu(menu, wasVisible);
  }

  private hideActiveTieredMenu(exceptMenu?: TieredMenu): void {
    if (!this.activeTieredMenu || this.activeTieredMenu === exceptMenu) {
      return;
    }

    const activeMenu = this.activeTieredMenu;
    this.activeTieredMenu = null;

    if (!activeMenu.visible || typeof activeMenu.hide !== 'function') {
      return;
    }

    activeMenu.hide();
  }

  private syncActiveTieredMenu(menu: TieredMenu, wasVisible: boolean | undefined): void {
    if (typeof menu.hide !== 'function') {
      this.activeTieredMenu = null;
      return;
    }

    this.activeTieredMenu = wasVisible ? null : menu;
  }

  private needsAdditionalFilesData(book: Book): boolean {
    if (this.additionalFilesLoaded) {
      return false;
    }
    const hasNoAlternativeFormats = !book.alternativeFormats || book.alternativeFormats.length === 0;
    const hasNoSupplementaryFiles = !book.supplementaryFiles || book.supplementaryFiles.length === 0;
    const canDownload = !!this.user?.permissions.canDownload;
    const canDeleteBook = !!this.user?.permissions.canDeleteBook;
    return (canDownload || canDeleteBook) && hasNoAlternativeFormats && hasNoSupplementaryFiles;
  }

  private setMenuContextBook(book: Book): Book {
    if (this.menuContextBook?.id !== book.id) {
      this.additionalFilesLoaded = false;
      this.isSubMenuLoading = false;
      this.menuInitialized = false;
      this.items = undefined;
      this.readStatusMenuItems = [];
    }

    this.menuContextBook = book;
    return this.menuContextBook;
  }

  private getMenuContextBook(): Book {
    return this.menuContextBook ?? this.book;
  }

  private getPopupAnchorEvent(event: Event, preferredTarget?: HTMLElement): Event {
    const anchor = this.resolvePopupAnchorElement(preferredTarget)
      ?? this.resolvePopupAnchorElement(event.currentTarget)
      ?? this.resolvePopupAnchorElement(event.target)
      ?? this.resolvePopupAnchorFromPath(event);

    if (!anchor) {
      return event;
    }

    return {
      currentTarget: anchor,
      target: anchor,
      preventDefault: () => event.preventDefault(),
      stopPropagation: () => event.stopPropagation(),
    } as unknown as Event;
  }

  private resolvePopupAnchorElement(candidate: EventTarget | null | undefined): HTMLElement | null {
    if (!(candidate instanceof HTMLElement)) {
      return null;
    }

    if (candidate.matches('button, a, [role="button"], .p-button')) {
      return candidate;
    }

    return candidate.querySelector<HTMLElement>('button, a, [role="button"], .p-button');
  }

  private resolvePopupAnchorFromPath(event: Event): HTMLElement | null {
    if (typeof event.composedPath !== 'function') {
      return null;
    }

    for (const entry of event.composedPath()) {
      const anchor = this.resolvePopupAnchorElement(entry);
      if (anchor) {
        return anchor;
      }
    }

    return null;
  }

  private initMenu(book: Book = this.getMenuContextBook()) {
    const currentShelfId = this.getCurrentShelfId();
    this.items = [
      ...(currentShelfId !== null ? [{
        label: this.t.translate('book.card.menu.moveToShelf') || 'Move to Shelf...',
        icon: 'pi pi-folder-open',
        command: () => this.openMoveShelfDialog(book, currentShelfId)
      }] : []),
      {
        label: this.t.translate('book.card.menu.assignShelf'),
        icon: 'pi pi-folder',
        command: () => this.openShelfDialog(book)
      },
      {
        label: 'Assign Media Type',
        icon: 'pi pi-file',
        command: () => this.openBookTypeDialog(book)
      },
      {
        label: this.t.translate('book.card.menu.viewDetails'),
        icon: 'pi pi-info-circle',
        command: () => {
          setTimeout(() => {
            this.openBookInfo(book);
          }, 150);
        },
      },
      ...this.getPermissionBasedMenuItems(book),
      ...this.moreMenuItems(book),
    ];
  }

  private getPermissionBasedMenuItems(book: Book): MenuItem[] {
    const items: MenuItem[] = [];

    if (this.user?.permissions.canDownload) {
      const hasAdditionalFiles = (book.alternativeFormats && book.alternativeFormats.length > 0) ||
        (book.supplementaryFiles && book.supplementaryFiles.length > 0);

      if (hasAdditionalFiles) {
        const downloadItems = this.getDownloadMenuItems(book);
        items.push({
          label: this.t.translate('book.card.menu.download'),
          icon: 'pi pi-download',
          items: downloadItems
        });
      } else if (this.additionalFilesLoaded) {
        items.push({
          label: this.t.translate('book.card.menu.download'),
          icon: 'pi pi-download',
          command: () => {
            this.bookFileService.downloadFile(book);
          }
        });
      } else {
        items.push({
          label: this.t.translate('book.card.menu.download'),
          icon: this.isSubMenuLoading ? 'pi pi-spin pi-spinner' : 'pi pi-download',
          items: [{label: this.t.translate('book.card.menu.loading'), disabled: true}]
        });
      }
    }

    if (this.user?.permissions.canDeleteBook) {
      const hasAdditionalFiles = (book.alternativeFormats && book.alternativeFormats.length > 0) ||
        (book.supplementaryFiles && book.supplementaryFiles.length > 0);

      if (hasAdditionalFiles) {
        const deleteItems = this.getDeleteMenuItems(book);
        if (deleteItems.length > 0) {
          items.push({
            label: this.t.translate('book.card.menu.delete'),
            icon: 'pi pi-trash',
            items: deleteItems
          });
        }
      } else if (this.additionalFilesLoaded) {
        items.push({
          label: this.t.translate('book.card.menu.delete'),
          icon: 'pi pi-trash',
          items: [
            ...(!this.allowFileDeletion ? [] : [{
              label: this.t.translate('book.card.menu.deleteFromDisk'),
              icon: 'pi pi-trash',
              command: () => {
                this.confirmationService.confirm({
                  message: this.t.translate('book.card.confirm.deleteBookMessage', {title: book.metadata?.title}),
                  header: this.t.translate('book.card.confirm.deleteBookHeader'),
                  icon: 'pi pi-exclamation-triangle',
                  acceptIcon: 'pi pi-trash',
                  rejectIcon: 'pi pi-times',
                  acceptLabel: this.t.translate('common.delete'),
                  rejectLabel: this.t.translate('common.cancel'),
                  acceptButtonStyleClass: 'p-button-danger',
                  rejectButtonStyleClass: 'p-button-outlined',
                  accept: () => {
                    this.bookService.deleteBooks(new Set([book.id]), true).subscribe();
                  }
                });
              }
            }]),
            {
              label: this.t.translate('book.card.menu.removeFromLibrary'),
              icon: 'pi pi-minus-circle',
              items: this.getRemoveFromLibraryMenuItems(book)
            }
          ]
        });
      } else {
        items.push({
          label: this.t.translate('book.card.menu.delete'),
          icon: this.isSubMenuLoading ? 'pi pi-spin pi-spinner' : 'pi pi-trash',
          items: [{label: this.t.translate('book.card.menu.loading'), disabled: true}]
        });
      }
    }

    if (this.user?.permissions.canEmailBook) {
      items.push(
        {
          label: this.t.translate('book.card.menu.emailBook'),
          icon: 'pi pi-envelope',
          items: [{
            label: this.t.translate('book.card.menu.quickSend'),
            icon: 'pi pi-envelope',
            command: () => {
              const doSend = () => {
                this.emailService.emailBookQuick(book.id).subscribe({
                  next: () => {
                    this.messageService.add({
                      severity: 'info',
                      summary: this.t.translate('common.success'),
                      detail: this.t.translate('book.card.toast.quickSendSuccessDetail'),
                    });
                  },
                  error: (err) => {
                    const errorMessage = err?.error?.message || this.t.translate('book.card.toast.quickSendErrorDetail');
                    this.toastError(this.t.translate('common.error'), errorMessage);
                  },
                });
              };

              if (book.primaryFile?.fileSizeKb && book.primaryFile.fileSizeKb > 25 * 1024) {
                this.confirmationService.confirm({
                  message: this.t.translate('book.card.confirm.largeFileMessage'),
                  header: this.t.translate('book.card.confirm.largeFileHeader'),
                  icon: 'pi pi-exclamation-triangle',
                  acceptLabel: this.t.translate('book.card.confirm.sendAnyway'),
                  rejectLabel: this.t.translate('common.cancel'),
                  acceptButtonProps: { severity: 'warn' },
                  rejectButtonProps: { severity: 'secondary' },
                  accept: doSend,
                });
              } else {
                doSend();
              }
            }
          },
            {
              label: this.t.translate('book.card.menu.customSend'),
              icon: 'pi pi-envelope',
              command: () => {
                this.bookDialogHelperService.openCustomSendDialog(book);
              }
            }
          ]
        });
    }

    if (this.user?.permissions.canEditMetadata) {
      items.push({
        label: this.t.translate('book.card.menu.metadata'),
        icon: 'pi pi-database',
        items: [
          {
            label: this.t.translate('book.card.menu.searchMetadata'),
            icon: 'pi pi-sparkles',
            command: () => {
              setTimeout(() => {
                this.router.navigate(['/book', book.id], {
                  queryParams: {tab: 'match', returnTo: this.router.url}
                })
              }, 150);
            },
          },
          {
            label: this.t.translate('book.card.menu.autoFetch'),
            icon: 'pi pi-bolt',
            command: () => {
              this.confirmationService.confirm({
                message: this.t.translate('book.menuService.confirm.autoFetchMetadataMessage', {count: 1}),
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
                    bookIds: [book.id],
                  }).subscribe();
                }
              });
            }
          },
          ...(this.isbnDiscoveryEnabled && !this.mobileUx.isPhone ? [{
            label: this.t.translate('book.card.menu.isbnDiscovery'),
            icon: 'pi pi-barcode',
            command: () => {
              const dialogRef = this.dialogLauncherService.openIsbnDiscoveryDialog(
                1,
                this.appSettingsService.getEnabledMetadataProviderNames()
              );
              dialogRef?.onClose
                .pipe(take(1), takeUntil(this.destroy$))
                .subscribe((providers: string[] | undefined) => {
                  if (providers?.length) {
                    this.taskHelperService.isbnDiscoveryTask([book.id], providers).subscribe();
                  }
                });
            }
          }] : []),
          {
            label: this.t.translate('book.card.menu.customFetch'),
            icon: 'pi pi-sync',
            command: () => {
              this.bookDialogHelperService.openMetadataRefreshDialog(new Set([book.id]))
            },
          },
          {
            label: this.t.translate('book.card.menu.regenerateCover'),
            icon: 'pi pi-image',
            command: () => {
              this.bookMetadataManageService.regenerateCover(book.id).subscribe({
                next: () => this.messageService.add({
                  severity: 'success',
                  summary: this.t.translate('common.success'),
                  detail: this.t.translate('book.card.toast.coverRegenSuccessDetail')
                }),
                error: (err) => this.toastError(this.t.translate('common.error'), err?.error?.message || this.t.translate('book.card.toast.coverRegenFailedDetail'))
              });
            }
          },
          {
            label: this.t.translate('book.card.menu.generateCustomCover'),
            icon: 'pi pi-palette',
            command: () => {
              this.bookMetadataManageService.generateCustomCover(book.id).subscribe({
                next: () => this.messageService.add({
                  severity: 'success',
                  summary: this.t.translate('common.success'),
                  detail: this.t.translate('book.card.toast.customCoverSuccessDetail')
                }),
                error: (err) => this.toastError(this.t.translate('common.error'), err?.error?.message || this.t.translate('book.card.toast.customCoverFailedDetail'))
              });
            }
          }
        ]
      });
    }

    if (this.user?.permissions.canEditMetadata || this.user?.permissions.admin) {
      items.push(book.staged
        ? {
          label: this.t.translate('book.card.menu.releaseFromStaging'),
          icon: 'pi pi-check-circle',
          command: () => {
            this.bookService.releaseFromStaging(new Set([book.id])).subscribe({
              next: () => {
                this.messageService.add({
                  severity: 'success',
                  summary: this.t.translate('common.success'),
                  detail: this.t.translate('book.card.toast.releaseFromStagingSuccessDetail'),
                });
              },
              error: (err) => {
                this.toastError(this.t.translate('common.error'), err?.error?.message || this.t.translate('book.card.toast.releaseFromStagingFailedDetail'));
              }
            });
          }
        }
        : {
          label: this.t.translate('book.card.menu.sendToStaging'),
          icon: 'pi pi-inbox',
          command: () => {
            this.bookService.stageForReview(new Set([book.id])).subscribe({
              next: () => {
                this.messageService.add({
                  severity: 'success',
                  summary: this.t.translate('common.success'),
                  detail: this.t.translate('book.card.toast.sendToStagingSuccessDetail'),
                });
              },
              error: (err) => {
                this.toastError(this.t.translate('common.error'), err?.error?.message || this.t.translate('book.card.toast.sendToStagingFailedDetail'));
              }
            });
          }
        });
    }

    return items;
  }

  private moreMenuItems(book: Book): MenuItem[] {
    const items: MenuItem[] = [];
    const moreActions: MenuItem[] = [];

    if (this.user?.permissions.canMoveOrganizeFiles && this.diskType === 'LOCAL') {
      moreActions.push({
        label: this.t.translate('book.card.menu.organizeFile'),
        icon: 'pi pi-arrows-h',
        command: () => {
          this.bookDialogHelperService.openFileMoverDialog(new Set([book.id]));
        }
      });
    }

    if (this.aiSearchEnabled) {
      moreActions.push({
        label: this.t.translate('layout.topbar.aiSearch') || 'AI Search',
        icon: 'pi pi-sparkles',
        command: () => {
          this.aiSearchDialogService.open(book.id);
        }
      });
      moreActions.push({
        label: this.t.translate('book.card.menu.embedForAiSearch'),
        icon: 'pi pi-search',
        command: () => {
          this.appSettingsService.extractAndEmbedBook(book.id).subscribe({
            next: () => {
              this.messageService.add({
                severity: 'success',
                summary: this.t.translate('common.success'),
                detail: this.t.translate('book.card.toast.embedAiSearchSuccessDetail', {title: book.metadata?.title}),
              });
            },
            error: (err) => {
              this.toastError(this.t.translate('common.error'), err?.error?.message || this.t.translate('book.card.toast.embedAiSearchFailedDetail'));
            },
          });
        },
      });
      if (book.markedForAiSearch) {
        moreActions.push({
          label: this.t.translate('book.card.menu.unmarkForEmbedding') || 'Unmark for embedding',
          icon: 'pi pi-bookmark-fill',
          command: () => {
            this.appSettingsService.markForAiSearch([book.id], false).subscribe({
              next: () => {
                this.messageService.add({
                  severity: 'success',
                  summary: this.t.translate('common.success'),
                  detail: this.t.translate('book.card.toast.unmarkAiSearchSuccessDetail')
                });
              }
            });
          }
        });
      } else if (!book.hasAiSearchData) {
        moreActions.push({
          label: this.t.translate('book.card.menu.markForEmbedding') || 'Mark for embedding',
          icon: 'pi pi-bookmark',
          command: () => {
            this.appSettingsService.markForAiSearch([book.id], true).subscribe({
              next: () => {
                this.messageService.add({
                  severity: 'success',
                  summary: this.t.translate('common.success'),
                  detail: this.t.translate('book.card.toast.markAiSearchSuccessDetail')
                });
              }
            });
          }
        });
      }
      if (book.hasAiSearchData) {
        moreActions.push({
          label: this.t.translate('book.card.menu.deleteAiSearchEmbeddings') || 'Delete AI Search Embeddings',
          icon: 'pi pi-trash',
          command: () => {
            this.confirmationService.confirm({
              message: this.t.translate('book.card.confirm.deleteAiSearchEmbeddingsMessage', {title: book.metadata?.title}),
              header: this.t.translate('book.card.confirm.deleteAiSearchEmbeddingsHeader'),
              icon: 'pi pi-exclamation-triangle',
              acceptIcon: 'pi pi-trash',
              rejectIcon: 'pi pi-times',
              acceptLabel: this.t.translate('common.delete'),
              rejectLabel: this.t.translate('common.cancel'),
              acceptButtonStyleClass: 'p-button-danger',
              rejectButtonStyleClass: 'p-button-outlined',
              accept: () => {
                this.appSettingsService.deleteAiSearchEmbeddings([book.id]).subscribe({
                  next: () => {
                    this.messageService.add({
                      severity: 'success',
                      summary: this.t.translate('common.success'),
                      detail: this.t.translate('book.card.toast.deleteAiSearchEmbeddingsSuccessDetail', {title: book.metadata?.title})
                    });
                  },
                  error: (err) => {
                    this.toastError(this.t.translate('common.error'), err?.error?.message || this.t.translate('book.card.toast.deleteAiSearchEmbeddingsFailedDetail'));
                  }
                });
              }
            });
          }
        });
      }
    }

    moreActions.push(
      {
        label: this.t.translate('book.card.menu.currentlyReading'),
        icon: 'pi pi-bookmark',
        command: () => {
          this.toggleCurrentlyReading(book);
        }
      },
      {
        label: this.t.translate('book.card.menu.readStatus'),
        icon: 'pi pi-book',
        items: Object.entries(readStatusLabels).map(([status, label]) => ({
          label,
          command: () => this.updateReadStatus(book, status as ReadStatus, label)
        }))
      },
      {
        label: this.t.translate('book.card.menu.resetFableProgress'),
        icon: 'pi pi-undo',
        command: () => {
          this.bookService.resetProgress(book.id, ResetProgressTypes.FABLE).subscribe({
            next: () => {
              this.messageService.add({
                severity: 'success',
                summary: this.t.translate('book.card.toast.progressResetSummary'),
                detail: this.t.translate('book.card.toast.progressResetFableDetail'),
                life: 1500
              });
            },
            error: () => {
              this.toastError(this.t.translate('book.card.toast.progressResetFailedSummary'), this.t.translate('book.card.toast.progressResetFableFailedDetail'), 1500);
            }
          });
        },
      },
      {
        label: this.t.translate('book.card.menu.resetKOReaderProgress'),
        icon: 'pi pi-undo',
        command: () => {
          this.bookService.resetProgress(book.id, ResetProgressTypes.KOREADER).subscribe({
            next: () => {
              this.messageService.add({
                severity: 'success',
                summary: this.t.translate('book.card.toast.progressResetSummary'),
                detail: this.t.translate('book.card.toast.progressResetKOReaderDetail'),
                life: 1500
              });
            },
            error: () => {
              this.toastError(this.t.translate('book.card.toast.progressResetFailedSummary'), this.t.translate('book.card.toast.progressResetKOReaderFailedDetail'), 1500);
            }
          });
        },
      }
    );

    items.push({
      label: this.t.translate('book.card.menu.moreActions'),
      icon: 'pi pi-ellipsis-h',
      items: moreActions
    });

    return items;
  }

  private updateReadStatus(book: Book, status: ReadStatus, label: string): void {
    this.bookService.updateBookReadStatus(book.id, status).subscribe({
      next: () => {
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('book.card.toast.readStatusUpdatedSummary'),
          detail: this.t.translate('book.card.toast.readStatusUpdatedDetail', {label}),
          life: 2000
        });
      },
      error: () => {
        this.toastError(this.t.translate('book.card.toast.readStatusFailedSummary'), this.t.translate('book.card.toast.readStatusFailedDetail'), 3000);
      }
    });
  }

  private toggleCurrentlyReading(book: Book): void {
    const isCurrentlyReading = book['isCurrentlyReading'] === true;
    const action = isCurrentlyReading ? 'remove from' : 'add to';
    
    this.bookService.updateBookCurrentlyReadingStatus(book.id, !isCurrentlyReading).subscribe({
      next: () => {
        this.messageService.add({
          severity: 'success',
          summary: 'Currently Reading Updated',
          detail: `Book ${isCurrentlyReading ? 'removed from' : 'added to'} Currently Reading panel`,
          life: 2000
        });
      },
      error: (error) => {
        console.error('Error updating Currently Reading status:', error);
        this.toastError('Update Failed', `Could not ${action} Currently Reading panel: ${error?.message || 'Unknown error'}`, 3000);
      }
    });
  }

  private openShelfDialog(book: Book): void {
    this.bookDialogHelperService.openShelfAssignerDialog(book, null);
  }

  onDragStart(event: DragEvent): void {
    if (this._isSeriesViewActive) {
      return;
    }
    const currentSelections = this.bookSelectionService.selectedBooks;
    const draggedId = Number(this.book.id);
    const selectionNumbers = new Set(Array.from(currentSelections).map(id => Number(id)));

    console.log('[DragStart Card] Dragging book:', draggedId);
    console.log('[DragStart Card] Selections:', Array.from(selectionNumbers));
    console.log('[DragStart Card] Is dragged book in selections?', selectionNumbers.has(draggedId));

    const bookIds = selectionNumbers.has(draggedId)
      ? Array.from(selectionNumbers)
      : [draggedId];

    console.log('[DragStart Card] Final bookIds:', bookIds);

    const sourceShelfId = this.getCurrentShelfId();

    if (event.dataTransfer) {
      event.dataTransfer.setData('text/plain', JSON.stringify({ bookIds, sourceShelfId }));
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  private getCurrentShelfId(): number | null {
    if (!this.router || !this.router.url) {
      return null;
    }
    const url = this.router.url;
    const match = url.match(/\/shelf\/(\d+)\/books/);
    return match ? parseInt(match[1], 10) : null;
  }

  private openMoveShelfDialog(book: Book, currentShelfId: number): void {
    this.bookDialogHelperService.openShelfAssignerDialog(book, null, currentShelfId);
  }

  private openBookTypeDialog(book: Book): void {
    this.bookDialogHelperService.openBookTypeAssignerDialog(book, null);
  }

  openSeriesInfo(): void {
    const seriesName = this.book?.metadata?.seriesName;
    if (this.isSeriesCollapsed && seriesName) {
      const encodedSeriesName = encodeURIComponent(seriesName);
      this.router.navigate(['/series', encodedSeriesName]);
    } else {
      this.openBookInfo(this.book);
    }
  }

  openBookInfo(book: Book): void {
    const allBookIds = this.bookNavigationService.getAvailableBookIds();
    if (allBookIds.length > 0) {
      this.bookNavigationService.setNavigationContext(allBookIds, book.id);
    }

    if (this.metadataCenterViewMode === 'route') {
      this.router.navigate(['/book', book.id], {
        queryParams: {tab: 'view', returnTo: this.router.url}
      });
    } else {
      this.bookDialogHelperService.openBookDetailsDialog(book.id);
    }
  }

  onInfoAction(book: Book): void {
    if (this.isReviewActionEnabled) {
      this.reviewRequested.emit(book);
      return;
    }
    this.openBookInfo(book);
  }

  private getDownloadMenuItems(book: Book): MenuItem[] {
    const items: MenuItem[] = [];

    items.push({
      label: `${book.fileName || 'Book File'}`,
      icon: 'pi pi-file',
      command: () => {
        this.bookFileService.downloadFile(book);
      }
    });

    if (this.hasAdditionalFiles(book)) {
      items.push({separator: true});
    }

    if (book.alternativeFormats && book.alternativeFormats.length > 0) {
      book.alternativeFormats.forEach(format => {
        const extension = this.getFileExtension(format.filePath);
        items.push({
          label: `${format.fileName} (${this.getFileSizeInMB(format)})`,
          icon: this.getFileIcon(extension),
          command: () => this.downloadAdditionalFile(book, format.id)
        });
      });
    }

    if (book.alternativeFormats && book.alternativeFormats.length > 0 &&
      book.supplementaryFiles && book.supplementaryFiles.length > 0) {
      items.push({separator: true});
    }

    if (book.supplementaryFiles && book.supplementaryFiles.length > 0) {
      book.supplementaryFiles.forEach(file => {
        const extension = this.getFileExtension(file.filePath);
        items.push({
          label: `${file.fileName} (${this.getFileSizeInMB(file)})`,
          icon: this.getFileIcon(extension),
          command: () => this.downloadAdditionalFile(book, file.id)
        });
      });
    }

    return items;
  }

  private getDeleteMenuItems(book: Book): MenuItem[] {
    const items: MenuItem[] = [];

    if (this.allowFileDeletion) {
      items.push({
        label: this.t.translate('book.card.menu.deleteFromDisk'),
        icon: 'pi pi-trash',
        command: () => {
          this.confirmationService.confirm({
            message: this.t.translate('book.card.confirm.deleteBookMessage', {title: book.metadata?.title}),
            header: this.t.translate('book.card.confirm.deleteBookHeader'),
            icon: 'pi pi-exclamation-triangle',
            acceptIcon: 'pi pi-trash',
            rejectIcon: 'pi pi-times',
            acceptLabel: this.t.translate('common.delete'),
            rejectLabel: this.t.translate('common.cancel'),
            acceptButtonStyleClass: 'p-button-danger',
            rejectButtonStyleClass: 'p-button-outlined',
            accept: () => {
              this.bookService.deleteBooks(new Set([book.id]), true).subscribe();
            }
          });
        }
      });
    }

    items.push({
      label: this.t.translate('book.card.menu.removeFromLibrary'),
      icon: 'pi pi-minus-circle',
      items: this.getRemoveFromLibraryMenuItems(book)
    });

    if (items.length > 0 && this.hasAdditionalFiles(book)) {
      items.push({separator: true});
    }

    if (book.alternativeFormats && book.alternativeFormats.length > 0) {
      book.alternativeFormats.forEach(format => {
        const extension = this.getFileExtension(format.filePath);
        items.push({
          label: `${format.fileName} (${this.getFileSizeInMB(format)})`,
          icon: this.getFileIcon(extension),
          command: () => this.deleteAdditionalFile(book.id, format.id, format.fileName || 'file')
        });
      });
    }

    if (book.alternativeFormats && book.alternativeFormats.length > 0 &&
      book.supplementaryFiles && book.supplementaryFiles.length > 0) {
      items.push({separator: true});
    }

    if (book.supplementaryFiles && book.supplementaryFiles.length > 0) {
      book.supplementaryFiles.forEach(file => {
        const extension = this.getFileExtension(file.filePath);
        items.push({
          label: `${file.fileName} (${this.getFileSizeInMB(file)})`,
          icon: this.getFileIcon(extension),
          command: () => this.deleteAdditionalFile(book.id, file.id, file.fileName || 'file')
        });
      });
    }

    return items;
  }

  private getRemoveFromLibraryMenuItems(book: Book): MenuItem[] {
    return [
      {
        label: 'Remove Permanently',
        icon: 'pi pi-minus-circle',
        command: () => this.confirmRemoveFromLibrary(book, 'REMOVE_FOREVER')
      },
      {
        label: 'Remove Until Next Scan',
        icon: 'pi pi-history',
        command: () => this.confirmRemoveFromLibrary(book, 'REMOVE_UNTIL_NEXT_SCAN')
      }
    ];
  }

  private confirmRemoveFromLibrary(book: Book, mode: RemoveFromLibraryMode): void {
    const message = mode === 'REMOVE_FOREVER'
      ? this.t.translate('book.card.confirm.removeFromLibraryMessage', {title: book.metadata?.title})
      : `${this.t.translate('book.card.confirm.removeFromLibraryMessage', {title: book.metadata?.title})}\n\nThis option allows the book to return on the next scan.`;

    this.confirmationService.confirm({
      message,
      header: mode === 'REMOVE_FOREVER' ? this.t.translate('book.card.confirm.removeFromLibraryHeader') : 'Remove Until Next Scan',
      icon: 'pi pi-exclamation-triangle',
      acceptIcon: mode === 'REMOVE_FOREVER' ? 'pi pi-minus-circle' : 'pi pi-history',
      rejectIcon: 'pi pi-times',
      acceptLabel: this.t.translate('common.remove'),
      rejectLabel: this.t.translate('common.cancel'),
      acceptButtonStyleClass: 'p-button-warning',
      rejectButtonStyleClass: 'p-button-outlined',
      accept: () => {
        this.bookService.deleteBooks(new Set([book.id]), false, mode).subscribe();
      }
    });
  }

  private hasAdditionalFiles(book: Book): boolean {
    return !!(book.alternativeFormats && book.alternativeFormats.length > 0) ||
      !!(book.supplementaryFiles && book.supplementaryFiles.length > 0);
  }

  private downloadAdditionalFile(book: Book, fileId: number): void {
    this.bookFileService.downloadAdditionalFile(book, fileId);
  }

  private deleteAdditionalFile(bookId: number, fileId: number, fileName: string): void {
    this.confirmationService.confirm({
      message: this.t.translate('book.card.confirm.deleteFileMessage', {fileName}),
      header: this.t.translate('book.card.confirm.deleteFileHeader'),
      icon: 'pi pi-exclamation-triangle',
      acceptIcon: 'pi pi-trash',
      rejectIcon: 'pi pi-times',
      acceptButtonStyleClass: 'p-button-danger',
      accept: () => {
        this.bookFileService.deleteAdditionalFile(bookId, fileId).subscribe({
          next: () => {
            this.messageService.add({
              severity: 'success',
              summary: this.t.translate('common.success'),
              detail: this.t.translate('book.card.toast.deleteFileSuccessDetail', {fileName})
            });
          },
          error: (error) => {
            this.toastError(
              this.t.translate('common.error'),
              this.t.translate('book.card.toast.deleteFileErrorDetail', {error: error.message || 'Unknown error'})
            );
          }
        });
      }
    });
  }

  getFileExtension(filePath?: string): string | null {
    if (!filePath) return null;
    const parts = filePath.split('.');
    if (parts.length < 2) return null;
    return parts.pop()?.toUpperCase() || null;
  }

  getDisplayFormat(): string | null {
    if (!this.book?.primaryFile) {
      return 'PHY';
    }
    if (this.forceEbookMode && this.book.primaryFile?.bookType === 'AUDIOBOOK') {
      const ebookType = this.getEbookType(this.book);
      if (ebookType) {
        return ebookType;
      }
    }
    const ext = this.book?.primaryFile?.extension;
    if (ext) {
      return ext.toUpperCase();
    }
    return this.getFileExtension(this.book?.primaryFile?.filePath);
  }

  getDisplayIssueNumber(): string | null {
    const comicIssueNumber = this.book?.metadata?.comicMetadata?.issueNumber?.trim();
    if (comicIssueNumber) {
      return comicIssueNumber.startsWith('#') ? comicIssueNumber : `#${comicIssueNumber}`;
    }

    if (!this.book?.seriesCount && this.book?.metadata?.seriesNumber != null) {
      return `#${this.book.metadata.seriesNumber}`;
    }

    return null;
  }

  hasDigitalFile(): boolean {
    return !!this.book?.primaryFile;
  }

  private getFileIcon(fileType: string | null): string {
    if (!fileType) return 'pi pi-file';
    switch (fileType.toLowerCase()) {
      case 'pdf':
        return 'pi pi-file-pdf';
      case 'epub':
      case 'mobi':
      case 'azw3':
      case 'fb2':
        return 'pi pi-book';
      case 'cbz':
      case 'cbr':
      case 'cbx':
        return 'pi pi-image';
      case 'audiobook':
      case 'm4b':
      case 'm4a':
      case 'mp3':
      case 'opus':
        return 'pi pi-headphones';
      default:
        return 'pi pi-file';
    }
  }

  private getFileSizeInMB(fileInfo: AdditionalFile): string {
    const sizeKb = fileInfo?.fileSizeKb;
    return sizeKb != null ? `${(sizeKb / 1024).toFixed(2)} MB` : '-';
  }

  private lastMouseEvent: MouseEvent | null = null;

  captureMouseEvent(event: MouseEvent): void {
    this.lastMouseEvent = event;
  }

  /** Touch digitizer on tablet/desktop: focus this card (reveal controls) + preview. */
  onCardPointerDown(event: PointerEvent): void {
    if (!this.isTouchDigitizerChrome || event.pointerType !== 'touch') {
      return;
    }
    const card = this.hostEl.nativeElement.querySelector('.book-card') as HTMLElement | null;
    card?.focus({preventScroll: true});
    this.bookClicked.emit(this.book);
  }

  onCardClick(event: MouseEvent | KeyboardEvent): void {
    if (!this.isCheckboxEnabled) {
      return;
    }

    const hasMods = !!(event.shiftKey || event.ctrlKey || event.metaKey);
    // Touch tap-to-add while a selection is active (phone geometry + touch-digitizer
    // tablet/desktop). Pure mouse desktop must not steal Shift/Ctrl multi-select.
    const touchTapSelect =
      this.isTouchRangeSelectEnabled
      && this.bookSelectionService.hasSelection()
      && (this.isMobileInteractionMode || this.isTouchDigitizerChrome)
      && !hasMods;

    if (touchTapSelect) {
      event.preventDefault();
      event.stopPropagation();
      this.checkboxClick.emit({
        index: this.index,
        book: this.book,
        selected: !this.isSelected,
        shiftKey: false
      });
      return;
    }

    if (event.shiftKey && this.bookSelectionService.hasSelection()) {
      event.preventDefault();
      event.stopPropagation();
      this.lastMouseEvent = event instanceof MouseEvent
        ? event
        : {shiftKey: true} as MouseEvent;
      this.toggleCardSelection(!this.isSelected);
      return;
    }

    if (!event.ctrlKey && !event.metaKey) {
      return;
    }

    if (event instanceof MouseEvent) {
      this.lastMouseEvent = event;
    }
    this.toggleCardSelection(!this.isSelected);
  }

  toggleCardSelection(selected: boolean): void {
    if (!this.isCheckboxEnabled) {
      return;
    }

    this.isSelected = selected;
    const shiftKey = this.lastMouseEvent?.shiftKey ?? false;

    this.checkboxClick.emit({
      index: this.index,
      book: this.book,
      selected: selected,
      shiftKey: shiftKey,
    });

    this.lastMouseEvent = null;
  }

  toggleSelection(event: CheckboxChangeEvent): void {
    this.toggleCardSelection(event.checked);
  }

  onTitleAreaActivate(event: Event): void {
    const mouseEvent = event instanceof MouseEvent ? event : null;
    const hasMods = !!(mouseEvent && (mouseEvent.shiftKey || mouseEvent.ctrlKey || mouseEvent.metaKey));
    const touchTapSelect =
      this.isTouchRangeSelectEnabled
      && this.bookSelectionService.hasSelection()
      && (this.isMobileInteractionMode || this.isTouchDigitizerChrome)
      && !hasMods;

    if (touchTapSelect) {
      event.preventDefault();
      event.stopPropagation();
      this.checkboxClick.emit({
        index: this.index,
        book: this.book,
        selected: !this.isSelected,
        shiftKey: false
      });
      return;
    }

    if (mouseEvent?.shiftKey && this.isCheckboxEnabled && this.bookSelectionService.hasSelection()) {
      event.preventDefault();
      event.stopPropagation();
      this.lastMouseEvent = mouseEvent;
      this.toggleCardSelection(!this.isSelected);
      return;
    }

    if (!this.isTitleAreaInteractive) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();

    if (this.titleAreaActivated.observed) {
      this.titleAreaActivated.emit(this.book);
      return;
    }

    if (this.shouldAutoMobileTitlePreview) {
      this.toggleInlineMobilePreview(this.book);
    }
  }

  openInlineMobilePreview(): void {
    this.toggleInlineMobilePreview(this.book);
  }

  toggleInlineMobilePreview(book: Book): void {
    if (!this.shouldAutoMobileTitlePreview) {
      return;
    }

    if (this.activeInlineMobileViewerBook?.id === book.id) {
      this.closeInlineMobilePreview();
      return;
    }

    this.hideActiveTieredMenu();

    const orderedBooks = this.resolveInlineViewerBooks();
    const nextIndex = orderedBooks.findIndex(candidate => candidate.id === book.id);

    if (nextIndex === -1) {
      return;
    }

    this.inlineMobileViewerBooks = orderedBooks;
    this.inlineMobileViewerIndex = nextIndex;
    if (!this.inlineMobilePreviewBackHandle) {
      this.inlineMobilePreviewBackHandle = this.mobileBackNavigation.register(() => {
        this.closeInlineMobilePreview();
      });
    }
    this.resetInlineViewerTouch();
    this.lockBackgroundScroll();
    this.attachPreviewPortal();
    this.cdr.markForCheck();
  }

  closeInlineMobilePreview(): void {
    if (!this.isInlineMobilePreviewOpen) {
      return;
    }

    this.hideActiveTieredMenu();
    this.inlineMobileViewerBooks = [];
    this.inlineMobileViewerIndex = -1;
    this.inlineMobilePreviewBackHandle?.release();
    this.inlineMobilePreviewBackHandle = null;
    this.resetInlineViewerTouch();
    this.unlockBackgroundScroll();
    this.detachPreviewPortal();
    this.cdr.markForCheck();
  }

  onInlineViewerTouchStart(event: TouchEvent): void {
    if (event.touches.length !== 1) {
      return;
    }

    const touch = event.touches[0];
    this.inlineViewerTouchStartX = touch.clientX;
    this.inlineViewerTouchStartY = touch.clientY;
    this.inlineViewerTouchMoved = false;
  }

  onInlineViewerTouchMove(event: TouchEvent): void {
    if (event.touches.length !== 1) {
      return;
    }

    const touch = event.touches[0];
    if (Math.abs(touch.clientX - this.inlineViewerTouchStartX) > 8 || Math.abs(touch.clientY - this.inlineViewerTouchStartY) > 8) {
      this.inlineViewerTouchMoved = true;
    }
  }

  onInlineViewerTouchEnd(event: TouchEvent): void {
    if (!this.inlineViewerTouchMoved || event.changedTouches.length !== 1) {
      this.resetInlineViewerTouch();
      return;
    }

    const touch = event.changedTouches[0];
    const deltaX = touch.clientX - this.inlineViewerTouchStartX;
    const deltaY = Math.abs(touch.clientY - this.inlineViewerTouchStartY);

    if (Math.abs(deltaX) >= this.VIEWER_SWIPE_THRESHOLD_PX && Math.abs(deltaX) > deltaY) {
      if (deltaX < 0) {
        this.showNextInlineViewerBook();
      } else {
        this.showPreviousInlineViewerBook();
      }
    }

    this.resetInlineViewerTouch();
  }

  onInlineViewerTouchCancel(): void {
    this.resetInlineViewerTouch();
  }

  getInlineViewerCoverUrl(book: Book): string {
    return book.primaryFile?.bookType === 'AUDIOBOOK'
      ? this.urlHelper.getAudiobookCoverUrl(book.id, book.metadata?.audiobookCoverUpdatedOn)
      : this.urlHelper.getCoverUrl(book.id, book.metadata?.coverUpdatedOn);
  }

  getInlineViewerTitle(book: Book): string {
    return book.metadata?.title?.trim()
      || book.fileName?.trim()
      || book.primaryFile?.fileName?.trim()
      || 'Untitled';
  }

  getInlineViewerSubtitle(book: Book): string | null {
    const subtitle = book.metadata?.subtitle?.trim();
    return subtitle ? subtitle : null;
  }

  getInlineViewerDisplayFormat(book: Book): string {
    if (!book.primaryFile) {
      return 'PHY';
    }

    const extension = book.primaryFile.extension?.trim();
    if (extension) {
      return extension.toUpperCase();
    }

    return this.getFileExtension(book.primaryFile.filePath) ?? 'PHY';
  }

  getInlineViewerIssueNumber(book: Book): string | null {
    const comicIssueNumber = book.metadata?.comicMetadata?.issueNumber?.trim();
    if (comicIssueNumber) {
      return comicIssueNumber.startsWith('#') ? comicIssueNumber : `#${comicIssueNumber}`;
    }

    if (!book.seriesCount && book.metadata?.seriesNumber != null) {
      return `#${book.metadata.seriesNumber}`;
    }

    return null;
  }

  getInlineViewerReadStatusIcon(book: Book): string {
    return this.readStatusHelper.getReadStatusIcon(book.readStatus);
  }

  getInlineViewerReadStatusClass(book: Book): string {
    return this.readStatusHelper.getReadStatusClass(book.readStatus);
  }

  shouldShowInlineViewerStatus(book: Book): boolean {
    return this.readStatusHelper.shouldShowStatusIcon(book.readStatus);
  }

  private showPreviousInlineViewerBook(): void {
    if (this.inlineMobileViewerIndex <= 0) {
      return;
    }

    this.hideActiveTieredMenu();
    this.inlineMobileViewerIndex -= 1;
    this.cdr.markForCheck();
  }

  private showNextInlineViewerBook(): void {
    if (this.inlineMobileViewerIndex >= this.inlineMobileViewerBooks.length - 1) {
      return;
    }

    this.hideActiveTieredMenu();
    this.inlineMobileViewerIndex += 1;
    this.cdr.markForCheck();
  }

  private resetInlineViewerTouch(): void {
    this.inlineViewerTouchStartX = 0;
    this.inlineViewerTouchStartY = 0;
    this.inlineViewerTouchMoved = false;
  }

  private resolveInlineViewerBooks(): Book[] {
    const contextBooks = (this.mobileViewerBooksContext ?? []).filter(
      (candidate): candidate is Book => !!candidate && candidate.id != null
    );

    if (contextBooks.length) {
      return contextBooks;
    }

    return [this.book];
  }

  private lockBackgroundScroll(): void {
    if (this.bodyScrollLockState || typeof document === 'undefined') {
      return;
    }

    this.bodyScrollLockState = {
      bodyOverflow: document.body.style.overflow,
      bodyTouchAction: document.body.style.touchAction,
      htmlOverflow: document.documentElement.style.overflow,
      htmlTouchAction: document.documentElement.style.touchAction,
    };

    document.body.style.overflow = 'hidden';
    document.body.style.touchAction = 'none';
    document.documentElement.style.overflow = 'hidden';
    document.documentElement.style.touchAction = 'none';
  }

  private unlockBackgroundScroll(): void {
    if (!this.bodyScrollLockState || typeof document === 'undefined') {
      return;
    }

    document.body.style.overflow = this.bodyScrollLockState.bodyOverflow;
    document.body.style.touchAction = this.bodyScrollLockState.bodyTouchAction;
    document.documentElement.style.overflow = this.bodyScrollLockState.htmlOverflow;
    document.documentElement.style.touchAction = this.bodyScrollLockState.htmlTouchAction;
    this.bodyScrollLockState = null;
  }

  private attachPreviewPortal(): void {
    if (typeof document === 'undefined' || !this.previewPortal || this.previewPortalOutlet?.hasAttached()) {
      return;
    }

    if (!this.previewPortalHost) {
      this.previewPortalHost = document.createElement('div');
      this.previewPortalHost.className = 'book-card-mobile-preview-portal-host';
      document.body.appendChild(this.previewPortalHost);
    }

    if (!this.previewPortalOutlet) {
      this.previewPortalOutlet = new DomPortalOutlet(this.previewPortalHost, this.appRef, this.injector);
    }

    this.previewPortalOutlet.attach(this.previewPortal);
  }

  private detachPreviewPortal(): void {
    this.previewPortalOutlet?.detach();
    this.previewPortalOutlet?.dispose();
    this.previewPortalOutlet = null;
    this.previewPortalHost?.remove();
    this.previewPortalHost = null;
  }

  onTouchStart(event: TouchEvent): void {
    if (!this.isCheckboxEnabled) return;

    const touch = event.touches[0];
    this.touchStartX = touch.clientX;
    this.touchStartY = touch.clientY;
    this.touchLongPressTriggered = false;

    this.touchHoldTimer = setTimeout(() => {
      this.touchLongPressTriggered = true;
      if (navigator.vibrate) {
        navigator.vibrate(60);
      }
      
      const isSelectionActive = this.bookSelectionService.hasSelection();
      this.checkboxClick.emit({
        index: this.index,
        book: this.book,
        selected: !this.isSelected,
        shiftKey: isSelectionActive
      });
    }, 500);
  }

  onTouchMove(event: TouchEvent): void {
    const touch = event.touches[0];
    const deltaX = Math.abs(touch.clientX - this.touchStartX);
    const deltaY = Math.abs(touch.clientY - this.touchStartY);

    if (deltaX > 10 || deltaY > 10) {
      if (this.touchHoldTimer) {
        clearTimeout(this.touchHoldTimer);
      }
    }
  }

  onTouchEnd(event: TouchEvent): void {
    if (this.touchHoldTimer) {
      clearTimeout(this.touchHoldTimer);
    }
    if (this.touchLongPressTriggered) {
      event.preventDefault();
    }
  }

  onContextMenu(event: MouseEvent): void {
    if (!this.isTouchRangeSelectEnabled) {
      return;
    }
    // Phone geometry path unchanged. On tablet/desktop, only suppress when a
    // touch digitizer is present so mouse right-click still works on desktop.
    if (!this.isMobileInteractionMode && !this.mobileUx.hasTouchInput) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
  }

  ngOnDestroy(): void {
    this.resizeSub?.unsubscribe();
    this.inlineMobilePreviewBackHandle?.release(false);
    this.inlineMobilePreviewBackHandle = null;
    this.hideActiveTieredMenu();
    this.detachPreviewPortal();
    this.unlockBackgroundScroll();
    this.destroy$.next();
    this.destroy$.complete();
    if (this.overlayPrefSub) {
      this.overlayPrefSub.unsubscribe();
    }
    if (this.touchHoldTimer) {
      clearTimeout(this.touchHoldTimer);
    }
  }

  private toastError(summary: string, detail: string, life?: number): void {
    this.failureNotifications.reportSafe(summary, detail);
    this.messageService.add({severity: 'error', summary, detail, ...(life != null ? {life} : {})});
  }
}
