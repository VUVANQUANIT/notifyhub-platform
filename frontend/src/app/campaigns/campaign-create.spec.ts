import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { beforeEach, afterEach, describe, it, expect } from 'vitest';
import { AuthService } from '../auth/auth.service';
import { CampaignCreate } from './campaign-create';

describe('Campaign creation after ambiguous responses', () => {
  let requests: HttpTestingController;
  const user = signal({ role: 'ADMIN' });
  beforeEach(() => {
    user.set({ role: 'ADMIN' });
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), { provide: AuthService, useValue: { user } }] });
    requests = TestBed.inject(HttpTestingController);
  });
  afterEach(() => requests.verify());
  function form() {
    const component = TestBed.createComponent(CampaignCreate).componentInstance;
    component.name = 'Welcome'; component.subject = 'Hello'; component.body = 'Hi';
    return component;
  }
  it('keeps the exact key and payload after a server error without automatic replay', () => {
    const component = form(); component.create();
    const first = requests.expectOne('/api/campaigns');
    const key = first.request.headers.get('Idempotency-Key'); const body = first.request.body;
    expect(key).toBeTruthy(); first.flush({}, { status: 500, statusText: 'Failure' });
    requests.expectNone('/api/campaigns');
    component.name = 'Changed after response loss'; component.create();
    const retry = requests.expectOne('/api/campaigns');
    expect(retry.request.body).toEqual(body); expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    retry.flush({}, { status: 500, statusText: 'Failure' });
  });
  it('allows a corrected payload with a fresh key after definite validation rejection', () => {
    const component = form(); component.create(); const first = requests.expectOne('/api/campaigns'); const key = first.request.headers.get('Idempotency-Key');
    first.flush({}, { status: 400, statusText: 'Invalid' });
    expect(component.pending).toBeNull(); component.name = 'Corrected'; component.create();
    const next = requests.expectOne('/api/campaigns'); expect(next.request.body.name).toBe('Corrected'); expect(next.request.headers.get('Idempotency-Key')).not.toBe(key);
    next.flush({}, { status: 400, statusText: 'Invalid' });
  });
  it('does not send a write for VIEWER or a schedule in the past', () => {
    const component = form(); user.set({ role: 'VIEWER' }); component.create(); requests.expectNone('/api/campaigns');
    user.set({ role: 'ADMIN' }); component.scheduledAt = '2000-01-01T12:00'; component.create(); requests.expectNone('/api/campaigns');
    expect(component.state.error()).toContain('tương lai');
  });
});
