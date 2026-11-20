import {inject, Injectable, Injector} from '@angular/core';
import {HttpClient, HttpParams} from '@angular/common/http';
import {Observable, Subject, from, of} from 'rxjs';
import {concatMap, distinctUntilChanged, exhaustMap, reduce, share, tap} from 'rxjs/operators';
import {Book, BookFileProgress, ReadStatus} from '../model/book.model';
import {BookStateService} from './book-state.service';
import {API_CONFIG} from '../../../core/config/api-config';
import {ResetProgressType, ResetProgressTypes} from '../../../shared/constants/reset-progress-type';
import {BookStatusUpdateResponse, PersonalRatingUpdateResponse} from '../model/book.model';
import {PagedGridPilotService} from './paged-grid-pilot.service';
import {PagedBookBrowserStateService} from './paged-book-browser-state.service';
import {SidebarBadgeRefreshService} from './sidebar-badge-refresh.service';

@Injectable({
  providedIn: 'root',
})
export class BookPatchService {
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/books`;

  private http = inject(HttpClient);
  private injector = inject(Injector);
  private bookStateService = inject(BookStateService);
  private sidebarBadgeRefresh = inject(SidebarBadgeRefreshService);

  private epubProgressSubject = new Subject<{ bookId: number; cfi: string; href: string; percentage: number; bookFileId?: number }>();

  private epubProgress$ = this.epubProgressSubject.pipe(
    distinctUntilChanged((prev, curr) =>
      prev.bookId === curr.bookId &&
      prev.cfi === curr.cfi &&
      prev.href === curr.href &&
      prev.percentage === curr.percentage &&
      prev.bookFileId === curr.bookFileId
    ),
    exhaustMap(payload => {
      const body: {
        bookId: number;
        epubProgress: { cfi: string; href: string; percentage: number };
        fileProgress?: { bookFileId: number; positionData: string; positionHref: string; progressPercent: number };
      } = {
        bookId: payload.bookId,
        epubProgress: {
          cfi: payload.cfi,
          href: payload.href,
          percentage: payload.percentage
        }
      };
      // Add file-level progress if bookFileId is provided
      if (payload.bookFileId) {
        body.fileProgress = {
          bookFileId: payload.bookFileId,
          positionData: payload.cfi,
          positionHref: payload.href,
          progressPercent: payload.percentage
        };
      }
      return this.http.post<void>(`${this.url}/progress`, body);
    }),
    share()
  );

  constructor() {
    this.initializeEpubProgressPipeline();
  }

  private initializeEpubProgressPipeline(): void {
    this.epubProgress$.subscribe();
  }

  updateBookShelves(bookIds: Set<number | undefined>, shelvesToAssign: Set<number | null | undefined>, shelvesToUnassign: Set<number | null | undefined>): Observable<Book[]> {
    const requestPayload = {
      bookIds: Array.from(bookIds),
      shelvesToAssign: Array.from(shelvesToAssign),
      shelvesToUnassign: Array.from(shelvesToUnassign),
    };
    return this.http.post<Book[]>(`${this.url}/shelves`, requestPayload).pipe(
      tap(updatedBooks => {
        const currentState = this.bookStateService.getCurrentBookState();
        if (currentState.books && updatedBooks.length > 0) {
          const updatedBookMap = new Map(updatedBooks.map(b => [b.id, b]));
          const newBooks = currentState.books.map(book => updatedBookMap.get(book.id) ?? book);
          this.bookStateService.updateBookState({...currentState, books: newBooks});
          this.syncPagedBrowsersAfterStateMutation();
        }

        if (updatedBooks.length > 0) {
          this.sidebarBadgeRefresh.requestRefresh();
        }
      })
    );
  }

  private static readonly STAGING_BATCH_SIZE = 500;

  releaseFromStaging(bookIds: Set<number | undefined>): Observable<Book[]> {
    return this.executeChunkedStagingMutation(`${this.url}/staging/release`, bookIds);
  }

  stageForReview(bookIds: Set<number | undefined>): Observable<Book[]> {
    return this.executeChunkedStagingMutation(`${this.url}/staging/stage`, bookIds);
  }

  private executeChunkedStagingMutation(endpoint: string, bookIds: Set<number | undefined>): Observable<Book[]> {
    const validIds = Array.from(bookIds).filter((id): id is number => id != null);
    if (validIds.length === 0) {
      return of([]);
    }

    const chunks: number[][] = [];
    for (let i = 0; i < validIds.length; i += BookPatchService.STAGING_BATCH_SIZE) {
      chunks.push(validIds.slice(i, i + BookPatchService.STAGING_BATCH_SIZE));
    }

    return from(chunks).pipe(
      concatMap(chunk => this.http.post<Book[]>(endpoint, {bookIds: chunk}).pipe(
        tap(updatedBooks => this.applyUpdatedBooksToState(updatedBooks))
      )),
      reduce((allBooks, chunkBooks) => allBooks.concat(chunkBooks), [] as Book[])
    );
  }

  private applyUpdatedBooksToState(updatedBooks: Book[]): void {
    const currentState = this.bookStateService.getCurrentBookState();
    if (currentState.books && updatedBooks.length > 0) {
      const updatedBookMap = new Map(updatedBooks.map(b => [b.id, b]));
      const newBooks = currentState.books.map(book => updatedBookMap.get(book.id) ?? book);
      this.bookStateService.updateBookState({...currentState, books: newBooks});
      this.syncPagedBrowsersAfterStateMutation();
    }

    if (updatedBooks.length > 0) {
      this.sidebarBadgeRefresh.requestRefresh();
    }
  }

  private syncPagedBrowsersAfterStateMutation(): void {
    this.injector.get(PagedBookBrowserStateService).syncCacheFromSharedState();
    this.injector.get(PagedGridPilotService).refreshActiveState();
  }

  updateFileType(bookIds: Set<number | undefined>, fileType: string | null): Observable<Book[]> {
    const requestPayload = {
      bookIds: Array.from(bookIds),
      fileType,
    };

    return this.http.post<Book[]>(`${this.url}/file-types`, requestPayload).pipe(
      tap(updatedBooks => {
        const currentState = this.bookStateService.getCurrentBookState();
        if (currentState.books && updatedBooks.length > 0) {
          const updatedBookMap = new Map(updatedBooks.map(b => [b.id, b]));
          const newBooks = currentState.books.map(book => updatedBookMap.get(book.id) ?? book);
          this.bookStateService.updateBookState({...currentState, books: newBooks});
        }

        if (updatedBooks.length > 0) {
          this.sidebarBadgeRefresh.requestRefresh();
        }
      })
    );
  }

  savePdfProgress(bookId: number, page: number, percentage: number, bookFileId?: number): Observable<void> {
    const body: {
      bookId: number;
      pdfProgress: { page: number; percentage: number };
      fileProgress?: { bookFileId: number; positionData: string; progressPercent: number };
    } = {
      bookId: bookId,
      pdfProgress: {
        page: page,
        percentage: percentage
      }
    };
    // Add file-level progress if bookFileId is provided
    if (bookFileId) {
      body.fileProgress = {
        bookFileId: bookFileId,
        positionData: String(page),
        progressPercent: percentage
      };
    }
    return this.http.post<void>(`${this.url}/progress`, body);
  }

  saveEpubProgress(bookId: number, cfi: string, href: string, percentage: number, bookFileId?: number): void {
    this.epubProgressSubject.next({bookId, cfi, href, percentage, bookFileId});
  }

  saveCbxProgress(bookId: number, page: number, percentage: number, bookFileId?: number): Observable<void> {
    const body: {
      bookId: number;
      cbxProgress: { page: number; percentage: number };
      fileProgress?: { bookFileId: number; positionData: string; progressPercent: number };
    } = {
      bookId: bookId,
      cbxProgress: {
        page: page,
        percentage: percentage
      }
    };
    // Add file-level progress if bookFileId is provided
    if (bookFileId) {
      body.fileProgress = {
        bookFileId: bookFileId,
        positionData: String(page),
        progressPercent: percentage
      };
    }
    return this.http.post<void>(`${this.url}/progress`, body);
  }

  /**
   * Save file-level progress directly using the new API.
   * This is the preferred method for per-file progress tracking.
   */
  saveFileProgress(bookId: number, fileProgress: BookFileProgress): Observable<void> {
    const body = {
      bookId: bookId,
      fileProgress: fileProgress
    };
    return this.http.post<void>(`${this.url}/progress`, body);
  }

  updateDateFinished(bookId: number, dateFinished: string | null): Observable<void> {
    const body = {
      bookId: bookId,
      dateFinished: dateFinished
    };
    return this.http.post<void>(`${this.url}/progress`, body).pipe(
      tap(() => {
        const currentState = this.bookStateService.getCurrentBookState();
        if (currentState.books) {
          const updatedBooks = currentState.books.map(book => {
            if (book.id === bookId) {
              return {...book, dateFinished: dateFinished || undefined};
            }
            return book;
          });
          this.bookStateService.updateBookState({
            ...currentState,
            books: updatedBooks
          });
        }
      })
    );
  }

  resetProgress(bookIds: number | number[], type: ResetProgressType): Observable<BookStatusUpdateResponse[]> {
    const ids = Array.isArray(bookIds) ? bookIds : [bookIds];
    const params = new HttpParams().set('type', ResetProgressTypes[type]);

    return this.http.post<BookStatusUpdateResponse[]>(`${this.url}/reset-progress`, ids, {params}).pipe(
      tap(responses => {
        const currentState = this.bookStateService.getCurrentBookState();
        const updatedBooks = (currentState.books || []).map(book => {
          const response = responses.find(r => r.bookId === book.id);
          if (response) {
            const progressReset: Partial<Book> =
              type === 'KOREADER' ? {koreaderProgress: undefined} :
              type === 'KOBO' ? {koboProgress: undefined} :
              {epubProgress: undefined, pdfProgress: undefined, cbxProgress: undefined, audiobookProgress: undefined};
            return {
              ...book,
              ...progressReset,
              readStatus: response.readStatus,
              readStatusModifiedTime: response.readStatusModifiedTime,
              dateFinished: response.dateFinished
            };
          }
          return book;
        });
        this.bookStateService.updateBookState({...currentState, books: updatedBooks});
      })
    );
  }

  updateBookReadStatus(bookIds: number | number[], status: ReadStatus): Observable<BookStatusUpdateResponse[]> {
    const ids = Array.isArray(bookIds) ? bookIds : [bookIds];
    const requestBody = {
      bookIds: ids,
      status: status
    };

    return this.http.post<BookStatusUpdateResponse[]>(`${this.url}/status`, requestBody).pipe(
      tap(responses => {
        const currentState = this.bookStateService.getCurrentBookState();
        const updatedBooks = (currentState.books || []).map(book => {
          const response = responses.find(r => r.bookId === book.id);
          if (response) {
            return {
              ...book,
              readStatus: response.readStatus,
              readStatusModifiedTime: response.readStatusModifiedTime,
              dateFinished: response.dateFinished
            };
          }
          return book;
        });
        this.bookStateService.updateBookState({...currentState, books: updatedBooks});
      })
    );
  }

  resetPersonalRating(bookIds: number | number[]): Observable<PersonalRatingUpdateResponse[]> {
    const ids = Array.isArray(bookIds) ? bookIds : [bookIds];

    return this.http.post<PersonalRatingUpdateResponse[]>(`${this.url}/reset-personal-rating`, ids).pipe(
      tap(responses => {
        const currentState = this.bookStateService.getCurrentBookState();
        const updatedBooks = (currentState.books || []).map(book => {
          const response = responses.find(r => r.bookId === book.id);
          if (response) {
            return {
              ...book,
              personalRating: response.personalRating
            };
          }
          return book;
        });
        this.bookStateService.updateBookState({...currentState, books: updatedBooks});
      })
    );
  }

  updatePersonalRating(bookIds: number | number[], rating: number): Observable<PersonalRatingUpdateResponse[]> {
    const ids = Array.isArray(bookIds) ? bookIds : [bookIds];

    return this.http.put<PersonalRatingUpdateResponse[]>(`${this.url}/personal-rating`, {ids, rating}).pipe(
      tap(responses => {
        const currentState = this.bookStateService.getCurrentBookState();
        const updatedBooks = (currentState.books || []).map(book => {
          const response = responses.find(r => r.bookId === book.id);
          if (response) {
            return {
              ...book,
              personalRating: response.personalRating
            };
          }
          return book;
        });
        this.bookStateService.updateBookState({...currentState, books: updatedBooks});
      })
    );
  }

  updateLastReadTime(bookId: number): void {
    const timestamp = new Date().toISOString();
    const currentState = this.bookStateService.getCurrentBookState();
    const updatedBooks = (currentState.books || []).map(book =>
      book.id === bookId ? {...book, lastReadTime: timestamp} : book
    );
    this.bookStateService.updateBookState({...currentState, books: updatedBooks});
  }
}
