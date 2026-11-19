import {HttpErrorResponse} from '@angular/common/http';
import {TestBed} from '@angular/core/testing';
import {Router} from '@angular/router';
import {firstValueFrom, of, throwError} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {AuthGuard} from './auth.guard';
import {AuthService} from '../../shared/service/auth.service';

describe('AuthGuard', () => {
  let authServiceMock: {
    getInternalAccessToken: ReturnType<typeof vi.fn>;
    getInternalAccessTokenExpiry: ReturnType<typeof vi.fn>;
    getInternalDefaultPassword: ReturnType<typeof vi.fn>;
    getInternalRefreshToken: ReturnType<typeof vi.fn>;
    internalRefreshToken: ReturnType<typeof vi.fn>;
    clearSessionOnLoginPage: ReturnType<typeof vi.fn>;
    hasValidInternalAccessToken: ReturnType<typeof vi.fn>;
    isRefreshing: boolean;
    refreshTokenSubject: {next: ReturnType<typeof vi.fn>};
  };
  let createUrlTree: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    createUrlTree = vi.fn((commands, extras) => ({commands, extras}));
    authServiceMock = {
      getInternalAccessToken: vi.fn(),
      getInternalAccessTokenExpiry: vi.fn(),
      getInternalDefaultPassword: vi.fn(),
      getInternalRefreshToken: vi.fn(),
      internalRefreshToken: vi.fn(),
      clearSessionOnLoginPage: vi.fn(),
      hasValidInternalAccessToken: vi.fn(),
      isRefreshing: false,
      refreshTokenSubject: {next: vi.fn()}
    };

    TestBed.configureTestingModule({
      providers: [
        {provide: AuthService, useValue: authServiceMock},
        {provide: Router, useValue: {createUrlTree}}
      ]
    });
  });

  it('allows navigation when the stored token metadata is valid', () => {
    authServiceMock.getInternalAccessToken.mockReturnValue('access-token');
    authServiceMock.getInternalAccessTokenExpiry.mockReturnValue(Date.now() + 60_000);
    authServiceMock.getInternalDefaultPassword.mockReturnValue(false);

    const result = TestBed.runInInjectionContext(() => AuthGuard({} as never, {} as never));

    expect(result).toBe(true);
    expect(authServiceMock.clearSessionOnLoginPage).not.toHaveBeenCalled();
  });

  it('redirects to change-password when the stored session flags a default password', () => {
    authServiceMock.getInternalAccessToken.mockReturnValue('access-token');
    authServiceMock.getInternalAccessTokenExpiry.mockReturnValue(Date.now() + 60_000);
    authServiceMock.getInternalDefaultPassword.mockReturnValue(true);

    const result = TestBed.runInInjectionContext(() => AuthGuard({} as never, {} as never));

    expect(result).toEqual({commands: ['/change-password'], extras: undefined});
    expect(createUrlTree).toHaveBeenCalledWith(['/change-password']);
  });

  it('clears the session and redirects to login when auth metadata is missing', () => {
    authServiceMock.getInternalAccessToken.mockReturnValue('access-token');
    authServiceMock.getInternalAccessTokenExpiry.mockReturnValue(null);
    authServiceMock.getInternalDefaultPassword.mockReturnValue(null);

    const result = TestBed.runInInjectionContext(() => AuthGuard({} as never, {} as never));

    expect(result).toEqual({commands: ['/login'], extras: undefined});
    expect(authServiceMock.clearSessionOnLoginPage).toHaveBeenCalledTimes(1);
  });

  it('redirects to login when there is no stored access token', () => {
    authServiceMock.getInternalAccessToken.mockReturnValue(null);

    const result = TestBed.runInInjectionContext(() => AuthGuard({} as never, {} as never));

    expect(result).toEqual({commands: ['/login'], extras: undefined});
    expect(createUrlTree).toHaveBeenCalledWith(['/login']);
  });

  it('refreshes an expired access token instead of wiping the refresh token', async () => {
    authServiceMock.getInternalAccessToken.mockReturnValue('expired-access');
    authServiceMock.getInternalAccessTokenExpiry.mockReturnValue(Date.now() - 1_000);
    authServiceMock.getInternalDefaultPassword.mockReturnValue(false);
    authServiceMock.getInternalRefreshToken.mockReturnValue('refresh-token');
    authServiceMock.internalRefreshToken.mockReturnValue(of({
      accessToken: 'new-access',
      refreshToken: 'new-refresh',
      expires: Date.now() + 60_000,
      isDefaultPassword: false
    }));

    const result = TestBed.runInInjectionContext(() => AuthGuard({} as never, {} as never));
    const resolved = await firstValueFrom(result as import('rxjs').Observable<unknown>);

    expect(authServiceMock.internalRefreshToken).toHaveBeenCalled();
    expect(authServiceMock.clearSessionOnLoginPage).not.toHaveBeenCalled();
    expect(resolved).toBe(true);
  });

  it('clears the session when refresh fails after access token expiry', async () => {
    authServiceMock.getInternalAccessToken.mockReturnValue('expired-access');
    authServiceMock.getInternalAccessTokenExpiry.mockReturnValue(Date.now() - 1_000);
    authServiceMock.getInternalDefaultPassword.mockReturnValue(false);
    authServiceMock.getInternalRefreshToken.mockReturnValue('refresh-token');
    authServiceMock.internalRefreshToken.mockReturnValue(throwError(() => new Error('refresh failed')));

    const result = TestBed.runInInjectionContext(() => AuthGuard({} as never, {} as never));
    const resolved = await firstValueFrom(result as import('rxjs').Observable<unknown>);

    expect(authServiceMock.clearSessionOnLoginPage).toHaveBeenCalledTimes(1);
    expect(resolved).toEqual({
      commands: ['/login'],
      extras: {queryParams: {reason: 'session_expired'}}
    });
  });

  it('preserves session and blocks navigation without logging out on transient network refresh error', async () => {
    authServiceMock.getInternalAccessToken.mockReturnValue('expired-access');
    authServiceMock.getInternalAccessTokenExpiry.mockReturnValue(Date.now() - 1_000);
    authServiceMock.getInternalDefaultPassword.mockReturnValue(false);
    authServiceMock.getInternalRefreshToken.mockReturnValue('refresh-token');
    authServiceMock.internalRefreshToken.mockReturnValue(throwError(() => new HttpErrorResponse({status: 0})));

    const result = TestBed.runInInjectionContext(() => AuthGuard({} as never, {} as never));
    const resolved = await firstValueFrom(result as import('rxjs').Observable<unknown>);

    expect(authServiceMock.clearSessionOnLoginPage).not.toHaveBeenCalled();
    expect(resolved).toBe(false);
  });
});
