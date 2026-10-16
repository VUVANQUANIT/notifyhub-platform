import { Component, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { RequestState } from './request-state';

@Component({imports:[ReactiveFormsModule,RouterLink],template:`
<main class="auth-layout"><section class="auth-form-panel"><h1>Kích hoạt tài khoản</h1>
@if(done()){<p role="status">Đã xác minh email và tạo tài khoản. Đăng nhập bằng mã tổ chức và email trong thư mời.</p>}
@else if(valid()){
<p>Đặt mật khẩu riêng để hoàn tất lời mời. Ít nhất 15 ký tự.</p>
<form class="business-form" [formGroup]="form" (ngSubmit)="accept()">
<label for="activate-password">Mật khẩu mới</label><input id="activate-password" type="password" formControlName="password" autocomplete="new-password" maxlength="128">
<label for="activate-confirm">Nhập lại mật khẩu</label><input id="activate-confirm" type="password" formControlName="confirm" autocomplete="new-password" maxlength="128">
<button class="primary-button compact-button" [disabled]="form.invalid || state.blocked">Kích hoạt tài khoản</button></form>
}@else{<p role="alert">Thiếu link mời hợp lệ. Mở lại link trong email hoặc nhờ ADMIN gửi lời mời mới.</p>}
@if(state.error()){<p class="notice error" role="alert">{{state.error()}}</p>}
@if(state.remaining()){<p role="status">Thử lại sau {{state.remaining()}} giây.</p>}
<a routerLink="/login">Đăng nhập</a></section></main>`})
export class Activate {
  private readonly http=inject(HttpClient); private token='';
  readonly state=new RequestState(); readonly done=signal(false); readonly valid=signal(false);
  readonly form=inject(NonNullableFormBuilder).group({password:['',[Validators.required,Validators.maxLength(128),c=>Array.from(String(c.value)).length>=15?null:{length:true}]],confirm:['',Validators.required]},{validators:c=>c.get('password')?.value===c.get('confirm')?.value?null:{mismatch:true}});
  constructor(){
    const view=inject(DOCUMENT).defaultView;
    this.token=view?.location.hash.slice(1)??'';
    this.valid.set(/^[A-Za-z0-9_-]{43}$/.test(this.token));
    // Fragment avoids server/proxy logs and referrers; remove it from this history entry immediately.
    view?.history.replaceState(view.history.state,'',view.location.pathname);
  }
  accept():void {
    if(!this.valid() || this.form.invalid || !this.state.begin())return;
    const password=this.form.controls.password.value; this.form.reset();
    this.http.post('/api/auth/invitations/accept',{token:this.token,password}).pipe(finalize(()=>this.state.busy.set(false))).subscribe({next:()=>{this.done.set(true);this.token='';},error:e=>this.state.fail(e,'Link đã hết hạn, đã dùng hoặc bị thu hồi. Nếu đã kích hoạt, hãy thử đăng nhập; nếu chưa, nhờ ADMIN gửi lại.')});
  }
}
