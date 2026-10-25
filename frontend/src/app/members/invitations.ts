import { Component, DestroyRef, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, switchMap, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { RequestState } from '../auth/request-state';
import { Shell } from '../workspace/shell';
import { Role, roles, roleLabels } from './member.api';

interface Invitation { id:string; email:string; displayName:string; role:Role; expiresAt:string; state:string; mailState:string; }
@Component({imports:[Shell,ReactiveFormsModule,RouterLink],template: `
<app-shell section="Lời mời"><h1>Mời thành viên</h1><p>Gửi link dùng một lần, có hiệu lực 24 giờ. Thành viên tự đặt mật khẩu và xác minh email khi kích hoạt.</p>
<a routerLink="/members">Quay lại thành viên</a>
@if (allowed()) {
<form class="business-form" [formGroup]="form" (ngSubmit)="invite()">
<label for="invite-name">Tên người được mời</label><input id="invite-name" formControlName="displayName" maxlength="100">
<label for="invite-email">Email nhận lời mời</label><input id="invite-email" type="email" formControlName="email" maxlength="254">
<label for="invite-role">Quyền được cấp</label><select id="invite-role" formControlName="role">@for(role of roles;track role){<option [value]="role">{{labels[role]}}</option>}</select>
<p class="field-hint">Gửi lại cùng email sẽ thay thế link trước đó. Kiểm tra quyền trước khi gửi.</p>
<button class="primary-button compact-button" [disabled]="form.invalid || state.blocked || uncertain()">Gửi lời mời</button></form>
<button class="secondary-button" [disabled]="state.blocked" (click)="load()">Kiểm tra lời mời</button>
<div class="table-wrap"><table><caption>100 lời mời gần nhất</caption><thead><tr><th>Email</th><th>Quyền</th><th>Lời mời / Email</th><th>Thao tác</th></tr></thead><tbody>
@for(item of items();track item.id){<tr><td>{{item.displayName}} · {{item.email}}</td><td>{{item.role}}</td><td>{{item.state}} / {{item.mailState}}</td><td>@if(item.state==='PENDING'){<button class="secondary-button" [disabled]="state.blocked || uncertain()" (click)="revoke(item)">Thu hồi {{item.email}}</button>}</td></tr>}
</tbody></table></div>}
@if(state.error()){<p class="notice error" role="alert">{{state.error()}}</p>}
@if(feedback()){<p class="notice" role="status">{{feedback()}}</p>}
@if(state.remaining()){<p role="status">Thử lại sau {{state.remaining()}} giây.</p>}
</app-shell>`})
export class Invitations {
  private readonly http=inject(HttpClient);
  private readonly auth=inject(AuthService);
  private readonly destroy=inject(DestroyRef);
  readonly state=new RequestState(); readonly items=signal<Invitation[]>([]); readonly allowed=signal(false);
  readonly feedback=signal(''); readonly uncertain=signal(false); readonly roles=roles; readonly labels=roleLabels;
  readonly form=inject(NonNullableFormBuilder).group({email:['',[Validators.required,Validators.email,Validators.maxLength(254)]],displayName:['',[Validators.required,Validators.maxLength(100)]],role:['VIEWER' as Role,Validators.required]});
  constructor(){this.load();}
  load():void {
    if(!this.state.begin())return;
    this.allowed.set(false); this.items.set([]);
    this.auth.loadIdentity().pipe(switchMap(()=>this.auth.user()?.role==='ADMIN'?this.http.get<Invitation[]>('/api/auth/users/invitations'):throwError(()=>new HttpErrorResponse({status:403}))),takeUntilDestroyed(this.destroy),finalize(()=>this.state.busy.set(false))).subscribe({next:items=>{this.allowed.set(true);this.items.set(items);this.uncertain.set(false);},error:e=>this.state.fail(e,'Đăng nhập lại để tiếp tục.')});
  }
  invite():void {
    this.form.controls.email.setValue(this.form.controls.email.value.trim().toLowerCase());
    this.form.controls.displayName.setValue(this.form.controls.displayName.value.trim());
    if(!this.allowed() || this.uncertain() || this.form.invalid || !this.state.begin())return;
    this.http.post('/api/auth/users/invitations',this.form.getRawValue()).pipe(takeUntilDestroyed(this.destroy),finalize(()=>this.state.busy.set(false))).subscribe({next:()=>{this.form.reset();this.feedback.set('Lời mời đã được xếp hàng gửi. Kiểm tra trạng thái email bên dưới.');this.state.busy.set(false);this.load();},error:e=>this.failure(e)});
  }
  revoke(item:Invitation):void {
    if(!this.allowed() || this.uncertain() || !this.state.begin())return;
    this.http.delete('/api/auth/users/invitations/'+encodeURIComponent(item.id)).pipe(takeUntilDestroyed(this.destroy),finalize(()=>this.state.busy.set(false))).subscribe({next:()=>{this.feedback.set('Đã thu hồi link mời.');this.state.busy.set(false);this.load();},error:e=>this.failure(e)});
  }
  private failure(e:unknown):void {
    this.state.fail(e,'Đăng nhập lại để tiếp tục.');
    if(e instanceof HttpErrorResponse && (e.status===0 || e.status>=500)){this.uncertain.set(true);this.state.error.set('Chưa xác nhận được kết quả. Kiểm tra lời mời trước khi gửi lại.');}
    if(e instanceof HttpErrorResponse && e.status===403){this.allowed.set(false);this.items.set([]);}
  }
}
