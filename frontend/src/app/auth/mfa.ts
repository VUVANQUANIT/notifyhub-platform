import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { Shell } from '../workspace/shell';
import { RequestState } from './request-state';

@Component({imports:[Shell,ReactiveFormsModule,RouterLink],template:`
<app-shell section="Bảo mật tài khoản"><h1>Xác thực hai bước</h1><p>Dùng ứng dụng xác thực hỗ trợ TOTP. Lưu mã khôi phục ở nơi riêng an toàn.</p>
@if(codes().length){<div class="notice" role="status">Đã bật MFA và thu hồi các phiên đăng nhập dài hạn. Lưu 8 mã sau trước khi rời trang: mỗi mã chỉ dùng một lần.</div><ul>@for(code of codes();track code){<li><code>{{code}}</code></li>}</ul><p>Chờ mã 6 số mới trước khi đăng nhập; mã vừa xác nhận không thể dùng lại.</p><a routerLink="/login">Đăng nhập lại</a>}
@else if(disabled()){<p role="status">Đã tắt MFA và thu hồi các phiên dài hạn.</p><a routerLink="/login">Đăng nhập lại</a>}
@else {
<button class="secondary-button" [disabled]="state.blocked" (click)="load()">Kiểm tra trạng thái MFA</button>
@if(loaded()){
<p>MFA: {{enabled()?'Đang bật':'Chưa bật'}}</p>
<form class="business-form" [formGroup]="form" (ngSubmit)="enabled()?disable():enrollment()?confirm():begin()">
@if(!enrollment() || enabled()){<label for="mfa-password">Mật khẩu hiện tại</label><input id="mfa-password" type="password" formControlName="password" autocomplete="current-password" maxlength="128">}
@if(enrollment();as setup){<p>Thêm khóa sau vào ứng dụng xác thực, tài khoản NotifyHub. Khóa chỉ hiển thị trên trang này.</p><code class="identifier">{{setup.secret}}</code><p>Nhập mã từ ứng dụng trong vòng 10 phút.</p>}
@if(enrollment() || enabled()){<label for="mfa-code">Mã xác thực hoặc mã khôi phục</label><input id="mfa-code" formControlName="code" autocomplete="one-time-code" maxlength="43">}
<button class="primary-button compact-button" [disabled]="state.blocked">{{enabled()?'Tắt MFA và thu hồi phiên':enrollment()?'Xác nhận bật MFA':'Thiết lập MFA'}}</button></form>}}
@if(state.error()){<p class="notice error" role="alert">{{state.error()}}</p>}
@if(state.remaining()){<p role="status">Thử lại sau {{state.remaining()}} giây.</p>}
</app-shell>`})
export class Mfa {
  private readonly http=inject(HttpClient); readonly state=new RequestState();
  readonly enabled=signal(false);readonly loaded=signal(false);readonly codes=signal<string[]>([]);readonly disabled=signal(false);
  readonly enrollment=signal<{secret:string;uri:string;expiresAt:string}|null>(null);
  readonly form=inject(NonNullableFormBuilder).group({password:['',Validators.maxLength(128)],code:['',Validators.maxLength(43)]});
  constructor(){this.load();}
  load():void {if(!this.state.begin())return;this.http.get<{enabled:boolean}>('/api/auth/mfa').pipe(finalize(()=>this.state.busy.set(false))).subscribe({next:r=>{this.enabled.set(r.enabled);this.loaded.set(true);},error:e=>this.state.fail(e,'Đăng nhập lại để tiếp tục.')});}
  begin():void {
    const password=this.form.controls.password.value;if(!password || !this.state.begin())return;this.form.reset();
    this.http.post<{secret:string;uri:string;expiresAt:string}>('/api/auth/mfa/enrollment',{password}).pipe(finalize(()=>this.state.busy.set(false))).subscribe({next:r=>this.enrollment.set(r),error:e=>this.state.fail(e,'Mật khẩu không hợp lệ.')});
  }
  confirm():void {
    const code=this.form.controls.code.value;if(!/^\d{6}$/.test(code) || !this.state.begin())return;this.form.reset();
    this.http.post<{recoveryCodes:string[]}>('/api/auth/mfa/confirm',{code}).pipe(finalize(()=>this.state.busy.set(false))).subscribe({next:r=>{this.codes.set(r.recoveryCodes);this.enrollment.set(null);},error:e=>this.state.fail(e,'Mã không hợp lệ hoặc đã hết hạn. Kiểm tra thời gian trên thiết bị.')});
  }
  disable():void {
    const body=this.form.getRawValue();if(!body.password || !body.code || !this.state.begin())return;this.form.reset();
    this.http.post('/api/auth/mfa/disable',body).pipe(finalize(()=>this.state.busy.set(false))).subscribe({next:()=>this.disabled.set(true),error:e=>this.state.fail(e,'Mật khẩu hoặc mã không hợp lệ. Mã đã dùng không thể dùng lại.')});
  }
}
