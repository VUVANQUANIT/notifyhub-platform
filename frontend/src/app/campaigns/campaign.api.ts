import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Campaign, CampaignStatus, CreateCampaign, ImportResult, Page, Recipient } from './campaign.models';

@Injectable({ providedIn: 'root' })
export class CampaignApi {
  private readonly http = inject(HttpClient);
  list(page: number, status: CampaignStatus | '') {
    let params = new HttpParams().set('page', page).set('size', 20);
    if (status) params = params.set('status', status);
    return this.http.get<Page<Campaign>>('/api/campaigns', { params });
  }
  get(id: string) { return this.http.get<Campaign>(`/api/campaigns/${encodeURIComponent(id)}`); }
  create(body: CreateCampaign, key: string) { return this.http.post<Campaign>('/api/campaigns', body, { headers: { 'Idempotency-Key': key } }); }
  recipients(id: string, page: number) { return this.http.get<Page<Recipient>>(`/api/campaigns/${encodeURIComponent(id)}/recipients`, { params: { page, size: 20 } }); }
  importCsv(id: string, file: File) {
    const data = new FormData(); data.append('file', file);
    // The browser supplies the multipart boundary. Never set Content-Type manually.
    return this.http.post<ImportResult>(`/api/campaigns/${encodeURIComponent(id)}/imports`, data);
  }
  start(id: string, key: string) { return this.http.post<Campaign>(`/api/campaigns/${encodeURIComponent(id)}/start`, null, { headers: { 'Idempotency-Key': key } }); }
}
