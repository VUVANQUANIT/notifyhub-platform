import { Component, DestroyRef, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, distinctUntilChanged, finalize, forkJoin, map } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { RequestState } from '../auth/request-state';
import { Shell } from '../workspace/shell';
import { CampaignApi } from './campaign.api';
import { Campaign, ImportResult, Page, Recipient, statusLabels } from './campaign.models';

@Component({ imports: [Shell, DatePipe, RouterLink], templateUrl: './campaign-detail.html' })
export class CampaignDetail {
  private readonly auth = inject(AuthService);
  private readonly api = inject(CampaignApi);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  private fetching: Subscription | null = null;
  private acting: Subscription | null = null;
  private id = '';
  private startKey = '';
  readonly state = new RequestState();
  readonly action = new RequestState();
  readonly campaign = signal<Campaign | null>(null);
  readonly recipients = signal<Page<Recipient> | null>(null);
  readonly imported = signal<ImportResult | null>(null);
  readonly feedback = signal('');
  readonly labels = statusLabels;
  file: File | null = null;
  get canWrite() { return this.auth.user()?.role === 'ADMIN' || this.auth.user()?.role === 'OPERATOR'; }
  constructor() {
    this.route.paramMap.pipe(map(params => params.get('id')!), distinctUntilChanged(), takeUntilDestroyed(this.destroy)).subscribe(id => {
      this.fetching?.unsubscribe(); this.acting?.unsubscribe(); this.id = id; this.startKey = crypto.randomUUID();
      this.campaign.set(null); this.recipients.set(null); this.imported.set(null); this.feedback.set(''); this.file = null;
      this.state.busy.set(false); this.load(0);
    });
    this.destroy.onDestroy(() => { this.fetching?.unsubscribe(); this.acting?.unsubscribe(); });
  }
  load(page: number): void {
    if (this.action.busy() || !this.state.begin()) return;
    this.fetching = forkJoin({ campaign: this.api.get(this.id), recipients: this.api.recipients(this.id, page) }).pipe(finalize(() => this.state.busy.set(false))).subscribe({
      next: value => { this.campaign.set(value.campaign); this.recipients.set(value.recipients); },
      error: error => this.state.fail(error, 'Phiên làm việc đã kết thúc.'),
    });
  }
  selectFile(event: Event): void {
    const input = event.target as HTMLInputElement; const file = input.files?.[0] ?? null;
    this.file = null; this.action.error.set('');
    if (file && file.size > 2 * 1024 * 1024) { this.action.error.set('CSV phải nhỏ hơn hoặc bằng 2 MiB.'); input.value = ''; return; }
    this.file = file;
  }
  importCsv(): void {
    if (!this.canWrite || this.campaign()?.status !== 'DRAFT' || !this.file || this.state.busy() || !this.action.begin()) return;
    this.imported.set(null); this.feedback.set('');
    this.acting = this.api.importCsv(this.id, this.file).pipe(takeUntilDestroyed(this.destroy), finalize(() => this.action.busy.set(false))).subscribe({
      next: value => { this.imported.set(value); this.action.busy.set(false); this.load(0); },
      error: error => this.action.fail(error, 'Phiên làm việc đã kết thúc.'),
    });
  }
  start(): void {
    if (!this.canWrite || this.campaign()?.status !== 'DRAFT' || !this.recipients()?.totalElements || this.state.busy() || !this.action.begin()) return;
    this.acting = this.api.start(this.id, this.startKey).pipe(takeUntilDestroyed(this.destroy), finalize(() => this.action.busy.set(false))).subscribe({
      next: campaign => { this.campaign.set(campaign); this.feedback.set(campaign.status === 'SCHEDULED' ? 'Chiến dịch đã được lên lịch.' : 'Chiến dịch đã bắt đầu. Theo dõi kết quả ở báo cáo.'); },
      error: error => this.action.fail(error, 'Phiên làm việc đã kết thúc.'),
    });
  }
}
