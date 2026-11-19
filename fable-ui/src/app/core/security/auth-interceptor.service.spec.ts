import {HttpErrorResponse, HttpRequest} from '@angular/common/http';
import {TestBed} from '@angular/core/testing';
import {BehaviorSubject, throwError} from 'rxjs';
import {describe, expect, it, vi} from 'vitest';
import {API_CONFIG} from '../config/api-config';
import {AuthInterceptorService, isPublicAuthEndpoint} from './auth-interceptor.service';
import {AuthService} from '../../shared/service/auth.service';

describe('isPublicAuthEndpoint', () => {
  const auth = `${API_CONFIG.BASE_URL}/api/v1/auth`;

  it('skips bearer for login/refresh/logout/oidc', () => {
    expect(isPublicAuthEndpoint(`${auth}/login`)).toBe(true);
    expect(isPublicAuthEndpoint(`${auth}/refresh`)).toBe(true);
    expect(isPublicAuthEndpoint(`${auth}/logout`)).toBe(true);
    expect(isPublicAuthEndpoint(`${auth}/remote`)).toBe(true);
    expect(isPublicAuthEndpoint(`${auth}/oidc/state`)).toBe(true);
  });

  it('attaches bearer for admin register', () => {
    expect(isPublicAuthEndpoint(`${auth}/register`)).toBe(false);
  });
});

describe('AuthInterceptorService refresh error handling', () => {
  function runWithRefreshError(refreshError: unknown) {
    const authServiceMock = {
      getInternalAccessToken: vi.fn().mockReturnValue('expired-token'),
      internalRefreshToken: vi.fn().mockReturnValue(throwError(() => refreshError)),
      saveInternalTokens: vi.fn(),
      forceLogout: vi.fn(),
      hasValidInternalAccessToken: vi.fn().mockReturnValue(false),
      isRefreshing: false,
      refreshTokenSubject: new BehaviorSubject<string | null>(null),
    };

    TestBed.configureTestingModule({
      providers: [{provide: AuthService, useValue: authServiceMock}],
    });

    const req = new HttpRequest('GET', `${API_CONFIG.BASE_URL}/api/v1/books`);
    let emittedError: unknown = null;

    TestBed.runInInjectionContext(() => {
      AuthInterceptorService(req, () => throwError(() => new HttpErrorResponse({status: 401}))).subscribe({
        error: err => {
          emittedError = err;
        },
      });
    });

    TestBed.resetTestingModule();
    return {authServiceMock, emittedError};
  }

  it('preserves session without calling forceLogout on transient status 0 / 503 refresh failure', () => {
    const {authServiceMock, emittedError} = runWithRefreshError(new HttpErrorResponse({status: 0}));
    expect(authServiceMock.forceLogout).not.toHaveBeenCalled();
    expect(authServiceMock.isRefreshing).toBe(false);
    expect(emittedError).toBeInstanceOf(HttpErrorResponse);

    const second = runWithRefreshError(new HttpErrorResponse({status: 503}));
    expect(second.authServiceMock.forceLogout).not.toHaveBeenCalled();
  });

  it('calls forceLogout on 401 refresh rejection', () => {
    const {authServiceMock} = runWithRefreshError(new HttpErrorResponse({status: 401}));
    expect(authServiceMock.forceLogout).toHaveBeenCalledWith('session_expired');
  });
});
