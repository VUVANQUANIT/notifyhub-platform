import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { RequestState } from '../auth/request-state';

@Component({ selector: 'app-workspace', imports: [RouterLink], templateUrl: './workspace.html' })
export class Workspace {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly state = new RequestState();
  readonly logoutState = new RequestState();
  readonly refreshed = signal(false);
  readonly roleNames = { ADMIN: 'Quản trị viên', OPERATOR: 'Điều phối viên', VIEWER: 'Người xem' };

  reload(): void {
    if (!this.state.begin()) return;
    this.refreshed.set(false);
    this.auth.loadIdentity().pipe(finalize(() => this.state.busy.set(false))).subscribe({
      next: () => this.refreshed.set(true),
      error: error => this.state.fail(error, 'Phiên làm việc đã kết thúc.'),
    });
  }
  logout(): void {
    if (!this.logoutState.begin()) return;
    this.auth.logout().pipe(finalize(() => {
      this.logoutState.busy.set(false);
      if (!this.auth.signedIn) void this.router.navigate(['/login']);
    })).subscribe({ error: () => { /* Local session is always cleared; server credentials expire independently. */ } });
  }
}
