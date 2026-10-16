import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from './auth.service';
import { RequestState } from './request-state';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './login.html',
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(NonNullableFormBuilder);
  readonly state = new RequestState();
  readonly showPassword = signal(false);
  readonly resetDone = this.route.snapshot.queryParamMap.get('reset') === '1';
  readonly expired = this.route.snapshot.queryParamMap.get('expired') === '1';
  readonly form = this.fb.group({
    tenantSlug: ['', [Validators.required, Validators.pattern(/^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    // Existing credentials are validated by the issuer, not by a new-password policy.
    password: ['', [Validators.required, Validators.maxLength(128)]],
    code: ['', [Validators.maxLength(43)]],
  });

  invalid(name: 'tenantSlug' | 'email' | 'password'): boolean {
    const control = this.form.controls[name];
    return control.touched && control.invalid;
  }
  submit(): void {
    this.form.controls.tenantSlug.setValue(this.form.controls.tenantSlug.value.trim().toLowerCase());
    this.form.controls.email.setValue(this.form.controls.email.value.trim().toLowerCase());
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    if (!this.state.begin()) return;
    this.auth.login(this.form.getRawValue()).pipe(finalize(() => this.state.busy.set(false))).subscribe({
      next: () => {
        this.form.controls.password.reset();
        this.form.controls.code.reset();
        void this.router.navigate(['/workspace']);
      },
      error: error => this.state.fail(error, 'Không thể đăng nhập. Kiểm tra mã tổ chức, email và mật khẩu.'),
    });
  }
}
