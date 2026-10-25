import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DestroyRef } from '@angular/core';
import { finalize } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { RequestState } from '../auth/request-state';
import { Shell } from '../workspace/shell';
import { CampaignApi } from './campaign.api';
import { Campaign, CampaignStatus, Page, statusLabels } from './campaign.models';

@Component({ imports: [Shell, RouterLink, FormsModule, DatePipe], templateUrl: './campaign-list.html' })
export class CampaignList {
  readonly auth = inject(AuthService);
  private readonly api = inject(CampaignApi);
  private readonly destroy = inject(DestroyRef);
  readonly state = new RequestState();
  readonly result = signal<Page<Campaign> | null>(null);
  readonly labels = statusLabels;
  readonly statuses: CampaignStatus[] = ['DRAFT', 'SCHEDULED', 'RUNNING', 'COMPLETED', 'FAILED'];
  status: CampaignStatus | '' = '';
  get canWrite() { return this.auth.user()?.role === 'ADMIN' || this.auth.user()?.role === 'OPERATOR'; }
  constructor() { this.load(0); }
  load(page: number): void {
    if (!this.state.begin()) return;
    this.result.set(null);
    this.api.list(page, this.status).pipe(takeUntilDestroyed(this.destroy), finalize(() => this.state.busy.set(false))).subscribe({
      next: result => this.result.set(result), error: error => this.state.fail(error, 'Phiên làm việc đã kết thúc.'),
    });
  }
}
