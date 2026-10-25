import { HttpClient, HttpErrorResponse, HttpHeaders, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { AuthService, retryDelay } from './auth.service';
import { authInterceptor } from './auth.interceptor';
import { Tokens } from './auth.models';

const tokens = (accessToken = 'access-one', refreshToken = 'refresh-one', expiresIn = 300): Tokens => ({
  tokenType: 'Bearer', accessToken, refreshToken, expiresIn,
  refreshExpiresAt: new Date(Date.now() + 86_400_000).toISOString(),
});

describe('Gateway authentication boundary', () => {
  let auth: AuthService;
  let http: HttpClient;
  let requests: HttpTestingController;
  const navigate = vi.fn().mockResolvedValue(true);
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(),
      { provide: Router, useValue: { navigate } },
    ] });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpClient);
    requests = TestBed.inject(HttpTestingController);
    navigate.mockClear();
  });
  afterEach(() => { requests.verify(); vi.restoreAllMocks(); });

  function signIn(session = tokens()): void {
    auth.login({ tenantSlug: 'demo-team', email: 'admin@example.com', password: 'a long secret passphrase' }).subscribe();
    const login = requests.expectOne('/api/auth/login');
    expect(login.request.headers.has('Authorization')).toBe(false);
    login.flush(session);
    const me = requests.expectOne('/api/auth/me');
    me.flush({ id: 'user-one', tenantId: 'tenant-one', email: 'admin@example.com', displayName: 'Admin', role: 'ADMIN', enabled: true });
    requests.expectOne('/api/auth/tenant').flush({ id: 'tenant-one', slug: 'demo-team', name: 'Demo' });
  }
  function expireSoon(): void {
    vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 300_000);
  }

  it('loads real identity with a bearer token and leaves browser storage untouched', () => {
    const localSet = vi.spyOn(localStorage, 'setItem');
    const sessionSet = vi.spyOn(sessionStorage, 'setItem');
    signIn();
    expect(auth.user()?.displayName).toBe('Admin');
    expect(auth.tenant()?.slug).toBe('demo-team');
    expect(localSet).not.toHaveBeenCalled();
    expect(sessionSet).not.toHaveBeenCalled();
  });

  it('serializes concurrent preflight refreshes and uses the rotated credential next time', () => {
    signIn(); expireSoon();
    const received: unknown[] = [];
    http.get('/api/campaigns').subscribe(value => received.push(value));
    http.get('/api/reports/summary').subscribe(value => received.push(value));
    const refresh = requests.expectOne('/api/auth/refresh');
    expect(refresh.request.body).toEqual({ refreshToken: 'refresh-one' });
    requests.expectNone('/api/campaigns');
    refresh.flush(tokens('access-two', 'refresh-two'));
    for (const path of ['/api/campaigns', '/api/reports/summary']) {
      const request = requests.expectOne(path);
      expect(request.request.headers.get('Authorization')).toBe('Bearer access-two');
      request.flush({ ok: true });
    }
    expect(received).toHaveLength(2);
    expireSoon();
    http.get('/api/campaigns').subscribe();
    const nextRefresh = requests.expectOne('/api/auth/refresh');
    expect(nextRefresh.request.body).toEqual({ refreshToken: 'refresh-two' });
    nextRefresh.flush(tokens('access-three', 'refresh-three'));
    requests.expectOne('/api/campaigns').flush({});
    vi.restoreAllMocks();
  });

  it('reuses the rotated access token for a late 401 instead of rotating twice', () => {
    signIn();
    http.get('/api/one').subscribe();
    http.get('/api/two').subscribe();
    const first = requests.expectOne('/api/one');
    const late = requests.expectOne('/api/two');
    first.flush({}, { status: 401, statusText: 'Unauthorized' });
    requests.expectOne('/api/auth/refresh').flush(tokens('access-two', 'refresh-two'));
    requests.expectOne('/api/one').flush({});
    late.flush({}, { status: 401, statusText: 'Unauthorized' });
    requests.expectNone('/api/auth/refresh');
    const retry = requests.expectOne('/api/two');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer access-two');
    retry.flush({});
  });

  it('clears the session when refresh is rejected and never retries credentials', () => {
    signIn(); expireSoon();
    http.get('/api/one').subscribe({ error: () => undefined });
    requests.expectOne('/api/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.signedIn).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { expired: '1' } });
    requests.expectNone('/api/one');
    vi.restoreAllMocks();
  });

  it('does not replay refresh after an ambiguous network failure', () => {
    signIn(); expireSoon();
    http.get('/api/one').subscribe({ error: () => undefined });
    requests.expectOne('/api/auth/refresh').error(new ProgressEvent('error'));
    expect(auth.signedIn).toBe(false);
    http.get('/api/two').subscribe({ error: () => undefined });
    requests.expectNone('/api/auth/refresh');
    vi.restoreAllMocks();
  });

  for (const status of [429, 503]) {
    it(`respects Retry-After on refresh ${status} without a retry loop`, () => {
      signIn(); expireSoon();
      http.get('/api/one').subscribe({ error: () => undefined });
      requests.expectOne('/api/auth/refresh').flush({}, { status, statusText: 'Wait', headers: { 'Retry-After': '60' } });
      expect(auth.signedIn).toBe(true);
      let failure: HttpErrorResponse | undefined;
      http.get('/api/two').subscribe({ error: error => { failure = error; } });
      requests.expectNone('/api/auth/refresh');
      expect(failure?.status).toBe(status);
      expect(failure?.headers.get('Retry-After')).toBe('60');
      vi.restoreAllMocks();
    });
  }

  it('does not attach bearer tokens or retry public and external requests', () => {
    signIn();
    for (const path of ['/api/auth/password/forgot', '/api/auth/password/reset', 'https://example.com/api/test', '/assets/icon.svg']) {
      http.post(path, {}).subscribe({ error: () => undefined });
      const request = requests.expectOne(path);
      expect(request.request.headers.has('Authorization')).toBe(false);
      request.flush({}, { status: 401, statusText: 'Unauthorized' });
      requests.expectNone('/api/auth/refresh');
    }
    expect(auth.signedIn).toBe(true);
  });

  it('retries a protected 401 once and expires on another 401', () => {
    signIn();
    http.get('/api/one').subscribe({ error: () => undefined });
    requests.expectOne('/api/one').flush({}, { status: 401, statusText: 'Unauthorized' });
    requests.expectOne('/api/auth/refresh').flush(tokens('access-two', 'refresh-two'));
    requests.expectOne('/api/one').flush({}, { status: 401, statusText: 'Unauthorized' });
    requests.expectNone('/api/auth/refresh');
    expect(auth.signedIn).toBe(false);
  });

  it('waits for an in-flight refresh before logging out its successor', () => {
    signIn(); expireSoon();
    http.get('/api/one').subscribe();
    auth.logout().subscribe();
    requests.expectNone('/api/auth/logout');
    requests.expectOne('/api/auth/refresh').flush(tokens('access-two', 'refresh-two'));
    requests.expectOne('/api/one').flush({});
    const logout = requests.expectOne('/api/auth/logout');
    expect(logout.request.body).toEqual({ refreshToken: 'refresh-two' });
    logout.flush(null);
    expect(auth.signedIn).toBe(false);
  });

  it('clears local identity even if server logout is temporarily unavailable', () => {
    signIn();
    auth.logout().subscribe({ error: () => undefined });
    requests.expectOne('/api/auth/logout').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(auth.signedIn).toBe(false);
    expect(auth.user()).toBeNull();
  });

  it('rejects protected requests without a session before they reach the network', () => {
    http.get('/api/campaigns').subscribe({ error: error => expect(error.status).toBe(401) });
    requests.expectNone('/api/campaigns');
  });

  it('clears partially loaded identity when tenant loading fails during login', () => {
    auth.login({ tenantSlug: 'demo-team', email: 'admin@example.com', password: 'a long passphrase' }).subscribe({ error: () => undefined });
    requests.expectOne('/api/auth/login').flush(tokens());
    requests.expectOne('/api/auth/me').flush({ displayName: 'Admin' });
    requests.expectOne('/api/auth/tenant').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(auth.signedIn).toBe(false);
    expect(auth.user()).toBeNull();
  });

  it('never restores identity from a response arriving after logout', () => {
    signIn();
    auth.loadIdentity().subscribe({ error: () => undefined });
    const lateIdentity = requests.expectOne('/api/auth/me');
    auth.logout().subscribe();
    requests.expectOne('/api/auth/logout').flush(null);
    lateIdentity.flush({ displayName: 'Old identity' });
    requests.expectNone('/api/auth/tenant');
    expect(auth.user()).toBeNull();
    expect(auth.signedIn).toBe(false);
  });

  it('a late logout response cannot clear a newer login', () => {
    signIn();
    auth.logout().subscribe();
    const lateLogout = requests.expectOne('/api/auth/logout');
    signIn(tokens('new-access', 'new-refresh'));
    lateLogout.flush(null);
    expect(auth.token).toBe('new-access');
    expect(auth.user()?.displayName).toBe('Admin');
  });

  it('successful password reset clears any current local session', () => {
    signIn();
    auth.reset('challenge', '00123456', 'a new long passphrase').subscribe();
    requests.expectOne('/api/auth/password/reset').flush(null);
    expect(auth.signedIn).toBe(false);
    expect(auth.user()).toBeNull();
  });

  it('an invalid recovery attempt does not discard a signed-in session', () => {
    signIn();
    auth.reset('challenge', '00123456', 'a new long passphrase').subscribe({ error: () => undefined });
    requests.expectOne('/api/auth/password/reset').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.signedIn).toBe(true);
  });
});

describe('Retry-After parsing', () => {
  it('supports both delay seconds and HTTP date', () => {
    const now = Date.now();
    vi.spyOn(Date, 'now').mockReturnValue(now);
    expect(retryDelay(new HttpErrorResponse({ headers: new HttpHeaders({ 'Retry-After': '42' }) }))).toBe(42);
    expect(retryDelay(new HttpErrorResponse({ headers: new HttpHeaders({ 'Retry-After': new Date(now + 60_000).toUTCString() }) }))).toBeGreaterThanOrEqual(59);
    expect(retryDelay(new HttpErrorResponse({}))).toBe(30);
    vi.restoreAllMocks();
  });
});
