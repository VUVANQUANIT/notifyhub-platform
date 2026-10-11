import { Routes } from '@angular/router';
import { authGuard } from './auth/auth.guard';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./auth/login').then(m => m.Login) },
  { path: 'forgot-password', loadComponent: () => import('./auth/recovery').then(m => m.Recovery) },
  { path: 'workspace', canActivate: [authGuard], loadComponent: () => import('./workspace/workspace').then(m => m.Workspace) },
  { path: '', pathMatch: 'full', redirectTo: 'workspace' },
  { path: '**', redirectTo: 'workspace' },
];
