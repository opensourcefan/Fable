import {Injector} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {Book, BookMetadata} from '../model/book.model';
import {PagedBookBrowserCacheEntry} from '../model/state/paged-book-browser-state.model';
import {BookSocketService} from './book-socket.service';
import {BookStateService} from './book-state.service';
import {SidebarBadgeRefreshService} from './sidebar-badge-refresh.service';
import {PagedBookBrowserStateService} from './paged-book-browser-state.service';
import {PagedGridPilotService} from './paged-grid-pilot.service';

function createBook(id: number, title = `Book ${id}`, libraryId = 1): Book {
  return {
    id,
    libraryId,
    libraryName: `Library ${libraryId}`,
    metadata: {
      bookId: id,
      title,
    },
  } as Book;
}

function createMetadata(bookId: number, title: string, coverUpdatedOn?: string): BookMetadata {
  return {
    bookId,
    title,
    coverUpdatedOn,
  };
}

function createCacheEntry(
  entity: 'ALL_BOOKS' | 'LIBRARY' | 'STAGING' | 'NOT_SHELFED',
  entityId: number | null,
  books: Book[],
  totalElements = books.length
): PagedBookBrowserCacheEntry {
  return {
    key: {
      entity,
      entityId,
      viewMode: 'grid',
      page: 0,
      size: 80,
      sorts: ['addedOn,desc'],
      filterMode: 'and',
      search: null,
      filters: {},
    },
    status: 'loaded',
    page: {
      content: books,
      page: 0,
      size: 80,
      totalElements,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    },
    error: null,
    loadedAt: Date.now(),
    fallbackReason: null,
  };
}

describe('BookSocketService', () => {
  let requestRefreshSpy: ReturnType<typeof vi.fn>;
  let syncCacheSpy: ReturnType<typeof vi.fn>;
  let refreshActiveStateSpy: ReturnType<typeof vi.fn>;
  let injectorGetSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    requestRefreshSpy = vi.fn();
    syncCacheSpy = vi.fn();
    refreshActiveStateSpy = vi.fn();

    const mockPagedBookBrowserStateService = {
      syncCacheFromSharedState: syncCacheSpy,
    };
    const mockPagedGridPilotService = {
      refreshActiveState: refreshActiveStateSpy,
    };

    injectorGetSpy = vi.fn((token: unknown) => {
      if (token === PagedBookBrowserStateService) {
        return mockPagedBookBrowserStateService;
      }
      if (token === PagedGridPilotService) {
        return mockPagedGridPilotService;
      }
      return undefined;
    });

    TestBed.configureTestingModule({
      providers: [
        BookSocketService,
        BookStateService,
        {
          provide: SidebarBadgeRefreshService,
          useValue: {
            requestRefresh: requestRefreshSpy,
          },
        },
        {
          provide: Injector,
          useValue: {
            get: injectorGetSpy,
          },
        },
      ],
    });
  });

  afterEach(() => {
    vi.runAllTimers();
    vi.useRealTimers();
  });

  function createServices() {
    return {
      socketService: TestBed.inject(BookSocketService),
      bookStateService: TestBed.inject(BookStateService),
    };
  }

  it('invalidates all-books and matching library caches when a new book arrives', () => {
    const {socketService, bookStateService} = createServices();

    bookStateService.updateBookState({
      books: [createBook(11, 'Existing Book', 1)],
      loaded: true,
      error: null,
    });

    bookStateService.setPagedCache({
      allBooks: createCacheEntry('ALL_BOOKS', null, [createBook(11, 'Existing Book', 1)], 10),
      matchingLibrary: createCacheEntry('LIBRARY', 1, [createBook(11, 'Existing Book', 1)], 4),
      otherLibrary: createCacheEntry('LIBRARY', 9, [createBook(99, 'Other Library Book', 9)], 2),
      staging: createCacheEntry('STAGING', null, [createBook(11, 'Existing Book', 1)], 1),
    });

    socketService.handleNewlyCreatedBook(createBook(22, 'New Book', 1));

    const state = bookStateService.getCurrentBookState();

    expect(state.books?.map(book => book.id)).toEqual([11, 22]);
    expect(Object.keys(state.pagedCache ?? {})).toEqual(['otherLibrary']);
    expect(state.totalCount).toBe(2);
    expect(requestRefreshSpy).toHaveBeenCalledTimes(1);
  });

  it('preserves staged when a realtime update omits the staged field', () => {
    const {socketService, bookStateService} = createServices();

    bookStateService.updateBookState({
      books: [{...createBook(44, 'Old Title', 1), staged: true}],
      loaded: true,
      error: null,
    });

    socketService.handleBookUpdate(createBook(44, 'New Title', 1));

    const state = bookStateService.getCurrentBookState();
    expect(state.books?.[0].metadata?.title).toBe('New Title');
    expect(state.books?.[0].staged).toBe(true);
  });

  it('emits full book updates for consumers that refresh derived views', () => {
    const {socketService} = createServices();
    const received: Book[][] = [];
    const subscription = socketService.bookUpdates$.subscribe(books => received.push(books));

    const single = createBook(44, 'Updated', 1);
    const batch = [createBook(55, 'Batch One', 1), createBook(66, 'Batch Two', 1)];

    socketService.handleBookUpdate(single);
    socketService.handleMultipleBookUpdates(batch);

    expect(received).toEqual([[single], batch]);
    subscription.unsubscribe();
  });

  it('invalidates staging cache when staged flag changes', () => {
    const {socketService, bookStateService} = createServices();

    bookStateService.updateBookState({
      books: [{...createBook(44, 'Staged Book', 1), staged: true}],
      loaded: true,
      error: null,
    });

    bookStateService.setPagedCache({
      staging: createCacheEntry('STAGING', null, [{...createBook(44, 'Staged Book', 1), staged: true}], 1),
      allBooks: createCacheEntry('ALL_BOOKS', null, [{...createBook(44, 'Staged Book', 1), staged: true}], 1),
    });

    socketService.handleBookUpdate({...createBook(44, 'Staged Book', 1), staged: false});

    const state = bookStateService.getCurrentBookState();
    expect(state.books?.[0].staged).toBe(false);
    expect(Object.keys(state.pagedCache ?? {})).toEqual(['allBooks']);
  });

  it('invalidates impacted caches when books are removed', () => {
    const {socketService, bookStateService} = createServices();

    bookStateService.updateBookState({
      books: [createBook(11, 'Library One', 1), createBook(22, 'Library Two', 2)],
      loaded: true,
      error: null,
    });

    bookStateService.setPagedCache({
      allBooks: createCacheEntry('ALL_BOOKS', null, [createBook(11, 'Library One', 1)], 10),
      libraryOne: createCacheEntry('LIBRARY', 1, [createBook(11, 'Library One', 1)], 5),
      libraryTwo: createCacheEntry('LIBRARY', 2, [createBook(22, 'Library Two', 2)], 5),
      otherLibrary: createCacheEntry('LIBRARY', 9, [createBook(99, 'Other Library Book', 9)], 2),
      staging: createCacheEntry('STAGING', null, [createBook(11, 'Library One', 1)], 1),
    });

    socketService.handleRemovedBookIds([11, 22]);

    const state = bookStateService.getCurrentBookState();

    expect(state.books).toEqual([]);
    expect(Object.keys(state.pagedCache ?? {})).toEqual(['otherLibrary']);
    expect(state.totalCount).toBe(0);
    expect(requestRefreshSpy).toHaveBeenCalledTimes(1);
  });

  it('patches cached pages in place for realtime book updates', () => {
    const {socketService, bookStateService} = createServices();

    bookStateService.updateBookState({
      books: [createBook(44, 'Old Title', 1)],
      loaded: true,
      error: null,
    });

    bookStateService.setPagedCache({
      allBooks: createCacheEntry('ALL_BOOKS', null, [createBook(44, 'Old Title', 1)], 12),
    });

    socketService.handleBookUpdate(createBook(44, 'New Title', 1));

    const state = bookStateService.getCurrentBookState();

    expect(state.books?.[0].metadata?.title).toBe('New Title');
    expect(state.pagedCache?.['allBooks']?.page?.content[0].metadata?.title).toBe('New Title');
    expect(state.totalCount).toBe(12);
    expect(requestRefreshSpy).not.toHaveBeenCalled();
  });

  it('requests a sidebar badge refresh when realtime book updates change count-relevant fields', () => {
    const {socketService, bookStateService} = createServices();

    bookStateService.updateBookState({
      books: [{
        ...createBook(55, 'Old Book', 1),
        fileType: 'PDF',
        isPhysical: false,
        shelves: [{id: 3, name: 'Shelf 3'}],
      }],
      loaded: true,
      error: null,
    });

    socketService.handleBookUpdate({
      ...createBook(55, 'Old Book', 2),
      fileType: 'CBZ',
      isPhysical: true,
      shelves: [{id: 9, name: 'Shelf 9'}],
    });

    expect(requestRefreshSpy).toHaveBeenCalledTimes(1);
  });

  it('patches metadata and cover updates without clearing the paged cache', () => {
    const {socketService, bookStateService} = createServices();
    const initialBook = {
      ...createBook(77, 'Original Title', 1),
      metadata: createMetadata(77, 'Original Title', '2024-01-01T00:00:00Z'),
    };

    bookStateService.updateBookState({
      books: [initialBook],
      loaded: true,
      error: null,
    });

    bookStateService.setPagedCache({
      allBooks: createCacheEntry('ALL_BOOKS', null, [initialBook], 6),
    });

    socketService.handleBookMetadataUpdate(77, createMetadata(77, 'Updated Title', '2024-01-01T00:00:00Z'));
    socketService.handleMultipleBookCoverPatches([{id: 77, coverUpdatedOn: '2025-05-05T12:00:00Z'}]);

    vi.advanceTimersByTime(200);

    const state = bookStateService.getCurrentBookState();

    expect(state.books?.[0].metadata?.title).toBe('Updated Title');
    expect(state.books?.[0].metadata?.coverUpdatedOn).toBe('2025-05-05T12:00:00Z');
    expect(state.pagedCache?.['allBooks']?.page?.content[0].metadata?.title).toBe('Updated Title');
    expect(state.pagedCache?.['allBooks']?.page?.content[0].metadata?.coverUpdatedOn).toBe('2025-05-05T12:00:00Z');
    expect(state.totalCount).toBe(6);
    expect(syncCacheSpy).toHaveBeenCalledTimes(1);
    expect(refreshActiveStateSpy).toHaveBeenCalledTimes(1);
  });

  it('emits bookCatalogChanges$ events when books are created or removed without affecting bookUpdates$', () => {
    const {socketService} = createServices();
    const catalogEvents: unknown[] = [];
    const updateEvents: unknown[] = [];

    socketService.bookCatalogChanges$.subscribe(event => catalogEvents.push(event));
    socketService.bookUpdates$.subscribe(event => updateEvents.push(event));

    const createdBook = {...createBook(88, 'Staged Import', 1), staged: true};
    socketService.handleNewlyCreatedBook(createdBook);
    socketService.handleRemovedBookIds([88]);
    socketService.handleRemovedBookIds([]);

    expect(catalogEvents).toEqual([
      {type: 'created', book: createdBook},
      {type: 'removed', bookIds: [88]},
    ]);
    expect(updateEvents).toEqual([]);
  });
});
