import {HttpInterceptorFn} from '@angular/common/http';
import {defer, retry, throwError, timer} from 'rxjs';
import {isTransientNetworkError} from '../util/network-error.util';

const RETRY_BASE_DELAYS_MS = [600, 1800];

function applyJitter(baseMs: number): number {
  const jitter = Math.round(baseMs * 0.15);
  const delta = Math.floor(Math.random() * (jitter * 2 + 1)) - jitter;
  return Math.max(50, baseMs + delta);
}

/**
 * Automatically retries idempotent GET/HEAD requests on transient connectivity/gateway
 * failures (status 0, 502, 503, 504). Never retries POST/PUT/PATCH/DELETE or auth routes.
 */
export const NetworkRetryInterceptor: HttpInterceptorFn = (req, next) => {
  const isReadOnly = req.method === 'GET' || req.method === 'HEAD';
  const isAuthRoute = req.url.includes('/api/v1/auth/');

  if (!isReadOnly || isAuthRoute) {
    return next(req);
  }

  return defer(() => next(req)).pipe(
    retry({
      count: RETRY_BASE_DELAYS_MS.length,
      delay: (err, retryIndex) => {
        if (!isTransientNetworkError(err)) {
          return throwError(() => err);
        }
        const baseDelay = RETRY_BASE_DELAYS_MS[Math.min(retryIndex - 1, RETRY_BASE_DELAYS_MS.length - 1)];
        return timer(applyJitter(baseDelay));
      },
    })
  );
};

