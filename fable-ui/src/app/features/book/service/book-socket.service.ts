import {inject, Injectable, Injector} from '@angular/core';
import {BookStateService} from './book-state.service';
import {Book, BookMetadata} from '../model/book.model';
import {SidebarBadgeRefreshService} from './sidebar-badge-refresh.service';
import {PagedBookBrowserStateService} from './paged-book-browser-state.service';
import {PagedGridPilotService} from './paged-grid-pilot.service';
import {Subject} from 'rxjs';
import {debounceTime} from 'rxjs/operators';

export type BookCatalogChangeEvent =
  | { type: 'created'; book: Book }
  | { type: 'removed'; bookIds: number[] };

@Injectable({
  providedIn: 'root',
})
export class BookSocketService {
  private bookStateService = inject(BookStateService);
  private sidebarBadgeRefresh = inject(SidebarBadgeRefreshService);
  private injector = inject(Injector);
  private syncGridSubject = new Subject<void>();
  private bookUpdatesSubject = new Subject<Book[]>();
  private bookCatalogChangesSubject = new Subject<BookCatalogChangeEvent>();

  /** Full book updates that may change client-side views such as Staging triage. */
  readonly bookUpdates$ = this.bookUpdatesSubject.asObservable();
  /** Newly created or removed book events so views like Staging stay live without reload. */
  readonly bookCatalogChanges$ = this.bookCatalogChangesSubject.asObservable();

  constructor() {
    this.syncGridSubject.pipe(debounceTime(150)).subscribe(() => {
      this.executeSyncPagedGridPilot();
    });
  }

  handleNewlyCreatedBook(book: Book): void {
    this.bookStateService.upsertBookAndInvalidatePagedCaches(book);
    this.sidebarBadgeRefresh.requestRefresh();
    this.bookCatalogChangesSubject.next({type: 'created', book});
  }

  handleRemovedBookIds(removedBookIds: number[]): void {
    this.bookStateService.removeBooksAndInvalidatePagedCaches(removedBookIds);
    if (removedBookIds.length > 0) {
      this.sidebarBadgeRefresh.requestRefresh();
      this.bookCatalogChangesSubject.next({type: 'removed', bookIds: removedBookIds});
    }
  }

  handleBookUpdate(updatedBook: Book): void {
    const existingBook = this.bookStateService.getBookById(updatedBook.id);
    this.bookStateService.replaceBookAcrossState(updatedBook);

    if (this.affectsSidebarCounts(existingBook, updatedBook)) {
      this.sidebarBadgeRefresh.requestRefresh();
    }
    
    this.syncPagedGridPilot();
    this.bookUpdatesSubject.next([updatedBook]);
  }

  handleMultipleBookUpdates(updatedBooks: Book[]): void {
    const shouldRefresh = updatedBooks.some(updatedBook => this.affectsSidebarCounts(this.bookStateService.getBookById(updatedBook.id), updatedBook));

    updatedBooks.forEach(book => this.bookStateService.replaceBookAcrossState(book));

    if (shouldRefresh) {
      this.sidebarBadgeRefresh.requestRefresh();
    }
    
    this.syncPagedGridPilot();
    if (updatedBooks.length > 0) {
      this.bookUpdatesSubject.next(updatedBooks);
    }
  }

  handleBookMetadataUpdate(bookId: number, updatedMetadata: BookMetadata): void {
    this.bookStateService.replaceBookMetadataAcrossState(bookId, updatedMetadata);
    this.syncPagedGridPilot();
  }

  handleMultipleBookCoverPatches(patches: { id: number; coverUpdatedOn: string }[]): void {
    this.bookStateService.patchBookCoverUpdatesAcrossState(patches ?? []);
    this.syncPagedGridPilot();
  }

  private syncPagedGridPilot(): void {
    this.syncGridSubject.next();
  }

  private executeSyncPagedGridPilot(): void {
    // Sync the updated paged cache so the paged grid pilot can re-emit
    // with the new data. Without this, the paged grid pilot's own 
    // bookStateSubject never reflects changes from the shared state, and
    // OnPush book-card components never recompute their bindings.
    // Use Injector for lazy resolution to avoid a circular dependency:
    // BookSocketService → PagedGridPilotService → BookService → BookSocketService
    const pagedBookBrowserStateService = this.injector.get(PagedBookBrowserStateService);
    const pagedGridPilotService = this.injector.get(PagedGridPilotService);
    pagedBookBrowserStateService.syncCacheFromSharedState();
    pagedGridPilotService.refreshActiveState();
  }

  private affectsSidebarCounts(previousBook: Book | undefined, nextBook: Book): boolean {
    if (!previousBook) {
      return false;
    }

    return previousBook.libraryId !== nextBook.libraryId
      || !!previousBook.isPhysical !== !!nextBook.isPhysical
      || this.normalizeFileType(previousBook.fileType) !== this.normalizeFileType(nextBook.fileType)
      || this.getShelfIds(previousBook).join(',') !== this.getShelfIds(nextBook).join(',')
      || !!previousBook.staged !== !!nextBook.staged;
  }

  private normalizeFileType(fileType?: string): string {
    return (fileType ?? '').trim().toLowerCase();
  }

  private getShelfIds(book: Book): number[] {
    return (book.shelves ?? [])
      .map(shelf => shelf.id)
      .filter((id): id is number => typeof id === 'number')
      .sort((left, right) => left - right);
  }
}
