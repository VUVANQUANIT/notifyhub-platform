export type CampaignStatus = 'DRAFT' | 'SCHEDULED' | 'RUNNING' | 'COMPLETED' | 'FAILED';
export interface Page<T> { items: T[]; totalElements: number; page: number; size: number; }
export interface Campaign { id: string; name: string; channel: 'EMAIL' | 'SMS'; subject: string | null; body: string; scheduledAt: string | null; status: CampaignStatus; createdAt: string; }
export interface CreateCampaign { name: string; channel: 'EMAIL' | 'SMS'; subject: string | null; body: string; scheduledAt: string | null; }
export interface Recipient { id: string; destination: string; personalization: Record<string, string>; }
export interface ImportResult { id: string; status: 'COMPLETED' | 'REJECTED' | 'PROCESSING'; summary: { totalRows: number; validRows: number; duplicateRows: number; invalidRows: number }; errors: { rowNumber: number; message: string }[]; rejectionReason: string | null; }
export const statusLabels: Record<CampaignStatus, string> = { DRAFT: 'Bản nháp', SCHEDULED: 'Đã lên lịch', RUNNING: 'Đang gửi', COMPLETED: 'Hoàn thành', FAILED: 'Có gửi thất bại' };
