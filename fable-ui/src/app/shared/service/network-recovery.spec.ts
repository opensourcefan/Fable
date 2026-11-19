import {HttpErrorResponse} from '@angular/common/http';
import {TestBed} from '@angular/core/testing';
import {of, Subject, throwError} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {ConnectionRecoveryService} from './connection-recovery.service';
import {RxStompService} from '../websocket/rx-stomp.service';
import {MetadataProgressService} from './metadata-progress.service';
import {AiSearchScanProgressService} from './ai-search-scan-progress.service';
import {AuthService} from './auth.service';
import {ReadingSessionService} from './reading-session.service';
import {ReadingSessionApiService} from './reading-session-api.service';
import {RxStompState} from '@stomp/rx-stomp';

describe('ConnectionRecoveryService', () => {
  let service: ConnectionRecoveryService;
  let connected$: Subject<RxStompState>;
  let metadataProgressMock: {refreshNow: ReturnType<typeof vi.fn>};
  let aiSearchMock: {reconcileWithServer: ReturnType<typeof vi.fn>};
  let authMock: {
    getInternalAccessToken: ReturnType<typeof vi.fn>;
    getInternalRefreshToken: ReturnType<typeof vi.fn>;
    retryProactiveRefreshIfNeeded: ReturnType<typeof vi.fn>;
  };
  let readingSessionMock: {flushPendingSessions: ReturnType<typeof vi.fn>};

  beforeEach(() => {
    vi.useFakeTimers();
    connected$ = new Subject<RxStompState>();
    metadataProgressMock = {refreshNow: vi.fn()};
    aiSearchMock = {reconcileWithServer: vi.fn()};
    authMock = {
      getInternalAccessToken: vi.fn().mockReturnValue('token'),
      getInternalRefreshToken: vi.fn().mockReturnValue('refresh'),
      retryProactiveRefreshIfNeeded: vi.fn(),
    };
    readingSessionMock = {flushPendingSessions: vi.fn()};

    TestBed.configureTestingModule({
      providers: [
        ConnectionRecoveryService,
        {provide: RxStompService, useValue: {connected$: connected$.asObservable()}},
        {provide: MetadataProgressService, useValue: metadataProgressMock},
        {provide: AiSearchScanProgressService, useValue: aiSearchMock},
        {provide: AuthService, useValue: authMock},
        {provide: ReadingSessionService, useValue: readingSessionMock},
      ],
    });

    service = TestBed.inject(ConnectionRecoveryService);
    service.initialize();
  });

  afterEach(() => {
    service.ngOnDestroy();
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });

  it('skips initial STOMP connect and reconciles all services on subsequent reconnect', async () => {
    connected$.next(RxStompState.OPEN);
    await vi.advanceTimersByTimeAsync(600);
    expect(metadataProgressMock.refreshNow).not.toHaveBeenCalled();

    connected$.next(RxStompState.OPEN);
    await vi.advanceTimersByTimeAsync(600);

    expect(authMock.retryProactiveRefreshIfNeeded).toHaveBeenCalledTimes(1);
    expect(metadataProgressMock.refreshNow).toHaveBeenCalledTimes(1);
    expect(aiSearchMock.reconcileWithServer).toHaveBeenCalledTimes(1);
    expect(readingSessionMock.flushPendingSessions).toHaveBeenCalledTimes(1);
  });

  it('reconciles on window online event', async () => {
    window.dispatchEvent(new Event('online'));
    await vi.advanceTimersByTimeAsync(600);

    expect(metadataProgressMock.refreshNow).toHaveBeenCalledTimes(1);
    expect(readingSessionMock.flushPendingSessions).toHaveBeenCalledTimes(1);
  });
});

describe('ReadingSessionService offline queue', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('queues reading session on status 0 error and flushes on reconnect', () => {
    const createSession = vi.fn()
      .mockReturnValueOnce(throwError(() => new HttpErrorResponse({status: 0})))
      .mockReturnValueOnce(of(void 0));

    TestBed.configureTestingModule({
      providers: [
        ReadingSessionService,
        {
          provide: ReadingSessionApiService,
          useValue: {createSession, sendSessionBeacon: vi.fn().mockReturnValue(true)},
        },
      ],
    });

    const service = TestBed.inject(ReadingSessionService);

    vi.useFakeTimers();
    try {
      service.startSession(42, 'EPUB' as never, 'loc-1', 10);
      vi.advanceTimersByTime(60_000);
      service.endSession('loc-2', 20);
    } finally {
      vi.useRealTimers();
    }

    expect(createSession).toHaveBeenCalledTimes(1);
    const queuedRaw = localStorage.getItem('fable.pendingReadingSessions.v1');
    expect(queuedRaw).not.toBeNull();
    expect(JSON.parse(queuedRaw!)).toHaveLength(1);

    service.flushPendingSessions();
    expect(createSession).toHaveBeenCalledTimes(2);
    expect(localStorage.getItem('fable.pendingReadingSessions.v1')).toBeNull();
  });
});

