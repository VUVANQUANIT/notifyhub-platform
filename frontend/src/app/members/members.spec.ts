import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { AuthService } from '../auth/auth.service';
import { User } from '../auth/auth.models';
import { Members } from './members';

const admin: User = {id:'a1',tenantId:'t1',email:'admin@example.com',displayName:'Admin',role:'ADMIN',enabled:true};
const member: User = {...admin,id:'u2',email:'member@example.com',displayName:'Member',role:'VIEWER'};
describe('Member writes and uncertain outcomes', () => {
  let http: HttpTestingController;
  const user = signal<User | null>(admin);
  const expire = vi.fn();
  beforeEach(() => {
    user.set(admin); expire.mockClear();
    // JSDOM omits the native dialog API; Chromium tests cover actual focus/cancel behavior.
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {configurable:true,value:vi.fn()});
    TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting(),provideRouter([]),
      {provide:AuthService,useValue:{user,tenant:signal({name:'Demo'}),loadIdentity:()=>of(undefined),expire}}]});
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { http.verify(); vi.restoreAllMocks(); });
  function createComponent() {
    const fixture = TestBed.createComponent(Members); fixture.detectChanges();
    if (user()?.role === 'ADMIN') http.expectOne('/api/auth/users?page=0&size=20').flush({items:[admin,member],total:2,page:0,size:20});
    return fixture.componentInstance;
  }
  function validForm(component: Members, password = 'a long initial passphrase') {
    component.beginCreate(); component.form.setValue({email:' MEMBER@example.com ',displayName:' Member ',role:'VIEWER',password,confirm:password});
  }
  it('blocks member reads and writes for a non-ADMIN even on direct navigation', () => {
    user.set({...admin,role:'OPERATOR'}); const component = createComponent();
    component.beginCreate(); component.create(); http.expectNone(request=>request.url === '/api/auth/users'); expect(component.canManage).toBe(false);
  });
  it('uses code points for password minimum, normalizes identity and immediately clears password fields', () => {
    const component = createComponent(); validForm(component,'😀'.repeat(8)); component.create(); http.expectNone(request=>request.method==='POST');
    const password = '😀'.repeat(9)+'abcdef'; component.form.patchValue({password,confirm:password}); component.create();
    const request = http.expectOne('/api/auth/users'); expect(request.request.body).toEqual({email:'member@example.com',displayName:'Member',role:'VIEWER',password});
    expect(component.form.controls.password.value).toBe(''); expect(component.form.controls.confirm.value).toBe('');
    request.flush(member,{status:201,statusText:'Created'}); http.expectOne('/api/auth/users?page=0&size=20').flush({items:[admin,member],total:2,page:0,size:20});
  });
  it('never resubmits an ambiguous create before a successful list check', () => {
    const component = createComponent(); validForm(component); component.create(); http.expectOne('/api/auth/users').flush({}, {status:500,statusText:'Lost response'});
    expect(component.uncertain()).toBe(true); component.create(); http.expectNone(request=>request.method==='POST');
    component.load(); http.expectOne('/api/auth/users?page=0&size=20').flush({items:[admin,member],total:2,page:0,size:20});
    expect(component.uncertain()).toBe(false); expect(component.showCreate()).toBe(false);
  });
  it('surfaces last-admin conflict without expiring a session or accepting the rejected role', () => {
    const component = createComponent(); component.selection.set({member:admin,kind:'role'}); component.selectedRole='VIEWER'; component.confirm();
    http.expectOne('/api/auth/users/a1/role').flush({}, {status:409,statusText:'Conflict'});
    expect(component.write.error()).toContain('ADMIN đang hoạt động'); expect(expire).not.toHaveBeenCalled(); expect(user()?.role).toBe('ADMIN');
  });
  it('drops the local session immediately after a confirmed self-demotion', () => {
    const component = createComponent(); component.selection.set({member:admin,kind:'role'}); component.selectedRole='VIEWER'; component.confirm();
    http.expectOne('/api/auth/users/a1/role').flush({...admin,role:'VIEWER'});
    expect(expire).toHaveBeenCalledOnce(); http.expectNone(request=>request.url==='/api/auth/users');
  });
  it('requires reconciliation after an ambiguous status change instead of blindly toggling again', () => {
    const component = createComponent(); component.selection.set({member,kind:'enabled'}); component.confirm();
    const request=http.expectOne('/api/auth/users/u2/enabled'); expect(request.request.body).toEqual({enabled:false}); request.flush({}, {status:500,statusText:'Lost response'});
    component.confirm(); expect(component.uncertain()).toBe(true); expect(component.selection()).toBeNull(); http.expectNone(request=>request.method==='PUT');
  });
});
