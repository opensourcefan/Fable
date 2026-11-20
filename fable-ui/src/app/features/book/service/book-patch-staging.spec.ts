import {TestBed} from '@angular/core/testing';
import {HttpClient} from '@angular/common/http';
import {firstValueFrom, of, throwError} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {BookPatchService} from './book-patch.service';
import {BookStateService} from './book-state.service';
import {SidebarBadgeRefreshService} from './sidebar-badge-refresh.service';
import {PagedBookBrowserStateService} from './paged-book-browser-state.service';
import {PagedGridPilotService} from './paged-grid-pilot.service';
import {Book} from '../model/book.model';

function createBook(id: number, staged: boolean): Book {
  return {
    id,
    libraryId: 1,
    libraryName: 'Main',
    staged,
  } as Book;
}

describe('BookPatchService staging mutations', () => {
  let service: BookPatchService;
  let httpMock: {post: ReturnType<typeof vi.fn>; put: ReturnType<typeof vi.fn>};
  let bookStateServiceMock: {
    getCurrentBookState: ReturnType<typeof vi.fn>;
    updateBookState: ReturnType<typeof vi.fn>;
  };
  let sidebarBadgeRefreshMock: {requestRefresh: ReturnType<typeof vi.fn>};
  let pagedStateMock: {syncCacheFromSharedState: ReturnType<typeof vi.fn>};
  let pagedPilotMock: {refreshActiveState: ReturnType<typeof vi.fn>};
  let storedBooks: Book[];

  beforeEach(() => {
    storedBooks = Array.from({length: 1050}, (_, idx) => createBook(idx + 1, true));
    httpMock = {
      post: vi.fn(),
      put: vi.fn(),
    };
    bookStateServiceMock = {
      getCurrentBookState: vi.fn(() => ({books: storedBooks, loaded: true, error: null})),
      updateBookState: vi.fn((state: {books: Book[]}) => {
        storedBooks = state.books;
      }),
    };
    sidebarBadgeRefreshMock = {requestRefresh: vi.fn()};
    pagedStateMock = {syncCacheFromSharedState: vi.fn()};
    pagedPilotMock = {refreshActiveState: vi.fn()};

    TestBed.configureTestingModule({
      providers: [
        BookPatchService,
        {provide: HttpClient, useValue: httpMock},
        {provide: BookStateService, useValue: bookStateServiceMock},
        {provide: SidebarBadgeRefreshService, useValue: sidebarBadgeRefreshMock},
        {provide: PagedBookBrowserStateService, useValue: pagedStateMock},
        {provide: PagedGridPilotService, useValue: pagedPilotMock},
      ],
    });

    service = TestBed.inject(BookPatchService);
  });

  it('chunks releaseFromStaging into sequential batches of 500 IDs', async () => {
    const requestedChunks: number[][] = [];
    httpMock.post.mockImplementation((_url: string, body: {bookIds: number[]}) => {
      requestedChunks.push(body.bookIds);
      return of(body.bookIds.map(id => createBook(id, false)));
    });

    const ids = new Set(storedBooks.map(b => b.id));
    const result = await firstValueFrom(service.releaseFromStaging(ids));

    expect(requestedChunks.map(c => c.length)).toEqual([500, 500, 50]);
    expect(result).toHaveLength(1050);
    expect(storedBooks.every(b => b.staged === false)).toBe(true);
    expect(sidebarBadgeRefreshMock.requestRefresh).toHaveBeenCalledTimes(3);
  });

  it('preserves local state updates from earlier chunks when a later chunk fails', async () => {
    let callCount = 0;
    httpMock.post.mockImplementation((_url: string, body: {bookIds: number[]}) => {
      callCount += 1;
      if (callCount === 1) {
        return of(body.bookIds.map(id => createBook(id, false)));
      }
      return throwError(() => new Error('Chunk 2 failed'));
    });

    const ids = new Set(storedBooks.slice(0, 600).map(b => b.id));
    await expect(firstValueFrom(service.releaseFromStaging(ids))).rejects.toThrow('Chunk 2 failed');

    expect(storedBooks.slice(0, 500).every(b => b.staged === false)).toBe(true);
    expect(storedBooks.slice(500, 600).every(b => b.staged === true)).toBe(true);
  });

  it('chunks stageForReview to /staging/stage and updates staged flag', async () => {
    storedBooks = [createBook(1, false), createBook(2, false)];
    httpMock.post.mockImplementation((url: string, body: {bookIds: number[]}) => {
      expect(url).toMatch(/\/api\/v1\/books\/staging\/stage$/);
      return of(body.bookIds.map(id => createBook(id, true)));
    });

    const result = await firstValueFrom(service.stageForReview(new Set([1, 2])));

    expect(result).toHaveLength(2);
    expect(storedBooks.every(b => b.staged === true)).toBe(true);
    expect(sidebarBadgeRefreshMock.requestRefresh).toHaveBeenCalledOnce();
  });

  it('short-circuits empty ID sets without HTTP calls', async () => {
    const result = await firstValueFrom(service.releaseFromStaging(new Set([undefined])));
    expect(result).toEqual([]);
    expect(httpMock.post).not.toHaveBeenCalled();
  });
});
