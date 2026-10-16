import { Component, DestroyRef, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { RequestState } from '../auth/request-state';
import { Shell } from '../workspace/shell';
import { CampaignApi } from './campaign.api';
import { CreateCampaign } from './campaign.models';

@Component({ imports: [Shell, FormsModule, RouterLink], templateUrl: './campaign-create.html' })
export class CampaignCreate {
  private readonly auth = inject(AuthService);
  private readonly api = inject(CampaignApi);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  readonly state = new RequestState();
  name = ''; channel: 'EMAIL' | 'SMS' = 'EMAIL'; subject = ''; body = ''; scheduledAt = '';
  pending: { body: CreateCampaign; key: string } | null = null;
  get canWrite() { return this.auth.user()?.role === 'ADMIN' || this.auth.user()?.role === 'OPERATOR'; }
  create(): void {
    if (!this.canWrite || !this.state.begin()) return;
    if (!this.pending) {
      const date = this.scheduledAt ? new Date(this.scheduledAt) : null;
      if (date && (!Number.isFinite(date.getTime()) || date.getTime() <= Date.now())) {
        this.state.error.set('Chọn thời gian gửi trong tương lai.'); this.state.busy.set(false); return;
      }
      this.pending = { key: crypto.randomUUID(), body: { name: this.name.trim(), channel: this.channel, subject: this.channel === 'EMAIL' ? this.subject : null, body: this.body, scheduledAt: date?.toISOString() ?? null } };
    }
    this.api.create(this.pending.body, this.pending.key).pipe(takeUntilDestroyed(this.destroy), finalize(() => this.state.busy.set(false))).subscribe({
      next: campaign => { this.pending = null; void this.router.navigate(['/campaigns', campaign.id]); },
      error: error => {
        // Keep the exact request/key on ambiguous network or server failure; never retry automatically.
        if (error instanceof HttpErrorResponse && error.status >= 400 && error.status < 500 && error.status !== 429) this.pending = null;
        this.state.fail(error, 'Phiên làm việc đã kết thúc.');
      },
    });
  }
}
