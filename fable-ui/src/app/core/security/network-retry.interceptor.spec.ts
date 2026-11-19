import {HttpErrorResponse, HttpRequest, HttpResponse} from '@angular/common/http';
import {of, throwError} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {NetworkRetryInterceptor} from './network-retry.interceptor';

describe('NetworkRetryInterceptor', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('retries GET requests on transient status 0 / 503 and resolves when recovered', async () => {
    let attempts = 0;
    const req = new HttpRequest('GET', '/api/v1/books');
    const next = vi.fn(() => {
      attempts++;
      if (attempts <= 2) {
        return throwError(() => new HttpErrorResponse({status: attempts === 1 ? 0 : 503}));
      }
      return of(new HttpResponse({status: 200, body: {ok: true}}));
    });

    let result: unknown = null;
    NetworkRetryInterceptor(req, next).subscribe(res => {
      result = res;
    });

    await vi.advanceTimersByTimeAsync(5000);

    expect(attempts).toBe(3);
    expect(result).toBeInstanceOf(HttpResponse);
  });

  it('never retries POST requests even on status 0', async () => {
    let attempts = 0;
    const req = new HttpRequest('POST', '/api/v1/tasks/start', {});
    const next = vi.fn(() => {
      attempts++;
      return throwError(() => new HttpErrorResponse({status: 0}));
    });

    let caught: unknown = null;
    NetworkRetryInterceptor(req, next).subscribe({
      error: err => {
        caught = err;
      },
    });

    await vi.advanceTimersByTimeAsync(5000);

    expect(attempts).toBe(1);
    expect(caught).toBeInstanceOf(HttpErrorResponse);
  });

  it('never retries auth endpoints or 4xx errors', async () => {
    let authAttempts = 0;
    const authReq = new HttpRequest('GET', '/api/v1/auth/remote');
    NetworkRetryInterceptor(authReq, () => {
      authAttempts++;
      return throwError(() => new HttpErrorResponse({status: 0}));
    }).subscribe({error: () => undefined});

    let notFoundAttempts = 0;
    const getReq = new HttpRequest('GET', '/api/v1/books/999');
    NetworkRetryInterceptor(getReq, () => {
      notFoundAttempts++;
      return throwError(() => new HttpErrorResponse({status: 404}));
    }).subscribe({error: () => undefined});

    await vi.advanceTimersByTimeAsync(5000);

    expect(authAttempts).toBe(1);
    expect(notFoundAttempts).toBe(1);
  });
});

