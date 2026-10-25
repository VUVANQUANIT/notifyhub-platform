import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, defer, finalize, map, of, shareReplay, switchMap, tap, throwError } from 'rxjs';
import { Credentials, RecoveryRequest, Tenant, Tokens, User } from './auth.models';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly session = signal<Tokens | null>(null);
  private expiresAt = 0;
  private generation = 0;
  private refreshing: Observable<string> | null = null;
  private readonly blockedUntil = signal(0);
  private blockedStatus = 503;
  readonly user = signal<User | null>(null);
  readonly tenant = signal<Tenant | null>(null);

  get signedIn(): boolean { return this.session() !== null; }
  get token(): string | null { return this.session()?.accessToken ?? null; }

  login(credentials: Credentials): Observable<void> {
    const generation = ++this.generation;
    return this.http.post<Tokens>('/api/auth/login', credentials).pipe(
      tap(tokens => {
        if (generation !== this.generation) throw new Error('Session changed');
        this.setSession(tokens);
      }),
      switchMap(() => this.loadIdentity()),
      catchError(error => {
        if (generation === this.generation) this.clear();
        return throwError(() => error);
      }),
    );
  }

  loadIdentity(): Observable<void> {
    return defer(() => {
      const generation = this.generation;
      const current = () => { if (generation !== this.generation) throw new Error('Session changed'); };
      return this.http.get<User>('/api/auth/me').pipe(
        tap(user => { current(); this.user.set(user); }),
        switchMap(() => this.http.get<Tenant>('/api/auth/tenant')),
        tap(tenant => { current(); this.tenant.set(tenant); }),
        map(() => undefined),
      );
    });
  }

  accessToken(): Observable<string> {
    return defer(() => {
      if (!this.session()) return throwError(() => new HttpErrorResponse({ status: 401 }));
      // A small margin avoids sending a token that expires during the request.
      return Date.now() < this.expiresAt - 15_000 ? of(this.session()!.accessToken) : this.refresh();
    });
  }

  refreshAfter(rejectedToken: string): Observable<string> {
    // Another request may already have rotated the token while this 401 was in flight.
    return this.token && this.token !== rejectedToken ? of(this.token) : this.refresh();
  }

  private refresh(): Observable<string> {
    if (this.refreshing) return this.refreshing;
    const tokens = this.session();
    if (!tokens || Date.parse(tokens.refreshExpiresAt) <= Date.now()) {
      this.expire();
      return throwError(() => new HttpErrorResponse({ status: 401 }));
    }
    if (this.blockedUntil() > Date.now()) {
      return throwError(() => new HttpErrorResponse({ status: this.blockedStatus,
        headers: new HttpHeaders({ 'Retry-After': String(Math.ceil((this.blockedUntil() - Date.now()) / 1000)) }) }));
    }
    const generation = this.generation;
    // refCount:false keeps one rotation alive even when a caller navigates away.
    this.refreshing = this.http.post<Tokens>('/api/auth/refresh', { refreshToken: tokens.refreshToken }).pipe(
      map(next => {
        if (generation !== this.generation) throw new HttpErrorResponse({ status: 401 });
        this.setSession(next);
        return next.accessToken;
      }),
      catchError(error => {
        if (generation === this.generation) {
          if (error instanceof HttpErrorResponse && (error.status === 429 || error.status === 503)) {
            this.blockedStatus = error.status;
            this.blockedUntil.set(Date.now() + retryDelay(error) * 1000);
          } else {
            // A lost refresh response is ambiguous: do not replay a possibly consumed credential.
            this.expire();
          }
        }
        return throwError(() => error);
      }),
      finalize(() => { if (generation === this.generation) this.refreshing = null; }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.refreshing;
  }

  logout(): Observable<void> {
    const session = this.session();
    const refresh = this.refreshing;
    const generation = this.generation;
    // Wait for any rotation before revoking, so logout cannot race its successor.
    const ready = refresh ? refresh.pipe(catchError(() => of(null))) : of(null);
    return ready.pipe(
      switchMap(() => {
        const current = generation === this.generation ? this.session() ?? session : session;
        return current ? this.http.post<void>('/api/auth/logout', { refreshToken: current.refreshToken }) : of(undefined);
      }),
      finalize(() => { if (generation === this.generation) this.clear(); }),
    );
  }

  forgot(tenantSlug: string, email: string): Observable<RecoveryRequest> {
    return this.http.post<RecoveryRequest>('/api/auth/password/forgot', { tenantSlug, email });
  }
  reset(challengeId: string, code: string, password: string): Observable<void> {
    return this.http.post<void>('/api/auth/password/reset', { challengeId, code, password }).pipe(tap(() => this.clear()));
  }
  expire(): void {
    this.clear();
    void this.router.navigate(['/login'], { queryParams: { expired: '1' } });
  }
  private setSession(tokens: Tokens): void {
    this.session.set(tokens);
    this.expiresAt = Date.now() + tokens.expiresIn * 1000;
    this.blockedUntil.set(0);
  }
  private clear(): void {
    this.generation++;
    this.session.set(null);
    this.user.set(null);
    this.tenant.set(null);
    this.expiresAt = 0;
    this.refreshing = null;
    this.blockedUntil.set(0);
  }
}

export function retryDelay(error: HttpErrorResponse): number {
  const value = error.headers.get('Retry-After');
  if (value && /^\d+$/.test(value)) return Math.max(1, Number(value));
  const date = value ? Date.parse(value) : NaN;
  return Number.isFinite(date) ? Math.max(1, Math.ceil((date - Date.now()) / 1000)) : 30;
}
