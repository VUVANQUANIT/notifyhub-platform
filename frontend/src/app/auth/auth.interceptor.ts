import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

const PUBLIC = new Set([
  '/api/auth/login', '/api/auth/register', '/api/auth/refresh', '/api/auth/logout',
  '/api/auth/password/forgot', '/api/auth/password/reset', '/api/auth/invitations/accept',
]);

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  // Never attach credentials to external URLs or unrelated assets.
  const path = request.url.split('?')[0];
  if (!path.startsWith('/api/') || PUBLIC.has(path)) return next(request);
  const auth = inject(AuthService);
  return auth.accessToken().pipe(switchMap(token => {
    const authorized = request.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
    return next(authorized).pipe(catchError(error => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) return throwError(() => error);
      return auth.refreshAfter(token).pipe(switchMap(rotated =>
        next(request.clone({ setHeaders: { Authorization: `Bearer ${rotated}` } })).pipe(catchError(retryError => {
          if (retryError instanceof HttpErrorResponse && retryError.status === 401) auth.expire();
          return throwError(() => retryError);
        })),
      ));
    }));
  }));
};
