import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, inject, signal } from '@angular/core';
import { retryDelay } from './auth.service';

// Shared form feedback; no automatic retries for credentials or OTPs.
export class RequestState {
  readonly busy = signal(false);
  readonly error = signal('');
  readonly remaining = signal(0);
  private timer: ReturnType<typeof setInterval> | null = null;
  private until = 0;

  constructor() { inject(DestroyRef).onDestroy(() => this.stopTimer()); }
  get blocked(): boolean { return this.busy() || this.remaining() > 0; }
  begin(): boolean {
    if (this.blocked) return false;
    this.busy.set(true);
    this.error.set('');
    return true;
  }
  fail(error: unknown, unauthorized: string): void {
    if (!(error instanceof HttpErrorResponse)) {
      this.error.set('Đã có lỗi xảy ra. Vui lòng thử lại.');
      return;
    }
    if (error.status === 429 || error.status === 503) {
      this.error.set(error.status === 429 ? 'Bạn đã gửi nhiều yêu cầu. Vui lòng chờ trước khi thử lại.' : 'Dịch vụ tạm thời chưa sẵn sàng. Vui lòng chờ rồi thử lại.');
      this.wait(retryDelay(error));
    } else if (error.status === 401) {
      this.error.set(unauthorized);
    } else if (error.status === 0 || error.status >= 500) {
      this.error.set('Không thể kết nối dịch vụ. Vui lòng thử lại sau.');
    } else {
      this.error.set('Thông tin chưa hợp lệ. Vui lòng kiểm tra lại.');
    }
  }
  wait(seconds: number): void {
    this.stopTimer();
    this.until = Date.now() + seconds * 1000;
    this.tick();
    this.timer = setInterval(() => this.tick(), 250);
  }
  private tick(): void {
    this.remaining.set(Math.max(0, Math.ceil((this.until - Date.now()) / 1000)));
    if (!this.remaining()) this.stopTimer();
  }
  private stopTimer(): void { if (this.timer !== null) clearInterval(this.timer); this.timer = null; }
}
