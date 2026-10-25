import { Component, inject, input } from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { RequestState } from '../auth/request-state';

@Component({ selector: 'app-shell', imports: [RouterLink, RouterLinkActive], templateUrl: './shell.html' })
export class Shell {
  readonly section = input('Không gian làm việc');
  readonly auth = inject(AuthService);
  readonly logoutState = new RequestState();
  readonly roleNames = { ADMIN: 'Quản trị viên', OPERATOR: 'Điều phối viên', VIEWER: 'Người xem' };
  private readonly router = inject(Router);
  logout(): void {
    if (!this.logoutState.begin()) return;
    this.auth.logout().pipe(finalize(() => {
      this.logoutState.busy.set(false);
      if (!this.auth.signedIn) void this.router.navigate(['/login']);
    })).subscribe({ error: () => { /* Auth clears the local session even when revocation is unavailable. */ } });
  }
}
