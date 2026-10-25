import { Routes } from '@angular/router';
import { authGuard } from './auth/auth.guard';

export const routes: Routes = [
  { path: 'activate', loadComponent: () => import('./auth/activate').then(m => m.Activate) },
  { path: 'invitations', canActivate: [authGuard], loadComponent: () => import('./members/invitations').then(m => m.Invitations) },
  { path: 'login', loadComponent: () => import('./auth/login').then(m => m.Login) },
  { path: 'forgot-password', loadComponent: () => import('./auth/recovery').then(m => m.Recovery) },
  { path: 'workspace', canActivate: [authGuard], loadComponent: () => import('./workspace/workspace').then(m => m.Workspace) },
  { path: 'members', canActivate: [authGuard], loadComponent: () => import('./members/members').then(m => m.Members) },
  { path: 'campaigns', canActivate: [authGuard], loadComponent: () => import('./campaigns/campaign-list').then(m => m.CampaignList) },
  { path: 'campaigns/new', canActivate: [authGuard], loadComponent: () => import('./campaigns/campaign-create').then(m => m.CampaignCreate) },
  { path: 'campaigns/:id', canActivate: [authGuard], loadComponent: () => import('./campaigns/campaign-detail').then(m => m.CampaignDetail) },
  { path: 'reports', canActivate: [authGuard], loadComponent: () => import('./reports/report-list').then(m => m.ReportList) },
  { path: 'reports/:id', canActivate: [authGuard], loadComponent: () => import('./reports/report-detail').then(m => m.ReportDetail) },
  { path: '', pathMatch: 'full', redirectTo: 'workspace' },
  { path: '**', redirectTo: 'workspace' },
];
