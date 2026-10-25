export interface Tokens {
  tokenType: string;
  accessToken: string;
  expiresIn: number;
  refreshToken: string;
  refreshExpiresAt: string;
}
export interface Credentials { tenantSlug: string; email: string; password: string }
export interface User {
  id: string;
  tenantId: string;
  email: string;
  displayName: string;
  role: 'ADMIN' | 'OPERATOR' | 'VIEWER';
  enabled: boolean;
}
export interface Tenant { id: string; slug: string; name: string }
export interface RecoveryRequest { challengeId: string; message: string }
