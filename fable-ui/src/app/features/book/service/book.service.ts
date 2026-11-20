import {inject, Injectable, Injector} from '@angular/core';
import {first, Observable, of, throwError} from 'rxjs';
import {HttpClient, HttpParams} from '@angular/common/http';
import {catchError, distinctUntilChanged, filter, finalize, map, shareReplay, tap} from 'rxjs/operators';
import {AppBookGridSummary, Book, BookDeletionResponse, BookRecommendation, BookSetting, BookStatusUpdateResponse, BookType, CreatePhysicalBookRequest, PersonalRatingUpdateResponse, ReadStatus} from '../model/book.model';
import {BookState} from '../model/state/book-state.model';
import {API_CONFIG} from '../../../core/config/api-config';
import {MessageService} from 'primeng/api';
import {FailureNotificationService} from '../../../shared/service/failure-notification.service';
import {ResetProgressType} from '../../../shared/constants/reset-progress-type';
import {AuthService} from '../../../shared/service/auth.service';
import {Router} from '@angular/router';
import {BookStateService} from './book-state.service';
import {BookSocketService} from './book-socket.service';
import {BookPatchService} from './book-patch.service';
import {TranslocoService} from '@jsverse/transloco';
import {SidebarBadgeRefreshService} from './sidebar-badge-refresh.service';
import {PagedBookBrowserStateService} from './paged-book-browser-state.service';
import {PagedGridPilotService} from './paged-grid-pilot.service';

/** DTO returned by /api/v1/app/books/continue-reading and continue-listening */
export interface AppBookSummary {
  id: number;
  title: string;
  authors: string[];
  thumbnailUrl: string;
  readStatus: string;
  personalRating: number;
  seriesName: string;
  seriesNumber: number;
  libraryId: number;
  addedOn: string;
  lastReadTime: string;
  readProgress: number;
  primaryFileType: string;
  coverUpdatedOn: string;
  audiobookCoverUpdatedOn: string;
  isPhysical: boolean;
  primaryFileId: number;
  primaryFileName: string;
}

export type RemoveFromLibraryMode = 'REMOVE_FOREVER' | 'REMOVE_UNTIL_NEXT_SCAN';

/** Paginated response from GET /api/v1/books/paged */
export interface AppPageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

/** Parameters for the paginated book endpoint */
export interface PagedBooksParams {
  page?: number;
  size?: number;
  sorts?: string[];
  sort?: string;
  dir?: string;
  libraryId?: number;
  shelfId?: number;
  unshelved?: boolean;
  staged?: boolean;
  mediaTypes?: string[];
  search?: string;
  authors?: string[];
  categories?: string[];
  series?: string;
  publisher?: string;
  language?: string;
  isbn?: string;
  readStatus?: string;
  bookType?: string;
  contentRating?: string;
  filterMode?: string;
}

@Injectable({
  providedIn: 'root',
})
export class BookService {

  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/books`;
  private readonly appUrl = `${API_CONFIG.BASE_URL}/api/v1/app/books`;

  private http = inject(HttpClient);
  private messageService = inject(MessageService);
  private failureNotifications = inject(FailureNotificationService);
  private authService = inject(AuthService);
  private router = inject(Router);
  private bookStateService = inject(BookStateService);
  private bookSocketService = inject(BookSocketService);
  private bookPatchService = inject(BookPatchService);
  private readonly t = inject(TranslocoService);
  private sidebarBadgeRefresh = inject(SidebarBadgeRefreshService);
  private injector = inject(Injector);

  private loading$: Observable<Book[]> | null = null;

  constructor() {
    this.authService.token$.pipe(
      distinctUntilChanged()
    ).subscribe(token => {
      if (token === null) {
        this.bookStateService.resetBookState();
        this.loading$ = null;
      } else {
        const current = this.bookStateService.getCurrentBookState();
        if (current.loaded && !current.books) {
          this.bookStateService.updateBookState({
            books: null,
            loaded: false,
            error: null,
          });
          this.loading$ = null;
        }
      }
    });
  }

  /*------------------ State Management ------------------*/

  bookState$ = this.bookStateService.bookState$.pipe(
    tap(state => {
      if (!state.loaded && !state.error && !this.loading$) {
        this.startBookLoading();
      }
    })
  );

  private startBookLoading(): void {
    this.loading$ = this.fetchBooks().pipe(
      shareReplay(1),
      finalize(() => (this.loading$ = null))
    );
    this.loading$.subscribe();
  }

  getCurrentBookState(): BookState {
    return this.bookStateService.getCurrentBookState();
  }

  private fetchBooks(): Observable<Book[]> {
    return this.http.get<Book[]>(this.url).pipe(
      map(bookList => {
        this.bookStateService.updateBookState({
          books: bookList,
          loaded: true,
          error: null,
        });
        return bookList;
      }),
      catchError(error => {
        const curr = this.bookStateService.getCurrentBookState();
        this.bookStateService.updateBookState({
          books: curr.books,
          loaded: true,
          error: error.message,
        });
        throw error;
      })
    );
  }

  /**
   * Fetch paginated books with server-side sort and filter support.
   * Corresponds to GET /api/v1/books/paged.
   * Returns the lightweight AppBookGridSummary DTO (Phase Two optimization).
   */
  getBooksPaged(params: PagedBooksParams = {}): Observable<AppPageResponse<AppBookGridSummary>> {
    let httpParams = new HttpParams()
      .set('page', String(params.page ?? 0))
      .set('size', String(params.size ?? 50));

    if (params.sorts?.length) {
      params.sorts.forEach(s => httpParams = httpParams.append('sorts', s));
    }
    if (params.sort) httpParams = httpParams.set('sort', params.sort);
    if (params.dir) httpParams = httpParams.set('dir', params.dir);
    if (params.libraryId != null) httpParams = httpParams.set('libraryId', String(params.libraryId));
    if (params.shelfId != null) httpParams = httpParams.set('shelfId', String(params.shelfId));
    if (params.unshelved) httpParams = httpParams.set('unshelved', 'true');
    if (params.staged) httpParams = httpParams.set('staged', 'true');
    if (params.mediaTypes?.length) {
      params.mediaTypes.forEach(mediaType => httpParams = httpParams.append('mediaTypes', mediaType));
    }
    if (params.search) httpParams = httpParams.set('search', params.search);
    if (params.authors?.length) {
      params.authors.forEach(a => httpParams = httpParams.append('authors', a));
    }
    if (params.categories?.length) {
      params.categories.forEach(c => httpParams = httpParams.append('categories', c));
    }
    if (params.series) httpParams = httpParams.set('series', params.series);
    if (params.publisher) httpParams = httpParams.set('publisher', params.publisher);
    if (params.language) httpParams = httpParams.set('language', params.language);
    if (params.isbn) httpParams = httpParams.set('isbn', params.isbn);
    if (params.readStatus) httpParams = httpParams.set('readStatus', params.readStatus);
    if (params.bookType) httpParams = httpParams.set('bookType', params.bookType);
    if (params.contentRating) httpParams = httpParams.set('contentRating', params.contentRating);
    if (params.filterMode) httpParams = httpParams.set('filterMode', params.filterMode);

    return this.http.get<AppPageResponse<AppBookGridSummary>>(`${this.url}/paged`, { params: httpParams });
  }

  /**
   * Adapt a lightweight AppBookGridSummary DTO to the full Book interface
   * for backward compatibility with existing UI components.
   */
  adaptGridSummaryToBook(summary: AppBookGridSummary): Book {
    return {
      id: summary.id,
      fileName: summary.fileName,
      fileType: summary.fileType,
      isPhysical: summary.isPhysical,
      hasAiPanelData: summary.hasAiPanelData,
      hasAiSearchData: summary.hasAiSearchData,
      hasMismatchedAiSearchData: summary.hasMismatchedAiSearchData,
      aiSearchEmbeddingModel: summary.aiSearchEmbeddingModel,
      markedForAiSearch: summary.markedForAiSearch,
      staged: summary.staged,
      isbnDiscoveryStatus: summary.isbnDiscoveryStatus,
      isbnDiscoveryCheckedAt: summary.isbnDiscoveryCheckedAt,
      isbnDiscoveryDetail: summary.isbnDiscoveryDetail,
      lastReadTime: summary.lastReadTime,
      addedOn: summary.addedOn,
      libraryId: 0,
      libraryName: '',
      primaryFile: summary.primaryFileType ? {
        id: 0,
        bookId: summary.id,
        bookType: summary.primaryFileType as BookType,
        extension: summary.primaryFileExtension,
        fileSizeKb: summary.primaryFileSizeKb,
      } : undefined,
      metadata: {
        bookId: summary.id,
        title: summary.title,
        subtitle: summary.subtitle,
        authors: summary.authors,
        publisher: summary.publisher,
        publishedDate: summary.publishedDate,
        seriesName: summary.seriesName,
        seriesNumber: summary.seriesNumber,
        isbn13: summary.isbn13,
        isbn10: summary.isbn10,
        pageCount: summary.pageCount,
        language: summary.language,
        categories: summary.categories,
        amazonRating: summary.amazonRating,
        amazonReviewCount: summary.amazonReviewCount,
        goodreadsRating: summary.goodreadsRating,
        goodreadsReviewCount: summary.goodreadsReviewCount,
        hardcoverRating: summary.hardcoverRating,
        hardcoverReviewCount: summary.hardcoverReviewCount,
        ranobedbRating: summary.ranobedbRating,
        coverUpdatedOn: summary.coverUpdatedOn,
        audiobookCoverUpdatedOn: summary.audiobookCoverUpdatedOn,
        comicMetadata: summary.comicIssueNumber ? {
          issueNumber: summary.comicIssueNumber,
        } : undefined,
      },
      epubProgress: summary.epubProgressPercent != null ? {
        cfi: '',
        percentage: summary.epubProgressPercent,
      } : undefined,
      pdfProgress: summary.pdfProgressPercent != null ? {
        page: 0,
        percentage: summary.pdfProgressPercent,
      } : undefined,
      cbxProgress: summary.cbxProgressPercent != null ? {
        page: 0,
        percentage: summary.cbxProgressPercent,
      } : undefined,
      koreaderProgress: summary.koreaderProgressPercent != null ? {
        percentage: summary.koreaderProgressPercent,
      } : undefined,
      koboProgress: summary.koboProgressPercent != null ? {
        percentage: summary.koboProgressPercent,
      } : undefined,
      readStatus: summary.readStatus as ReadStatus,
    };
  }

  getBooksCount(params: Omit<PagedBooksParams, 'page' | 'size'> = {}): Observable<number> {
    return this.getBooksPaged({
      ...params,
      page: 0,
      size: 1,
    }).pipe(
      map(response => response.totalElements)
    );
  }

  /**
   * Fetch random books from the dedicated endpoint.
   * GET /api/v1/app/books/random?page=0&size=N&libraryId=X
   */
  getRandomBooks(page: number = 0, size: number = 20, libraryId?: number): Observable<AppPageResponse<AppBookSummary>> {
    let params = new HttpParams()
      .set('page', String(page))
      .set('size', String(size));
    if (libraryId != null) {
      params = params.set('libraryId', String(libraryId));
    }
    return this.http.get<AppPageResponse<AppBookSummary>>(`${this.appUrl}/random`, { params });
  }

  /**
   * Fetch continue-reading books from the dedicated paginated endpoint.
   * GET /api/v1/app/books/continue-reading?limit=N&libraryId=X
   */
  getContinueReading(limit: number = 10, libraryId?: number): Observable<AppBookSummary[]> {
    let params = new HttpParams().set('limit', String(limit));
    if (libraryId != null) {
      params = params.set('libraryId', String(libraryId));
    }
    return this.http.get<AppBookSummary[]>(`${this.appUrl}/continue-reading`, { params });
  }

  /**
   * Fetch continue-listening books from the dedicated paginated endpoint.
   * GET /api/v1/app/books/continue-listening?limit=N&libraryId=X
   */
  getContinueListening(limit: number = 10, libraryId?: number): Observable<AppBookSummary[]> {
    let params = new HttpParams().set('limit', String(limit));
    if (libraryId != null) {
      params = params.set('libraryId', String(libraryId));
    }
    return this.http.get<AppBookSummary[]>(`${this.appUrl}/continue-listening`, { params });
  }

  refreshBooks(): Observable<Book[]> {
    return this.http.get<Book[]>(this.url).pipe(
      tap(bookList => {
        this.bookStateService.updateBookState({
          books: bookList,
          loaded: true,
          error: null,
        });
      }),
      catchError(error => {
        const curr = this.bookStateService.getCurrentBookState();
        this.bookStateService.updateBookState({
          books: curr.books,
          loaded: true,
          error: error.message,
        });
        return of(curr.books || []);
      })
    );
  }

  removeBooksByLibraryId(libraryId: number): void {
    const currentState = this.bookStateService.getCurrentBookState();
    const currentBooks = currentState.books || [];
    const filteredBooks = currentBooks.filter(book => book.libraryId !== libraryId);
    this.bookStateService.updateBookState({...currentState, books: filteredBooks});
  }

  removeBooksFromShelf(shelfId: number): void {
    const currentState = this.bookStateService.getCurrentBookState();
    const currentBooks = currentState.books || [];
    const updatedBooks = currentBooks.map(book => ({
      ...book,
      shelves: book.shelves?.filter(shelf => shelf.id !== shelfId),
    }));
    this.bookStateService.updateBookState({...currentState, books: updatedBooks});
  }

  /*------------------ Book Retrieval ------------------*/

  getBookByIdFromState(bookId: number): Book | undefined {
    return this.bookStateService.getBookById(+bookId);
  }

  getBooksByIdsFromState(bookIds: number[]): Book[] {
    return this.bookStateService.getBooksByIds(bookIds);
  }

  getBookByIdFromAPI(bookId: number, withDescription: boolean): Observable<Book> {
    return this.http.get<Book>(`${this.url}/${bookId}`, {
      params: {
        withDescription: withDescription.toString()
      }
    });
  }

  getBooksInSeries(bookId: number): Observable<Book[]> {
    return this.bookStateService.bookState$.pipe(
      filter(state => state.loaded),
      first(),
      map(state => {
        const allBooks = state.books || [];
        const currentBook = allBooks.find(b => b.id === bookId);

        if (!currentBook || !currentBook.metadata?.seriesName) {
          return [];
        }

        const seriesName = currentBook.metadata.seriesName.toLowerCase();
        return allBooks.filter(b => b.metadata?.seriesName?.toLowerCase() === seriesName);
      })
    );
  }

  getBookRecommendations(bookId: number, limit = 20): Observable<BookRecommendation[]> {
    return this.http.get<BookRecommendation[]>(`${this.url}/${bookId}/recommendations`, {
      params: {limit: limit.toString()}
    });
  }

  /*------------------ Book Operations ------------------*/

  deleteBooks(ids: Set<number>, deleteFromDisk = true, removeMode: RemoveFromLibraryMode = 'REMOVE_FOREVER'): Observable<BookDeletionResponse> {
    const idList = Array.from(ids);
    let params = new HttpParams().set('ids', idList.join(',')).set('deleteFromDisk', String(deleteFromDisk));
    if (!deleteFromDisk) {
      params = params.set('removeMode', removeMode);
    }

    return this.http.delete<BookDeletionResponse>(this.url, {params}).pipe(
      tap(response => {
        const deletedIds = new Set((response.deleted || []).map(id => Number(id)));
        const currentState = this.bookStateService.getCurrentBookState();
        const remainingBooks = (currentState.books || []).filter(
          book => !deletedIds.has(book.id)
        );

        this.bookStateService.updateBookState({
          books: remainingBooks,
          loaded: true,
          error: null,
        });

        this.injector.get(PagedBookBrowserStateService).syncCacheFromSharedState();
        this.injector.get(PagedGridPilotService).refreshActiveState();

        if (deletedIds.size > 0) {
          this.sidebarBadgeRefresh.requestRefresh();
        }

        if (deleteFromDisk && response.failedFileDeletions?.length > 0) {
          this.messageService.add({
            severity: 'warn',
            summary: this.t.translate('book.bookService.toast.someFilesNotDeletedSummary'),
            detail: this.t.translate('book.bookService.toast.someFilesNotDeletedDetail', {fileNames: response.failedFileDeletions.join(', ')}),
          });
        } else if (deleteFromDisk) {
          this.messageService.add({
            severity: 'success',
            summary: this.t.translate('book.bookService.toast.booksDeletedSummary'),
            detail: this.t.translate('book.bookService.toast.booksDeletedDetail', {count: deletedIds.size}),
          });
        } else {
          this.messageService.add({
            severity: 'success',
            summary: this.t.translate('book.bookService.toast.booksRemovedSummary'),
            detail: this.t.translate('book.bookService.toast.booksRemovedDetail', {count: deletedIds.size}),
          });
        }
      }),
      catchError(error => {
        this.toastError(this.t.translate('book.bookService.toast.deleteFailedSummary'), error?.error?.message || error?.message || this.t.translate('book.bookService.toast.deleteFailedDetail'));
        return throwError(() => error);
      })
    );
  }

  updateBookShelves(bookIds: Set<number | undefined>, shelvesToAssign: Set<number | null | undefined>, shelvesToUnassign: Set<number | null | undefined>): Observable<Book[]> {
    return this.bookPatchService.updateBookShelves(bookIds, shelvesToAssign, shelvesToUnassign).pipe(
      catchError(error => {
        const currentState = this.bookStateService.getCurrentBookState();
        this.bookStateService.updateBookState({...currentState, error: error.message});
        throw error;
      })
    );
  }

  releaseFromStaging(bookIds: Set<number | undefined>): Observable<Book[]> {
    return this.bookPatchService.releaseFromStaging(bookIds).pipe(
      catchError(error => {
        const currentState = this.bookStateService.getCurrentBookState();
        this.bookStateService.updateBookState({...currentState, error: error.message});
        throw error;
      })
    );
  }

  stageForReview(bookIds: Set<number | undefined>): Observable<Book[]> {
    return this.bookPatchService.stageForReview(bookIds).pipe(
      catchError(error => {
        const currentState = this.bookStateService.getCurrentBookState();
        this.bookStateService.updateBookState({...currentState, error: error.message});
        throw error;
      })
    );
  }

  updateFileType(bookIds: Set<number | undefined>, fileType: string | null): Observable<Book[]> {
    return this.bookPatchService.updateFileType(bookIds, fileType).pipe(
      catchError(error => {
        const currentState = this.bookStateService.getCurrentBookState();
        this.bookStateService.updateBookState({...currentState, error: error.message});
        throw error;
      })
    );
  }

  createPhysicalBook(request: CreatePhysicalBookRequest): Observable<Book> {
    return this.http.post<Book>(`${this.url}/physical`, request).pipe(
      tap(newBook => {
        this.bookSocketService.handleNewlyCreatedBook(newBook);
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('book.bookService.toast.physicalBookCreatedSummary'),
          detail: this.t.translate('book.bookService.toast.physicalBookCreatedDetail', {title: newBook.metadata?.title || 'Book'})
        });
      }),
      catchError(error => {
        this.toastError(this.t.translate('book.bookService.toast.creationFailedSummary'), error?.error?.message || error?.message || this.t.translate('book.bookService.toast.creationFailedDetail'));
        return throwError(() => error);
      })
    );
  }

  togglePhysicalFlag(bookId: number, physical: boolean): Observable<Book> {
    return this.http.patch<Book>(`${this.url}/${bookId}/physical`, null, {params: {physical}}).pipe(
      tap(_updatedBook => {
        const currentState = this.bookStateService.getCurrentBookState();
        const updatedBooks = (currentState.books || []).map(b => b.id === bookId ? {...b, isPhysical: physical} : b);
        this.bookStateService.updateBookState({...currentState, books: updatedBooks});
        this.sidebarBadgeRefresh.requestRefresh();
      })
    );
  }

  /*------------------ Reading & Viewer Settings ------------------*/

  readBook(bookId: number, reader?: 'epub-streaming', explicitBookType?: BookType, targetPage?: number): void {
    const book = this.getBookByIdFromState(bookId);

    if (!book) {
      console.error('Book not found');
      return;
    }

    const bookType: BookType | undefined = explicitBookType ?? book.primaryFile?.bookType;
    const isAlternativeFormat = explicitBookType && explicitBookType !== book.primaryFile?.bookType;

    let baseUrl: string | null = null;
    const queryParams: Record<string, string | boolean | number> = {};

    if (targetPage) {
      queryParams['page'] = targetPage;
    }

    switch (bookType) {
      case 'PDF':
        baseUrl = 'pdf-reader';
        break;

      case 'EPUB':
        baseUrl = 'ebook-reader';
        if (reader === 'epub-streaming') {
          queryParams['streaming'] = true;
        }
        break;

      case 'FB2':
      case 'MOBI':
      case 'AZW3':
        baseUrl = 'ebook-reader';
        break;

      case 'CBX':
        baseUrl = 'cbx-reader';
        break;

      case 'AUDIOBOOK':
        baseUrl = 'audiobook-player';
        break;
    }

    if (!baseUrl) {
      console.error('Unsupported book type:', bookType);
      return;
    }

    if (isAlternativeFormat && bookType) {
      queryParams['bookType'] = bookType;
    }

    const hasQueryParams = Object.keys(queryParams).length > 0;
    this.router.navigate([`/${baseUrl}/book/${book.id}`], hasQueryParams ? {queryParams} : undefined);

    this.updateLastReadTime(book.id);
  }

  getBookSetting(bookId: number, bookFileId: number): Observable<BookSetting> {
    return this.http.get<BookSetting>(`${this.url}/${bookId}/viewer-setting?bookFileId=${bookFileId}`);
  }

  updateViewerSetting(bookSetting: BookSetting, bookId: number): Observable<void> {
    return this.http.put<void>(`${this.url}/${bookId}/viewer-setting`, bookSetting);
  }

  /*------------------ Progress & Status Tracking ------------------*/

  updateLastReadTime(bookId: number): void {
    this.bookPatchService.updateLastReadTime(bookId);
  }

  savePdfProgress(bookId: number, page: number, percentage: number, bookFileId?: number): Observable<void> {
    return this.bookPatchService.savePdfProgress(bookId, page, percentage, bookFileId);
  }

  saveCbxProgress(bookId: number, page: number, percentage: number, bookFileId?: number): Observable<void> {
    return this.bookPatchService.saveCbxProgress(bookId, page, percentage, bookFileId);
  }

  updateDateFinished(bookId: number, dateFinished: string | null): Observable<void> {
    return this.bookPatchService.updateDateFinished(bookId, dateFinished);
  }

  resetProgress(bookIds: number | number[], type: ResetProgressType): Observable<BookStatusUpdateResponse[]> {
    return this.bookPatchService.resetProgress(bookIds, type);
  }

  updateBookReadStatus(bookIds: number | number[], status: ReadStatus): Observable<BookStatusUpdateResponse[]> {
    return this.bookPatchService.updateBookReadStatus(bookIds, status);
  }

  updateBookCurrentlyReadingStatus(bookId: number, isCurrentlyReading: boolean): Observable<Book> {
    const params = new HttpParams().set('isCurrentlyReading', isCurrentlyReading.toString());
    return this.http.patch<Book>(`${this.url}/${bookId}`, null, { params }).pipe(
      tap(_updatedBook => {
        const currentState = this.bookStateService.getCurrentBookState();
        const updatedBooks = (currentState.books || []).map(b => b.id === bookId ? {...b, isCurrentlyReading} : b);
        this.bookStateService.updateBookState({...currentState, books: updatedBooks});
      })
    );
  }

  /*------------------ Personal Rating ------------------*/

  resetPersonalRating(bookIds: number | number[]): Observable<PersonalRatingUpdateResponse[]> {
    return this.bookPatchService.resetPersonalRating(bookIds);
  }

  updatePersonalRating(bookIds: number | number[], rating: number): Observable<PersonalRatingUpdateResponse[]> {
    return this.bookPatchService.updatePersonalRating(bookIds, rating);
  }

  /*------------------ Websocket Handlers ------------------*/

  handleNewlyCreatedBook(book: Book): void {
    this.bookSocketService.handleNewlyCreatedBook(book);
  }

  handleRemovedBookIds(removedBookIds: number[]): void {
    this.bookSocketService.handleRemovedBookIds(removedBookIds);
  }

  handleBookUpdate(updatedBook: Book): void {
    this.bookSocketService.handleBookUpdate(updatedBook);
  }

  handleMultipleBookUpdates(updatedBooks: Book[]): void {
    this.bookSocketService.handleMultipleBookUpdates(updatedBooks);
  }

  handleMultipleBookCoverPatches(patches: { id: number; coverUpdatedOn: string }[]): void {
    this.bookSocketService.handleMultipleBookCoverPatches(patches);
  }

  clearAiPanelDataFromState(): void {
    const currentState = this.bookStateService.getCurrentBookState();
    const updatedBooks = (currentState.books || []).map(book => ({
      ...book,
      hasAiPanelData: false
    }));
    this.bookStateService.updateBookState({ ...currentState, books: updatedBooks });
  }

  private toastError(summary: string, detail: string): void {
    this.failureNotifications.reportSafe(summary, detail);
    this.messageService.add({severity: 'error', summary, detail});
  }
}
