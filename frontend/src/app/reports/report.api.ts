import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Page, CampaignStatus } from '../campaigns/campaign.models';

export interface Summary { deliveries: number; sent: number; failed: number; attempts: number; }
export interface CampaignReport { campaignId: string; status: CampaignStatus; expected: number | null; sent: number; failed: number; pending: number | null; attempts: number; }
export interface Delivery { notificationId: string; recipientId: string; channel: string; status: 'SENT' | 'FAILED'; attempts: number; failureCode: string | null; occurredAt: string; }
@Injectable({ providedIn: 'root' })
export class ReportApi {
  private readonly http = inject(HttpClient);
  summary() { return this.http.get<Summary>('/api/reports/summary'); }
  campaigns(page: number) { return this.http.get<Page<CampaignReport>>('/api/reports/campaigns', { params: { page, size: 20 } }); }
  campaign(id: string) { return this.http.get<CampaignReport>(`/api/reports/campaigns/${encodeURIComponent(id)}`); }
  deliveries(id: string, page: number, status: 'SENT' | 'FAILED' | '') {
    return this.http.get<Page<Delivery>>(`/api/reports/campaigns/${encodeURIComponent(id)}/deliveries`, { params: { page, size: 20, ...(status ? {status} : {}) } });
  }
}
