import {inject, Injectable, Injector, OnDestroy} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {BehaviorSubject, firstValueFrom, Observable, tap, timer} from 'rxjs';
import {filter, take} from 'rxjs/operators';
import {RxStompService} from '../websocket/rx-stomp.service';
import {API_CONFIG} from '../../core/config/api-config';
import {createRxStompConfig} from '../websocket/rx-stomp.config';
import {Router} from '@angular/router';
import {PostLoginInitializerService} from '../../core/services/post-login-initializer.service';
import {AuthTokenResponse} from '../model/auth-token-response.model';
import {isTransientNetworkError} from '../../core/util/network-error.util';

const ACCESS_TOKEN_STORAGE_KEY = 'accessToken_Internal';
const REFRESH_TOKEN_STORAGE_KEY = 'refreshToken_Internal';
const ACCESS_TOKEN_EXPIRY_STORAGE_KEY = 'accessToken_Internal_Expires';
const DEFAULT_PASSWORD_STORAGE_KEY = 'isDefaultPassword_Internal';

/** Refresh the access token this many milliseconds before it expires. */
const PROACTIVE_REFRESH_LEAD_MS = 5 * 60 * 1000; // 5 minutes
const PROACTIVE_REFRESH_RETRY_DELAY_MS = 30 * 1000; // 30 seconds on transient network failure

@Injectable({
  providedIn: 'root',
})
export class AuthService implements OnDestroy {

  private apiUrl = `${API_CONFIG.BASE_URL}/api/v1/auth`;
  private rxStompService?: RxStompService;
  private postLoginInitialized = false;

  private http = inject(HttpClient);
  private injector = inject(Injector);
  private router = inject(Router);
  private postLoginInitializer = inject(PostLoginInitializerService);

  public tokenSubject = new BehaviorSubject<string | null>(this.resolveInitialInternalAccessToken());
  public token$ = this.tokenSubject.asObservable();

  /**
   * Token-refresh state used by the auth interceptor.
   * Keeping these as injectable service instance fields (rather than module-level
   * variables in the interceptor file) prevents test pollution and makes state
   * ownership explicit (L3 — OWASP A07 code-quality fix).
   */
  public isRefreshing = false;
  public readonly refreshTokenSubject = new BehaviorSubject<string | null>(null);

  /** Handle for the proactive refresh timer, cleared on logout / destroy. */
  private proactiveRefreshTimerId: ReturnType<typeof setTimeout> | null = null;

  internalLogin(credentials: { username: string; password: string }): Observable<AuthTokenResponse> {
    return this.http.post<AuthTokenResponse>(`${this.apiUrl}/login`, credentials).pipe(
      tap((response) => {
        if (response.accessToken && response.refreshToken) {
          this.saveInternalTokens(response);
          this.initializeWebSocketConnection();
          this.handleSuccessfulAuth();
        }
      })
    );
  }

  internalRefreshToken(): Observable<AuthTokenResponse> {
    const refreshToken = this.getInternalRefreshToken();
    return this.http.post<AuthTokenResponse>(`${this.apiUrl}/refresh`, {refreshToken}).pipe(
      tap((response) => {
        if (response.accessToken && response.refreshToken) {
          this.saveInternalTokens(response);
        }
      })
    );
  }

  remoteLogin(): Observable<AuthTokenResponse> {
    return this.http.get<AuthTokenResponse>(`${this.apiUrl}/remote`).pipe(
      tap((response) => {
        if (response.accessToken && response.refreshToken) {
          this.saveInternalTokens(response);
          this.initializeWebSocketConnection();
          this.handleSuccessfulAuth();
        }
      })
    );
  }

  saveInternalTokens(tokenResponse: AuthTokenResponse): void {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, tokenResponse.accessToken);
    localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, tokenResponse.refreshToken);
    localStorage.setItem(ACCESS_TOKEN_EXPIRY_STORAGE_KEY, tokenResponse.expires.toString());
    localStorage.setItem(DEFAULT_PASSWORD_STORAGE_KEY, String(tokenResponse.isDefaultPassword));
    this.tokenSubject.next(tokenResponse.accessToken);
    this.scheduleProactiveRefresh(tokenResponse.expires);
  }

  getInternalAccessToken(): string | null {
    return localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY);
  }

  getInternalRefreshToken(): string | null {
    return localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY);
  }

  getInternalAccessTokenExpiry(): number | null {
    return this.readStoredNumber(ACCESS_TOKEN_EXPIRY_STORAGE_KEY);
  }

  getInternalDefaultPassword(): boolean | null {
    const val = localStorage.getItem(DEFAULT_PASSWORD_STORAGE_KEY);
    return val !== null ? val === 'true' : null;
  }

  hasValidInternalAccessToken(): boolean {
    const token = this.getInternalAccessToken();
    const expires = this.getInternalAccessTokenExpiry();
    return !!token && expires !== null && expires > Date.now();
  }

  logout(): void {
    const refreshToken = this.getInternalRefreshToken();
    this.http.post<{ logoutUrl: string | null }>(`${this.apiUrl}/logout`, {refreshToken}).subscribe({
      next: (response) => {
        if (response.logoutUrl) {
          window.location.href = response.logoutUrl;
        } else {
          this.clearSession();
          this.router.navigate(['/login']).then(() => window.location.reload());
        }
      },
      error: () => {
        this.clearSession();
        this.router.navigate(['/login']).then(() => window.location.reload());
      }
    });
  }

  forceLogout(reason: string): void {
    this.clearSession();
    this.router.navigate(['/login'], {queryParams: {reason}});
  }

  clearSessionOnLoginPage(): void {
    this.clearSession();
  }

  private clearSession(): void {
    this.cancelProactiveRefresh();
    this.clearStoredSessionData();
    this.tokenSubject.next(null);
    this.postLoginInitialized = false;
    this.getRxStompService().deactivate();
  }

  getRxStompService(): RxStompService {
    if (!this.rxStompService) {
      this.rxStompService = this.injector.get(RxStompService);
    }
    return this.rxStompService;
  }

  initializeWebSocketConnection(): void {
    const token = this.getInternalAccessToken();
    if (!token) return;

    const stompService = this.getRxStompService();
    const config = createRxStompConfig(this);
    stompService.updateConfig(config);
    stompService.activate();

    if (!this.postLoginInitialized) {
      this.handleSuccessfulAuth();
    }
  }

  private handleSuccessfulAuth() {
    if (this.postLoginInitialized) return;
    this.postLoginInitialized = true;
    this.postLoginInitializer.initialize().subscribe({
      error: (err) => console.error('AuthService: Post-login initialization failed:', err)
    });
  }

  private resolveInitialInternalAccessToken(): string | null {
    const accessToken = localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY);
    if (!accessToken) {
      return null;
    }

    const expires = this.readStoredNumber(ACCESS_TOKEN_EXPIRY_STORAGE_KEY);
    const isDefaultPassword = this.getInternalDefaultPassword();
    if (expires === null || isDefaultPassword === null) {
      this.clearStoredSessionData();
      return null;
    }

    if (expires <= Date.now()) {
      return null;
    }

    // Schedule proactive refresh for the existing valid token (e.g., after page reload).
    this.scheduleProactiveRefresh(expires);

    return accessToken;
  }

  private clearStoredSessionData(): void {
    localStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
    localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
    localStorage.removeItem(ACCESS_TOKEN_EXPIRY_STORAGE_KEY);
    localStorage.removeItem(DEFAULT_PASSWORD_STORAGE_KEY);
  }

  /**
   * Ensures a fresh access token is available before opening or reconnecting a WebSocket.
   * Shares the single-flight refresh lock with AuthInterceptorService and AuthGuard.
   */
  async ensureFreshAccessToken(): Promise<string | null> {
    if (this.hasValidInternalAccessToken()) {
      return this.getInternalAccessToken();
    }

    const refreshToken = this.getInternalRefreshToken();
    if (!refreshToken) {
      return this.getInternalAccessToken();
    }

    if (this.isRefreshing) {
      await firstValueFrom(
        timer(0, 50).pipe(
          filter(() => !this.isRefreshing),
          take(1)
        )
      );
      return this.getInternalAccessToken();
    }

    this.isRefreshing = true;
    this.refreshTokenSubject.next(null);
    try {
      const response = await firstValueFrom(this.internalRefreshToken());
      this.isRefreshing = false;
      if (response.accessToken) {
        this.refreshTokenSubject.next(response.accessToken);
        return response.accessToken;
      }
      return this.getInternalAccessToken();
    } catch (err) {
      this.isRefreshing = false;
      this.refreshTokenSubject.next(null);
      if (isTransientNetworkError(err)) {
        console.warn('AuthService: Token refresh deferred during transient outage; preserving session.');
        return this.getInternalAccessToken();
      }
      this.forceLogout('session_expired');
      return null;
    }
  }

  /**
   * Called after connectivity or tab visibility resumes to retry any proactive
   * token refresh that was deferred while offline.
   */
  retryProactiveRefreshIfNeeded(): void {
    const refreshToken = this.getInternalRefreshToken();
    if (!refreshToken || this.isRefreshing) {
      return;
    }
    const expires = this.getInternalAccessTokenExpiry();
    if (expires === null || expires - Date.now() <= PROACTIVE_REFRESH_LEAD_MS) {
      this.executeProactiveRefresh();
    }
  }

  /**
   * Schedules a proactive token refresh ~5 minutes before the access token expires.
   * This prevents the token from expiring while the tab is suspended (common on mobile).
   */
  private scheduleProactiveRefresh(expiresTimestamp: number): void {
    this.cancelProactiveRefresh();

    const expiresAt = expiresTimestamp; // already in epoch millis
    const now = Date.now();
    const delayMs = expiresAt - now - PROACTIVE_REFRESH_LEAD_MS;

    // If the token expires within the lead window (or already expired), refresh immediately.
    const effectiveDelay = Math.max(0, delayMs);

    this.proactiveRefreshTimerId = setTimeout(() => {
      this.executeProactiveRefresh();
    }, effectiveDelay);
  }

  private executeProactiveRefresh(): void {
    const refreshToken = this.getInternalRefreshToken();
    if (!refreshToken) return;
    // Share the interceptor's refresh lock so a tab-wake proactive refresh and a
    // concurrent 401 retry cannot rotate the same refresh token twice.
    if (this.isRefreshing) return;

    this.isRefreshing = true;
    this.refreshTokenSubject.next(null);
    this.internalRefreshToken().subscribe({
      next: (response) => {
        this.isRefreshing = false;
        if (response.accessToken) {
          this.refreshTokenSubject.next(response.accessToken);
        }
        // saveInternalTokens (called inside internalRefreshToken's tap)
        // will re-schedule the next proactive refresh automatically.
      },
      error: (err) => {
        this.isRefreshing = false;
        console.warn('AuthService: Proactive token refresh failed:', err);
        if (isTransientNetworkError(err) && this.getInternalRefreshToken()) {
          this.cancelProactiveRefresh();
          this.proactiveRefreshTimerId = setTimeout(() => {
            this.executeProactiveRefresh();
          }, PROACTIVE_REFRESH_RETRY_DELAY_MS);
        }
      }
    });
  }

  /** Cancels any pending proactive refresh timer. */
  private cancelProactiveRefresh(): void {
    if (this.proactiveRefreshTimerId !== null) {
      clearTimeout(this.proactiveRefreshTimerId);
      this.proactiveRefreshTimerId = null;
    }
  }

  /** Clean up the refresh timer when the service is destroyed. */
  ngOnDestroy(): void {
    this.cancelProactiveRefresh();
  }

  private readStoredNumber(key: string): number | null {
    const value = localStorage.getItem(key);
    if (value === null) {
      return null;
    }

    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }

  private readStoredBoolean(key: string): boolean | null {
    const value = localStorage.getItem(key);
    if (value === 'true') {
      return true;
    }
    if (value === 'false') {
      return false;
    }
    return null;
  }
}

export function websocketInitializer(authService: AuthService): () => void {
  return () => authService.initializeWebSocketConnection();
}
