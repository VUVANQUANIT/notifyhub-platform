import { Component, DestroyRef, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, forkJoin } from 'rxjs';
import { RequestState } from '../auth/request-state';
import { Shell } from '../workspace/shell';
import { Page, statusLabels } from '../campaigns/campaign.models';
import { ReportApi, CampaignReport, Summary } from './report.api';

@Component({ imports: [Shell, RouterLink], templateUrl: './report-list.html' })
export class ReportList {
  private readonly api = inject(ReportApi);
  private readonly destroy = inject(DestroyRef);
  readonly state = new RequestState();
  readonly summary = signal<Summary | null>(null);
  readonly result = signal<Page<CampaignReport> | null>(null);
  readonly labels = statusLabels;
  constructor() { this.load(0); }
  load(page: number): void {
    if (!this.state.begin()) return;
    this.summary.set(null); this.result.set(null);
    forkJoin({ summary: this.api.summary(), campaigns: this.api.campaigns(page) }).pipe(takeUntilDestroyed(this.destroy), finalize(() => this.state.busy.set(false))).subscribe({
      next: value => { this.summary.set(value.summary); this.result.set(value.campaigns); }, error: error => this.state.fail(error, 'Phiên làm việc đã kết thúc.'),
    });
  }
}
