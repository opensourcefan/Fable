import {HttpErrorResponse} from '@angular/common/http';

/**
 * Returns true ONLY for confirmed transient transport or upstream gateway failures
 * (network offline / status 0, or HTTP 502 / 503 / 504).
 *
 * Intentionally returns false for 401/403, other 4xx/500 responses, and generic Error
 * instances so auth guards and interceptors always fail closed on ambiguous errors.
 */
export function isTransientNetworkError(err: unknown): boolean {
  if (!(err instanceof HttpErrorResponse)) {
    return false;
  }
  return err.status === 0 || err.status === 502 || err.status === 503 || err.status === 504;
}

