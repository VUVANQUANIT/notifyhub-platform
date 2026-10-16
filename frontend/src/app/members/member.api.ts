import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { User } from '../auth/auth.models';

export type Role = User['role'];
export interface UserPage { items: User[]; total: number; page: number; size: number; }
export interface CreateMember { email: string; displayName: string; password: string; role: Role; }
export const roleLabels: Record<Role, string> = { ADMIN: 'Quản trị viên', OPERATOR: 'Điều phối viên', VIEWER: 'Người xem' };
export const roles: Role[] = ['ADMIN', 'OPERATOR', 'VIEWER'];

@Injectable({ providedIn: 'root' })
export class MemberApi {
  private readonly http = inject(HttpClient);
  list(page: number) { return this.http.get<UserPage>('/api/auth/users', { params: { page, size: 20 } }); }
  create(body: CreateMember) { return this.http.post<User>('/api/auth/users', body); }
  role(id: string, role: Role) { return this.http.put<User>(`/api/auth/users/${encodeURIComponent(id)}/role`, { role }); }
  enabled(id: string, enabled: boolean) { return this.http.put<User>(`/api/auth/users/${encodeURIComponent(id)}/enabled`, { enabled }); }
}
