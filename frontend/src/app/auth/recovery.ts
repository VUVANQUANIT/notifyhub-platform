import { Component, inject, signal } from '@angular/core';
import { AbstractControl, NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from './auth.service';
import { RequestState } from './request-state';

function passwordLength(control: AbstractControl): ValidationErrors | null {
  return Array.from(String(control.value)).length >= 15 ? null : { passwordLength: true };
}
function matchPasswords(control: AbstractControl): ValidationErrors | null {
  return control.get('password')?.value === control.get('confirm')?.value ? null : { mismatch: true };
}

@Component({ selector: 'app-recovery', imports: [ReactiveFormsModule, RouterLink], templateUrl: './recovery.html' })
export class Recovery {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);
  readonly requestState = new RequestState();
  readonly resetState = new RequestState();
  readonly step = signal<'request' | 'reset'>('request');
  readonly requested = signal(false);
  readonly requestForm = this.fb.group({
    tenantSlug: ['', [Validators.required, Validators.pattern(/^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
  });
  readonly resetForm = this.fb.group({
    challengeId: ['', [Validators.required, Validators.pattern(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)]],
    code: ['', [Validators.required, Validators.pattern(/^[0-9]{8}$/)]],
    password: ['', [Validators.required, passwordLength, Validators.maxLength(128)]],
    confirm: ['', Validators.required],
  }, { validators: matchPasswords });

  request(): void {
    this.requestForm.controls.tenantSlug.setValue(this.requestForm.controls.tenantSlug.value.trim().toLowerCase());
    this.requestForm.controls.email.setValue(this.requestForm.controls.email.value.trim().toLowerCase());
    if (this.requestForm.invalid) { this.requestForm.markAllAsTouched(); return; }
    if (!this.requestState.begin()) return;
    const { tenantSlug, email } = this.requestForm.getRawValue();
    this.auth.forgot(tenantSlug, email).pipe(finalize(() => this.requestState.busy.set(false))).subscribe({
      next: response => {
        this.resetForm.controls.challengeId.setValue(response.challengeId);
        this.requested.set(true);
        this.requestState.wait(60);
        this.step.set('reset');
      },
      error: error => this.requestState.fail(error, 'Không thể gửi yêu cầu. Vui lòng thử lại.'),
    });
  }

  reset(): void {
    if (this.resetForm.invalid) { this.resetForm.markAllAsTouched(); return; }
    if (!this.resetState.begin()) return;
    const { challengeId, code, password } = this.resetForm.getRawValue();
    this.auth.reset(challengeId, code, password).pipe(finalize(() => this.resetState.busy.set(false))).subscribe({
      next: () => {
        this.resetForm.reset();
        void this.router.navigate(['/login'], { queryParams: { reset: '1' } });
      },
      error: error => this.resetState.fail(error, 'Mã không hợp lệ, đã hết hạn hoặc đã được sử dụng. Kiểm tra mã hoặc yêu cầu mã mới.'),
    });
  }
  requestInvalid(name: 'tenantSlug' | 'email'): boolean {
    return this.requestForm.controls[name].touched && this.requestForm.controls[name].invalid;
  }
  resetInvalid(name: 'challengeId' | 'code' | 'password' | 'confirm'): boolean {
    return this.resetForm.controls[name].touched && this.resetForm.controls[name].invalid;
  }
}
