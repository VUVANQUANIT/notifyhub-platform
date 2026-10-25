import { expect, test, Page } from '@playwright/test';

const credentials = { tenantSlug: 'demo-team', email: 'admin@example.com', password: 'a long demo passphrase' };
const tokens = { tokenType: 'Bearer', accessToken: 'demo-access', refreshToken: 'demo-refresh', expiresIn: 300, refreshExpiresAt: '2099-01-01T00:00:00Z' };

async function identity(page: Page): Promise<void> {
  await page.route('**/api/auth/me', route => route.fulfill({ json: { id: 'u1', tenantId: 't1', email: credentials.email, displayName: 'Minh Anh', role: 'ADMIN', enabled: true } }));
  await page.route('**/api/auth/tenant', route => route.fulfill({ json: { id: 't1', slug: credentials.tenantSlug, name: 'Acme Studio' } }));
}
async function fillLogin(page: Page): Promise<void> {
  await page.getByLabel('Mã tổ chức', { exact: true }).fill(credentials.tenantSlug);
  await page.getByLabel('Email', { exact: true }).fill(credentials.email);
  await page.getByLabel('Mật khẩu', { exact: true }).fill(credentials.password);
}

test('protected routes require login; successful login loads tenant and logout revokes refresh', async ({ page }) => {
  await identity(page);
  await page.route('**/api/auth/login', route => route.fulfill({ json: tokens }));
  await page.route('**/api/auth/logout', async route => {
    expect(route.request().postDataJSON()).toEqual({ refreshToken: tokens.refreshToken });
    await route.fulfill({ status: 204 });
  });
  await page.goto('/workspace');
  await expect(page).toHaveURL(/\/login$/);
  await fillLogin(page);
  await page.getByRole('button', { name: 'Vào không gian làm việc' }).click();
  await expect(page.getByRole('heading', { name: 'Chào Minh Anh.' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Acme Studio' })).toBeVisible();
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }))).toEqual({ local: 0, session: 0 });
  await page.getByRole('button', { name: 'Làm mới thông tin' }).click();
  await expect(page.getByRole('status')).toHaveText('Thông tin đã được cập nhật.');
  await page.getByRole('button', { name: 'Đăng xuất', exact: true }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goto('/workspace');
  await expect(page).toHaveURL(/\/login$/);
});

test('429 honors Retry-After and credentials are not automatically resubmitted', async ({ page }) => {
  let requests = 0;
  await page.route('**/api/auth/login', route => {
    requests++;
    return route.fulfill({ status: 429, headers: { 'Retry-After': '2' }, json: { detail: 'Too many requests' } });
  });
  await page.goto('/login');
  await fillLogin(page);
  await page.getByRole('button', { name: 'Vào không gian làm việc' }).click();
  await expect(page.getByRole('alert')).toContainText('Vui lòng chờ');
  await expect(page.getByRole('button', { name: /Thử lại sau/ })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Vào không gian làm việc' })).toBeEnabled({ timeout: 6000 });
  expect(requests).toBe(1);
});

test('503 during login keeps a visible wait state with no retry loop', async ({ page }) => {
  await page.route('**/api/auth/login', route => route.fulfill({ status: 503, headers: { 'Retry-After': '30' }, json: {} }));
  await page.goto('/login');
  await fillLogin(page);
  await page.getByRole('button', { name: 'Vào không gian làm việc' }).click();
  await expect(page.getByRole('alert')).toContainText('Dịch vụ tạm thời chưa sẵn sàng');
  await expect(page.getByRole('button', { name: /Thử lại sau/ })).toBeDisabled();
});

test('recovery keeps the generic response, validates passwords and preserves leading OTP zeros', async ({ page }) => {
  await page.route('**/api/auth/password/forgot', route => route.fulfill({ status: 202, json: { challengeId: '11111111-2222-3333-4444-555555555555', message: 'Generic response' } }));
  await page.route('**/api/auth/password/reset', async route => {
    expect(route.request().postDataJSON()).toEqual({ challengeId: '11111111-2222-3333-4444-555555555555', code: '00123456', password: 'new long demo passphrase' });
    await route.fulfill({ status: 204 });
  });
  await page.goto('/forgot-password');
  await page.getByLabel('Mã tổ chức', { exact: true }).fill(credentials.tenantSlug);
  await page.getByLabel('Email', { exact: true }).fill(credentials.email);
  await page.getByRole('button', { name: 'Gửi mã khôi phục' }).click();
  await expect(page.getByRole('status')).toContainText('Nếu tài khoản đủ điều kiện');
  await page.getByLabel('Mã xác nhận', { exact: true }).fill('00123456');
  await page.getByLabel('Mật khẩu mới', { exact: true }).fill('new long demo passphrase');
  await page.getByLabel('Nhập lại mật khẩu mới', { exact: true }).fill('different long passphrase');
  await page.getByRole('button', { name: 'Cập nhật mật khẩu' }).click();
  await expect(page.getByText('Hai mật khẩu phải giống nhau.')).toBeVisible();
  await page.getByLabel('Nhập lại mật khẩu mới', { exact: true }).fill('new long demo passphrase');
  await page.getByRole('button', { name: 'Cập nhật mật khẩu' }).click();
  await expect(page).toHaveURL(/\/login\?reset=1$/);
  await expect(page.getByRole('status')).toContainText('Mật khẩu đã được cập nhật');
});

test('refresh after expiry succeeds once and reload intentionally requires another login', async ({ page }) => {
  await identity(page);
  let refreshes = 0;
  await page.route('**/api/auth/login', route => route.fulfill({ json: { ...tokens, expiresIn: 0 } }));
  await page.route('**/api/auth/refresh', route => {
    refreshes++;
    return route.fulfill({ json: { ...tokens, accessToken: 'rotated-access', refreshToken: 'rotated-refresh' } });
  });
  await page.goto('/login');
  await fillLogin(page);
  await page.getByRole('button', { name: 'Vào không gian làm việc' }).click();
  await expect(page).toHaveURL(/\/workspace$/);
  expect(refreshes).toBe(1);
  await page.reload();
  await expect(page).toHaveURL(/\/login$/);
});

test('login and recovery fit a mobile viewport without horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/login');
  await expect(page.getByRole('button', { name: 'Vào không gian làm việc' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole('link', { name: 'Quên mật khẩu?' }).click();
  await expect(page.getByRole('button', { name: 'Gửi mã khôi phục' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
