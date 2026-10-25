import { Component, DestroyRef, ElementRef, ViewChild, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { AbstractControl, FormsModule, NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, switchMap, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { User } from '../auth/auth.models';
import { RequestState } from '../auth/request-state';
import { Shell } from '../workspace/shell';
import { MemberApi, Role, UserPage, roleLabels, roles } from './member.api';

function passwordLength(control: AbstractControl) {
  return Array.from(String(control.value)).length >= 15 ? null : { passwordLength: true };
}
function matchingPasswords(control: AbstractControl) {
  return control.get('password')?.value === control.get('confirm')?.value ? null : { mismatch: true };
}
type Change = { member: User; kind: 'role' | 'enabled' };

@Component({ imports: [Shell, FormsModule, ReactiveFormsModule, RouterLink], templateUrl: './members.html' })
export class Members {
  readonly auth = inject(AuthService);
  private readonly api = inject(MemberApi);
  private readonly destroy = inject(DestroyRef);
  private readonly fb = inject(NonNullableFormBuilder);
  @ViewChild('confirmation', {static: true}) private dialog!: ElementRef<HTMLDialogElement>;
  readonly read = new RequestState();
  readonly write = new RequestState();
  readonly result = signal<UserPage | null>(null);
  readonly verified = signal(false);
  readonly showCreate = signal(false);
  readonly selection = signal<Change | null>(null);
  readonly uncertain = signal(false);
  readonly feedback = signal('');
  readonly roles = roles;
  readonly labels = roleLabels;
  selectedRole: Role = 'VIEWER';
  private checkPage = 0;
  readonly form = this.fb.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    displayName: ['', [Validators.required, Validators.maxLength(100)]],
    role: ['VIEWER' as Role, Validators.required],
    password: ['', [Validators.required, passwordLength, Validators.maxLength(128)]],
    confirm: ['', Validators.required],
  }, {validators: matchingPasswords});

  get canManage() { return this.verified() && this.auth.user()?.role === 'ADMIN'; }
  get blocked() { return !this.canManage || this.read.busy() || this.write.blocked || this.uncertain(); }
  constructor() { this.load(0); }

  load(page = this.uncertain() ? this.checkPage : this.result()?.page ?? 0): void {
    if (this.write.busy() || !this.read.begin()) return;
    this.result.set(null); this.verified.set(false);
    this.auth.loadIdentity().pipe(
      switchMap(() => this.auth.user()?.role === 'ADMIN' && this.auth.user()?.enabled
        ? this.api.list(page) : throwError(() => new HttpErrorResponse({status: 403}))),
      takeUntilDestroyed(this.destroy), finalize(() => this.read.busy.set(false)),
    ).subscribe({
      next: result => {
        this.result.set(result); this.verified.set(true);
        if (this.uncertain()) {
          this.uncertain.set(false); this.cancelCreate();
          this.feedback.set('Danh sách đã được kiểm tra lại. Xem email và trạng thái hiện tại trước khi gửi yêu cầu mới.');
        }
      },
      error: error => {
        this.read.fail(error, 'Phiên làm việc đã kết thúc.');
        if (error instanceof HttpErrorResponse && error.status === 403) this.loseAccess();
      },
    });
  }

  beginCreate(): void {
    if (this.blocked) return;
    this.form.reset(); this.write.error.set(''); this.feedback.set(''); this.showCreate.set(true);
  }
  cancelCreate(): void { this.form.reset(); this.showCreate.set(false); }
  create(): void {
    if (this.blocked || !this.showCreate()) return;
    this.form.controls.email.setValue(this.form.controls.email.value.trim().toLowerCase());
    this.form.controls.displayName.setValue(this.form.controls.displayName.value.trim());
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    if (!this.write.begin()) return;
    const {confirm: _confirm, ...body} = this.form.getRawValue();
    this.checkPage = Math.floor((this.result()?.total ?? 0) / 20);
    // Passwords stay only in the in-flight request, never in page feedback or retained form state.
    this.form.controls.password.reset(); this.form.controls.confirm.reset();
    this.feedback.set('');
    this.api.create(body).pipe(takeUntilDestroyed(this.destroy), finalize(() => this.write.busy.set(false))).subscribe({
      next: member => {
        this.cancelCreate(); this.feedback.set(`Đã tạo tài khoản ${member.email}.`);
        this.write.busy.set(false); this.load(this.checkPage);
      },
      error: error => this.failedWrite(error, 'create'),
    });
  }

  edit(member: User, kind: Change['kind']): void {
    if (this.blocked) return;
    this.cancelCreate(); this.feedback.set(''); this.write.error.set(''); this.selectedRole = member.role;
    this.selection.set({ member: {...member}, kind }); this.dialog.nativeElement.showModal();
  }
  cancel(event?: Event): void {
    if (this.write.busy()) { event?.preventDefault(); return; }
    this.closeDialog();
  }
  private closeDialog(): void { this.dialog?.nativeElement.close(); this.selection.set(null); }
  confirm(): void {
    const change = this.selection();
    if (!change || this.blocked || (change.kind === 'role' && change.member.role === this.selectedRole) || !this.write.begin()) return;
    const request = change.kind === 'role' ? this.api.role(change.member.id, this.selectedRole) : this.api.enabled(change.member.id, !change.member.enabled);
    this.checkPage = this.result()?.page ?? 0;
    request.pipe(takeUntilDestroyed(this.destroy), finalize(() => this.write.busy.set(false))).subscribe({
      next: member => {
        this.closeDialog();
        if (member.id === this.auth.user()?.id) {
          // The server has revoked this user's refresh sessions; discard the old local role/token too.
          this.auth.expire(); return;
        }
        this.feedback.set(`Đã cập nhật tài khoản ${member.email}.`);
        this.write.busy.set(false); this.load(this.checkPage);
      },
      error: error => this.failedWrite(error, 'change'),
    });
  }
  private failedWrite(error: unknown, operation: 'create' | 'change'): void {
    this.write.fail(error, 'Phiên làm việc đã kết thúc.');
    if (!(error instanceof HttpErrorResponse)) return;
    if (error.status === 409) {
      this.write.error.set(operation === 'create' ? 'Email đã tồn tại trong tổ chức. Kiểm tra danh sách trước khi tạo lại.'
        : 'Không thể thay đổi: tổ chức phải còn ít nhất một ADMIN đang hoạt động. Làm mới danh sách nếu quyền vừa thay đổi.');
    } else if (error.status === 404) {
      this.write.error.set('Không tìm thấy thành viên trong tổ chức này. Hãy làm mới danh sách.');
    } else if (error.status === 403) {
      this.loseAccess();
      this.auth.loadIdentity().pipe(takeUntilDestroyed(this.destroy)).subscribe({error: () => { /* Normal auth handling clears expired sessions. */ }});
    } else if (error.status === 0 || error.status >= 500) {
      this.uncertain.set(true); this.closeDialog();
      this.write.error.set('Chưa xác nhận được kết quả. Yêu cầu có thể đã thành công; hãy kiểm tra danh sách trước khi thực hiện lại.');
    }
  }
  private loseAccess(): void {
    this.verified.set(false); this.result.set(null); this.closeDialog(); this.cancelCreate();
  }
}
