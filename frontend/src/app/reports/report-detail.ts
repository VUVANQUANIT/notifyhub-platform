import { Component, DestroyRef, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, distinctUntilChanged, finalize, forkJoin, map } from 'rxjs';
import { RequestState } from '../auth/request-state';
import { Shell } from '../workspace/shell';
import { Page, statusLabels } from '../campaigns/campaign.models';
import { ReportApi, CampaignReport, Delivery } from './report.api';

@Component({ imports: [Shell, FormsModule, DatePipe, RouterLink], templateUrl: './report-detail.html' })
export class ReportDetail {
  private readonly api = inject(ReportApi);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  private fetching: Subscription | null = null;
  id = '';
  status: 'SENT' | 'FAILED' | '' = '';
  readonly state = new RequestState();
  readonly result = signal<CampaignReport | null>(null);
  readonly deliveries = signal<Page<Delivery> | null>(null);
  readonly labels = statusLabels;
  constructor() {
    this.route.paramMap.pipe(map(params => params.get('id')!), distinctUntilChanged(), takeUntilDestroyed(this.destroy)).subscribe(id => {
      this.fetching?.unsubscribe(); this.id = id; this.status = ''; this.result.set(null); this.deliveries.set(null); this.state.busy.set(false); this.load(0);
    });
    this.destroy.onDestroy(() => this.fetching?.unsubscribe());
  }
  load(page: number): void {
    if (!this.state.begin()) return;
    this.result.set(null); this.deliveries.set(null);
    this.fetching = forkJoin({ campaign: this.api.campaign(this.id), deliveries: this.api.deliveries(this.id, page, this.status) }).pipe(finalize(() => this.state.busy.set(false))).subscribe({
      next: value => { this.result.set(value.campaign); this.deliveries.set(value.deliveries); }, error: error => this.state.fail(error, 'Phiên làm việc đã kết thúc.'),
    });
  }
}
