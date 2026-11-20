import {AfterViewInit, ChangeDetectionStrategy, ChangeDetectorRef, Component, EventEmitter, inject, Input, OnChanges, OnDestroy, OnInit, Output, SimpleChanges, ViewChild} from '@angular/core';
import {Table, TableModule} from 'primeng/table';
import {DatePipe, NgClass} from '@angular/common';
import {TooltipModule} from "primeng/tooltip";
import {Book, BookMetadata, IsbnDiscoveryStatus, ReadStatus} from '../../../model/book.model';
import {SortOption} from '../../../model/sort.model';
import {UrlHelperService} from '../../../../../shared/service/url-helper.service';
import {Button} from 'primeng/button';
import {BookMetadataManageService} from '../../../service/book-metadata-manage.service';
import {MessageService} from 'primeng/api';
import {FailureNotificationService} from '../../../../../shared/service/failure-notification.service';
import {Router, RouterLink, UrlTree} from '@angular/router';
import {filter, Subject} from 'rxjs';
import {UserService} from '../../../../settings/user-management/user.service';
import {take, takeUntil} from 'rxjs/operators';
import {ReadStatusHelper} from '../../../helpers/read-status.helper';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {ScrollerOptions} from 'primeng/api';
import {BookSelectionService} from '../book-selection.service';
import {MobileUxService} from '../../../../../core/services/mobile-ux.service';

export interface TableViewportMetrics {
  scrollTop: number;
  clientHeight: number;
  scrollHeight: number;
}

@Component({
  selector: 'app-book-table',
  standalone: true,
  templateUrl: './book-table.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TableModule,
    Button,
    TooltipModule,
    NgClass,
    RouterLink,
    TranslocoDirective
  ],
  styleUrls: ['./book-table.component.scss'],
  providers: [DatePipe]
})
export class BookTableComponent implements OnInit, AfterViewInit, OnDestroy, OnChanges {
  @ViewChild(Table) private ptable?: Table;
  selectedBooks: Book[] = [];
  selectedBookIds = new Set<number>();
  private cellValueCache = new Map<string, string | number>();
  private cellClickableCache = new Map<string, {url: string | UrlTree, anchor: string | number | null | undefined}[]>();
  private metadataLockedCache = new Map<number, boolean>();
  private lastViewportMetrics: TableViewportMetrics | null = null;

  @Output() selectedBooksChange = new EventEmitter<Set<number>>();
  @Output() viewportScroll = new EventEmitter<TableViewportMetrics>();
  @Output() reviewRequested = new EventEmitter<Book>();
  @Input() books: Book[] = [];
  @Input() sortOption: SortOption | null = null;
  @Input() visibleColumns: { field: string; header: string }[] = [];
  @Input() preselectedBookIds = new Set<number>();
  @Input() showSubtitle = false;
  @Input() forceFileNameTitle = false;
  @Input() pagedPilotActive = false;
  @Input() reviewActionEnabled = false;
  @Input() showStagingIsbnProblem = false;

  protected urlHelper = inject(UrlHelperService);
  private bookMetadataManageService = inject(BookMetadataManageService);
  private messageService = inject(MessageService);
  private failureNotifications = inject(FailureNotificationService);
  private userService = inject(UserService);
  private datePipe = inject(DatePipe);
  private readStatusHelper = inject(ReadStatusHelper);
  private readonly t = inject(TranslocoService);
  private cdr = inject(ChangeDetectorRef);
  private router = inject(Router);
  private bookSelectionService = inject(BookSelectionService);
  private mobileUx = inject(MobileUxService);

  readonly starRange = [1, 2, 3, 4, 5];
  readonly virtualScrollOptions: ScrollerOptions = {
    onScroll: (event: { originalEvent?: Event }) => this.onVirtualScroll(event),
  };

  private metadataCenterViewMode: 'route' | 'dialog' = 'route';
  private destroy$ = new Subject<void>();

  /** Columns with live-translated headers (rebuilt on each access). */
  get allColumns(): { field: string; header: string }[] {
    return [
      {field: 'readStatus', header: this.t.translate('book.columnPref.columns.readStatus')},
      {field: 'title', header: this.t.translate('book.columnPref.columns.title')},
      {field: 'authors', header: this.t.translate('book.columnPref.columns.authors')},
      {field: 'publisher', header: this.t.translate('book.columnPref.columns.publisher')},
      {field: 'seriesName', header: this.t.translate('book.columnPref.columns.seriesName')},
      {field: 'seriesNumber', header: this.t.translate('book.columnPref.columns.seriesNumber')},
      {field: 'categories', header: this.t.translate('book.columnPref.columns.categories')},
      {field: 'publishedDate', header: this.t.translate('book.columnPref.columns.publishedDate')},
      {field: 'lastReadTime', header: this.t.translate('book.columnPref.columns.lastReadTime')},
      {field: 'addedOn', header: this.t.translate('book.columnPref.columns.addedOn')},
      {field: 'fileName', header: this.t.translate('book.columnPref.columns.fileName')},
      {field: 'fileSizeKb', header: this.t.translate('book.columnPref.columns.fileSizeKb')},
      {field: 'language', header: this.t.translate('book.columnPref.columns.language')},
      {field: 'isbn', header: this.t.translate('book.columnPref.columns.isbn')},
      {field: 'pageCount', header: this.t.translate('book.columnPref.columns.pageCount')},
      {field: 'amazonRating', header: this.t.translate('book.columnPref.columns.amazonRating')},
      {field: 'amazonReviewCount', header: this.t.translate('book.columnPref.columns.amazonReviewCount')},
      {field: 'goodreadsRating', header: this.t.translate('book.columnPref.columns.goodreadsRating')},
      {field: 'goodreadsReviewCount', header: this.t.translate('book.columnPref.columns.goodreadsReviewCount')},
      {field: 'hardcoverRating', header: this.t.translate('book.columnPref.columns.hardcoverRating')},
      {field: 'hardcoverReviewCount', header: this.t.translate('book.columnPref.columns.hardcoverReviewCount')},
      {field: 'ranobedbRating', header: this.t.translate('book.columnPref.columns.ranobedbRating')},
    ];
  }

  scrollHeight = 'calc(100dvh - 150px)';
  private readonly onResize = () => this.setScrollHeight();

  ngAfterViewInit(): void {
    // PrimeNG's virtual scroller reads offsetHeight during its own ngAfterViewInit,
    // but when BookTableComponent is first created inside an @if block the browser
    // has not yet done a layout pass so offsetHeight is 0. This leaves
    // numItemsInViewport = 0 and zero body rows are ever rendered.
    //
    // Fix: use requestAnimationFrame to wait for the first paint, then call
    // setSize() to recalculate numItemsInViewport using the real container
    // height, then dispatch a scroll event on the scroller element so PrimeNG
    // re-evaluates the visible item range and actually renders the rows.
    this.scheduleVirtualScrollFix();
  }

  private scheduleVirtualScrollFix(): void {
    // PrimeNG's virtual scroller init() uses setTimeout(..., 1) to
    // schedule setSize()/calculateOptions(). We must defer past that
    // so our scroll event arrives after the scroller's internal state
    // is ready. scrollToIndex(1) + scrollToIndex(0) produce two real
    // native scroll events that trigger onScrollPositionChange →
    // onScrollChange → row rendering for the initial viewport.
    requestAnimationFrame(() => {
      setTimeout(() => {
        const scroller = this.ptable?.scroller;
        if (!scroller) return;
        scroller.setSize();
        scroller.scrollToIndex(1, 'auto');
        setTimeout(() => scroller.scrollToIndex(0, 'auto'), 16);
      }, 50);
    });
  }

  private toastError(summary: string, detail: string, life = 3000): void {
    this.messageService.add({severity: 'error', summary, detail, life});
    this.failureNotifications.reportSafe(summary, detail);
  }

  ngOnInit(): void {
    this.userService.userState$
      .pipe(
        filter(userState => !!userState?.user && userState.loaded),
        take(1),
        takeUntil(this.destroy$)
      )
      .subscribe(userState => {
        this.metadataCenterViewMode = userState?.user?.userSettings.metadataCenterViewMode ?? 'route';
      });

    this.syncSelectionFromInputs();
    this.rebuildDerivedCaches();
    this.setScrollHeight();
    window.addEventListener('resize', this.onResize);
  }

  setScrollHeight() {
    const isMobile = window.innerWidth <= 768;
    // Account for selection action panel (~50px) when active
    const hasActionPanel = !!document.querySelector('.selection-action-panel:not(.panel-hidden)');
    const actionPanelOffset = hasActionPanel ? 50 : 0;
    this.scrollHeight = isMobile
      ? `calc(100dvh - ${125 + actionPanelOffset}px)`
      : `calc(100dvh - ${150 + actionPanelOffset}px)`;
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['books'] || changes['visibleColumns'] || changes['showSubtitle'] || changes['forceFileNameTitle']) {
      this.rebuildDerivedCaches();
    }

    if (changes['books'] || changes['preselectedBookIds']) {
      this.syncSelectionFromInputs();
    }

    // Recalculate scrollHeight when books change (layout may shift)
    if (changes['books']) {
      this.setScrollHeight();

      const previousBooks = (changes['books'].previousValue as Book[] | undefined) ?? [];
      const currentBooks = (changes['books'].currentValue as Book[] | undefined) ?? [];
      const appendedPagedBooks = this.pagedPilotActive
        && !changes['books'].firstChange
        && currentBooks.length > previousBooks.length;

      // Keep PrimeNG's initial render fix for legacy table refreshes, but skip
      // the scroll bounce for incremental paged appends.
      if (currentBooks.length > 0 && !changes['books'].firstChange && !this.pagedPilotActive) {
        this.scheduleVirtualScrollFix();
      } else if (appendedPagedBooks) {
        this.restorePagedScrollPosition();
      }
    }
  }

  private onVirtualScroll(event: { originalEvent?: Event }): void {
    const target = event.originalEvent?.target;
    if (!(target instanceof HTMLElement)) {
      return;
    }

    const metrics = {
      scrollTop: target.scrollTop,
      clientHeight: target.clientHeight,
      scrollHeight: target.scrollHeight,
    };

    this.lastViewportMetrics = metrics;
    this.viewportScroll.emit(metrics);
  }

  private restorePagedScrollPosition(): void {
    const savedMetrics = this.lastViewportMetrics;
    const scroller = this.ptable?.scroller;
    const scrollEl = scroller?.getElementRef()?.nativeElement as HTMLElement | undefined;

    if (!savedMetrics || !scroller || !scrollEl) {
      return;
    }

    requestAnimationFrame(() => {
      scroller.setSize();
      scrollEl.scrollTop = savedMetrics.scrollTop;
      scrollEl.dispatchEvent(new Event('scroll'));
      this.cdr.markForCheck?.();
    });
  }

  selectAllBooks(): void {
    this.selectedBookIds = new Set(this.books.map(book => book.id));
    this.selectedBooks = [...this.books];
    this.selectedBooksChange.emit(new Set(this.selectedBookIds));
  }

  clearSelectedBooks(): void {
    this.selectedBookIds = new Set();
    this.selectedBooks = [];
    this.selectedBooksChange.emit(new Set(this.selectedBookIds));
  }

  onRowSelect(event: { data?: Book | Book[] }): void {
    if (event.data && !Array.isArray(event.data)) {
      this.selectedBookIds.add(event.data.id);
      this.selectedBooksChange.emit(new Set(this.selectedBookIds));
    }
  }

  onRowUnselect(event: { data?: Book | Book[] }): void {
    if (event.data && !Array.isArray(event.data)) {
      this.selectedBookIds.delete(event.data.id);
      this.selectedBooksChange.emit(new Set(this.selectedBookIds));
    }
  }

  onHeaderCheckboxToggle(event: { checked: boolean }): void {
    if (event.checked) {
      this.selectedBooks = [...this.books];
      this.selectedBookIds = new Set(this.books.map(book => book.id));
    } else {
      this.selectedBookIds = new Set();
      this.selectedBooks = [];
    }
    this.selectedBooksChange.emit(new Set(this.selectedBookIds));
  }

  refreshSelectionFromInputs(): void {
    this.syncSelectionFromInputs();
  }

  private syncSelectionFromInputs(): void {
    this.selectedBookIds = new Set(this.preselectedBookIds);
    this.selectedBooks = this.books.filter(book => this.selectedBookIds.has(book.id));
  }

  getStarColor(rating: number): string {
    if (rating >= 4.5) {
      return 'rgb(34, 197, 94)';
    } else if (rating >= 4) {
      return 'rgb(52, 211, 153)';
    } else if (rating >= 3.5) {
      return 'rgb(234, 179, 8)';
    } else if (rating >= 2.5) {
      return 'rgb(249, 115, 22)';
    } else {
      return 'rgb(239, 68, 68)';
    }
  }

  ratingForField(metadata: BookMetadata, field: string): number {
    return (metadata as Record<string, number | null | undefined>)[field] ?? 0;
  }

  getAuthorNames(authors: string[]): string {
    return authors?.join(', ') || '';
  }

  getGenres(genres: string[]) {
    return genres?.join(', ') || '';
  }

  trackByBookId(index: number, book: Book): number {
    return book.id;
  }

  isMetadataFullyLocked(metadata: BookMetadata | null | undefined): boolean {
    if (!metadata) {
      return false;
    }

    return this.metadataLockedCache.get(metadata.bookId)
      ?? this.computeMetadataLocked(metadata);
  }

  formatFileSize(kb?: number): string {
    if (kb == null || isNaN(kb)) return '-';
    const mb = kb / 1024;
    return mb >= 1 ? `${mb.toFixed(1)} MB` : `${mb.toFixed(2)} MB`;
  }

  getReadStatusIcon(readStatus: ReadStatus | undefined): string {
    return this.readStatusHelper.getReadStatusIcon(readStatus);
  }

  getReadStatusClass(readStatus: ReadStatus | undefined): string {
    return this.readStatusHelper.getReadStatusClass(readStatus);
  }

  getReadStatusTooltip(readStatus: ReadStatus | undefined): string {
    return this.readStatusHelper.getReadStatusTooltip(readStatus);
  }

  shouldShowStatusIcon(readStatus: ReadStatus | undefined): boolean {
    return this.readStatusHelper.shouldShowStatusIcon(readStatus);
  }

  getCellClickableValue(metadata: BookMetadata | null | undefined, book: Book, field: string) {
    const cacheKey = `${book.id}-${field}`;
    return this.cellClickableCache.get(cacheKey)
      ?? this.computeCellClickableValue(metadata, book, field);
  }

  getCellValue(metadata: BookMetadata | null | undefined, book: Book, field: string): string | number {
    const cacheKey = `${book.id}-${field}`;
    const cachedValue = this.cellValueCache.get(cacheKey);
    if (cachedValue !== undefined) {
      return cachedValue;
    }

    return this.computeCellValue(metadata, book, field);
  }

  private computeCellValue(metadata: BookMetadata | null | undefined, book: Book, field: string): string | number {
    let val: string | number = '';
    switch (field) {
      case 'readStatus':
        val = this.readStatusHelper.getReadStatusTooltip(book?.readStatus);
        break;
      case 'title':
        val = this.getDisplayTitle(book);
        break;
      case 'authors':
        val = this.getAuthorNames(metadata?.authors ?? []);
        break;
      case 'publisher':
        val = metadata?.publisher ?? '';
        break;
      case 'seriesName':
        val = metadata?.seriesName ?? '';
        break;
      case 'seriesNumber':
        val = metadata?.seriesNumber ?? '';
        break;
      case 'categories':
        val = this.getGenres(metadata?.categories ?? []);
        break;
      case 'publishedDate':
        val = metadata?.publishedDate ? this.datePipe.transform(metadata.publishedDate, 'dd-MMM-yyyy') ?? '' : '';
        break;
      case 'lastReadTime':
        val = book.lastReadTime ? this.datePipe.transform(book.lastReadTime, 'dd-MMM-yyyy') ?? '' : '';
        break;
      case 'addedOn':
        val = book.addedOn ? this.datePipe.transform(book.addedOn, 'dd-MMM-yyyy') ?? '' : '';
        break;
      case 'fileName':
        val = book.primaryFile?.fileName ?? '';
        break;
      case 'fileSizeKb':
        val = this.formatFileSize(book.fileSizeKb);
        break;
      case 'language':
        val = metadata?.language ?? '';
        break;
      case 'pageCount':
        val = metadata?.pageCount ?? '';
        break;
      case 'amazonRating':
      case 'goodreadsRating':
      case 'hardcoverRating':
      case 'ranobedbRating': {
        const rating = metadata?.[field];
        val = typeof rating === 'number' ? rating.toFixed(1) : '';
        break;
      }
      case 'amazonReviewCount':
      case 'goodreadsReviewCount':
      case 'hardcoverReviewCount':
        val = metadata?.[field] ?? '';
        break;
      case 'isbn':
        val = metadata?.isbn13 || metadata?.isbn10 || '';
        break;
    }

    return val;
  }

  private computeCellClickableValue(metadata: BookMetadata | null | undefined, book: Book, field: string): {url: string | UrlTree, anchor: string | number | null | undefined}[] {
    const filterKeys: Record<string, string> = {
      'authors': 'author',
      'publisher': 'publisher',
      'categories': 'category',
      'language': 'language',
      'title': 'title',
      'isbn': 'isbn'
    } as const;

    let data: string[] = metadata ? [metadata[field] as string] : [];
    let result: {url: string | UrlTree, anchor: string | number | null | undefined}[] = [];

    switch (field) {
      case 'title':
        result = [
          {
            url: this.urlHelper.getBookUrl ? this.urlHelper.getBookUrl(book) : '',
            anchor: this.getDisplayTitle(book)
          }
        ];
        break;

      case 'categories':
        data = metadata?.categories ?? [];
        break;

      case 'authors':
        data = metadata?.authors ?? [];
        break;

      case 'seriesName':
        result = [
          {
            url: this.urlHelper.filterBooksBy ? this.urlHelper.filterBooksBy('series', metadata?.seriesName ?? '') : '',
            anchor: metadata?.seriesName
          }
        ];
        break;
      case 'isbn':
        result = [
          {
            url: '',
            anchor: this.computeCellValue(metadata, book, 'isbn')
          }
        ];
        break;
    }

    if (result.length === 0) {
      result = data.map(item => {
        return {
          url: this.urlHelper.filterBooksBy ? this.urlHelper.filterBooksBy(filterKeys[field] ?? field, item) : '',
          anchor: item
        };
      });
    }

    return result;
  }

  private computeMetadataLocked(metadata: BookMetadata): boolean {
    const lockedKeys = Object.keys(metadata).filter(key => key.endsWith('Locked'));
    return lockedKeys.length > 0 && lockedKeys.every(key => metadata[key] === true);
  }

  private rebuildDerivedCaches(): void {
    this.cellValueCache.clear();
    this.cellClickableCache.clear();
    this.metadataLockedCache.clear();

    const valueFields = new Set<string>(['title']);
    const clickableFields = new Set<string>(['title']);
    const clickableFieldCandidates = new Set<string>(['title', 'authors', 'publisher', 'seriesName', 'categories', 'language', 'isbn']);
    this.visibleColumns.forEach(col => {
      valueFields.add(col.field);
      if (clickableFieldCandidates.has(col.field)) {
        clickableFields.add(col.field);
      }
    });

    for (const book of this.books) {
      const metadata = book.metadata ?? undefined;
      if (metadata) {
        this.metadataLockedCache.set(metadata.bookId, this.computeMetadataLocked(metadata));
      }

      for (const field of valueFields) {
        this.cellValueCache.set(`${book.id}-${field}`, this.computeCellValue(metadata, book, field));
      }

      for (const field of clickableFields) {
        this.cellClickableCache.set(`${book.id}-${field}`, this.computeCellClickableValue(metadata, book, field));
      }
    }
  }

  private getDisplayTitle(book: Book): string {
    const fileName = book.fileName?.trim() || book.primaryFile?.fileName?.trim() || '';
    if (this.forceFileNameTitle) {
      return fileName;
    }

    const title = book.metadata?.title?.trim() || '';
    const subtitle = book.metadata?.subtitle?.trim() || '';

    if (this.showSubtitle && title && subtitle) {
      return `${title} : ${subtitle}`;
    }

    return title || fileName;
  }

  toggleMetadataLock(metadata: BookMetadata | null | undefined): void {
    if (!metadata) {
      return;
    }

    const lockKeys = Object.keys(metadata).filter(key => key.endsWith('Locked'));
    const allLocked = lockKeys.every(key => metadata[key] === true);
    const lockAction = allLocked ? 'UNLOCK' : 'LOCK';

    this.bookMetadataManageService.toggleAllLock(new Set([metadata.bookId]), lockAction).subscribe({
      next: () => {
        this.messageService.add({
          severity: 'success',
          summary: lockAction === 'LOCK' ? this.t.translate('book.table.toast.metadataLockedSummary') : this.t.translate('book.table.toast.metadataUnlockedSummary'),
          detail: lockAction === 'LOCK' ? this.t.translate('book.table.toast.metadataLockedDetail') : this.t.translate('book.table.toast.metadataUnlockedDetail'),
        });
      },
      error: () => {
        this.toastError(lockAction === 'LOCK' ? this.t.translate('book.table.toast.lockFailedSummary') : this.t.translate('book.table.toast.unlockFailedSummary'), lockAction === 'LOCK' ? this.t.translate('book.table.toast.lockFailedDetail') : this.t.translate('book.table.toast.unlockFailedDetail'), 3000);
      }
    });
  }

  onDragStart(event: DragEvent, book: Book): void {
    const currentSelections = this.bookSelectionService.selectedBooks;
    const draggedId = Number(book.id);
    const selectionNumbers = new Set(Array.from(currentSelections).map(id => Number(id)));

    console.log('[DragStart Table] Dragging book:', draggedId);
    console.log('[DragStart Table] Selections:', Array.from(selectionNumbers));
    console.log('[DragStart Table] Is dragged book in selections?', selectionNumbers.has(draggedId));

    const bookIds = selectionNumbers.has(draggedId)
      ? Array.from(selectionNumbers)
      : [draggedId];

    console.log('[DragStart Table] Final bookIds:', bookIds);

    let sourceShelfId: number | null = null;
    if (this.router && this.router.url) {
      const url = this.router.url;
      const match = url.match(/\/shelf\/(\d+)\/books/);
      sourceShelfId = match ? parseInt(match[1], 10) : null;
    }

    if (event.dataTransfer) {
      event.dataTransfer.setData('text/plain', JSON.stringify({ bookIds, sourceShelfId }));
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  get isReviewActionEnabled(): boolean {
    return this.reviewActionEnabled && !this.mobileUx.isPhone;
  }

  get reviewTooltip(): string {
    return this.t.translate('book.browser.tooltip.openMetadataReview');
  }

  getStagingIsbnProblemStatus(book: Book): IsbnDiscoveryStatus | null {
    if (!this.showStagingIsbnProblem || this.mobileUx.isPhone) {
      return null;
    }
    return book.isbnDiscoveryStatus === IsbnDiscoveryStatus.NOT_FOUND
      || book.isbnDiscoveryStatus === IsbnDiscoveryStatus.ERROR
      ? book.isbnDiscoveryStatus
      : null;
  }

  getStagingIsbnProblemTooltip(book: Book): string {
    const status = this.getStagingIsbnProblemStatus(book);
    const titleKey = status === IsbnDiscoveryStatus.NOT_FOUND
      ? 'book.card.isbnDiscoveryStatus.notFound'
      : 'book.card.isbnDiscoveryStatus.error';
    const title = this.t.translate(titleKey);
    const detail = book.isbnDiscoveryDetail?.trim();
    const checkedAt = book.isbnDiscoveryCheckedAt
      ? new Date(book.isbnDiscoveryCheckedAt).toLocaleString()
      : null;
    return [title, detail, checkedAt].filter(Boolean).join(' — ');
  }

  onStagingIsbnProblemClick(event: Event, book: Book): void {
    event.stopPropagation();
    const status = this.getStagingIsbnProblemStatus(book);
    if (!status) {
      return;
    }
    this.messageService.add({
      severity: status === IsbnDiscoveryStatus.ERROR ? 'error' : 'warn',
      summary: this.t.translate(status === IsbnDiscoveryStatus.NOT_FOUND
        ? 'book.card.isbnDiscoveryStatus.notFound'
        : 'book.card.isbnDiscoveryStatus.error'),
      detail: this.getStagingIsbnProblemTooltip(book),
      life: 5000,
    });
  }

  onReviewActionClick(event: Event, book: Book): void {
    event.stopPropagation();
    this.reviewRequested.emit(book);
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    window.removeEventListener('resize', this.onResize);
  }
}
