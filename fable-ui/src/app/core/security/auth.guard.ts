import {inject} from '@angular/core';
import {CanActivateFn, Router, UrlTree} from '@angular/router';
import {catchError, filter, map, Observable, of, take, timer} from 'rxjs';
import {AuthService} from '../../shared/service/auth.service';
import {isTransientNetworkError} from '../util/network-error.util';

export const AuthGuard: CanActivateFn = (_route, _state) => {
  const router = inject(Router);
  const authService = inject(AuthService);

  const internalAccessToken = authService.getInternalAccessToken();
  const internalAccessTokenExpiry = authService.getInternalAccessTokenExpiry();
  const isDefaultPassword = authService.getInternalDefaultPassword();

  if (!internalAccessToken) {
    return router.createUrlTree(['/login']);
  }

  if (internalAccessTokenExpiry === null || isDefaultPassword === null) {
    authService.clearSessionOnLoginPage();
    return router.createUrlTree(['/login']);
  }

  // Access tokens expire after 10 hours; refresh tokens last ~30 days (mobile login retention).
  // Soft-navigate after idle must attempt refresh instead of wiping the refresh token.
  if (internalAccessTokenExpiry <= Date.now()) {
    if (!authService.getInternalRefreshToken()) {
      authService.clearSessionOnLoginPage();
      return router.createUrlTree(['/login'], {queryParams: {reason: 'session_expired'}});
    }

    if (authService.isRefreshing) {
      return waitForInFlightRefresh(authService, router);
    }

    authService.isRefreshing = true;
    authService.refreshTokenSubject.next(null);

    return authService.internalRefreshToken().pipe(
      map(response => {
        authService.isRefreshing = false;
        if (response.accessToken) {
          authService.refreshTokenSubject.next(response.accessToken);
        }
        return postRefreshDestination(authService, router);
      }),
      catchError((err) => {
        authService.isRefreshing = false;
        authService.refreshTokenSubject.next(null);
        if (isTransientNetworkError(err)) {
          return of(false);
        }
        authService.clearSessionOnLoginPage();
        return of(router.createUrlTree(['/login'], {queryParams: {reason: 'session_expired'}}));
      })
    );
  }

  if (isDefaultPassword) {
    return router.createUrlTree(['/change-password']);
  }

  return true;
};

function waitForInFlightRefresh(authService: AuthService, router: Router): Observable<boolean | UrlTree> {
  return timer(0, 50).pipe(
    filter(() => !authService.isRefreshing),
    take(1),
    map(() => {
      if (!authService.hasValidInternalAccessToken()) {
        if (authService.getInternalRefreshToken()) {
          return false;
        }
        authService.clearSessionOnLoginPage();
        return router.createUrlTree(['/login'], {queryParams: {reason: 'session_expired'}});
      }
      return postRefreshDestination(authService, router);
    })
  );
}

function postRefreshDestination(authService: AuthService, router: Router): boolean | UrlTree {
  if (authService.getInternalDefaultPassword()) {
    return router.createUrlTree(['/change-password']);
  }
  return true;
}
