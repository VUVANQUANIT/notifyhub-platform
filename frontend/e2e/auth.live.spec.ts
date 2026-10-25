import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test('browser → real Gateway/Auth → Redis/PostgreSQL/MailHog: login, refresh, reset, revoke and logout', async ({ page, request }) => {
  const gateway = process.env['NOTIFYHUB_GATEWAY_URL'] || 'http://127.0.0.1:18080';
  const mailbox = process.env['NOTIFYHUB_MAILHOG_URL'] || 'http://127.0.0.1:18025';
  const tenantSlug = `web-${randomUUID().slice(0, 12)}`;
  const email = `${tenantSlug}@example.com`;
  const password = `browser smoke ${randomUUID()}`;
  const nextPassword = `updated browser ${randomUUID()}`;
  const registration = await request.post(`${gateway}/api/auth/register`, { data: { tenantSlug, tenantName: 'Browser Test Team', email, displayName: 'Browser Tester', password } });
  expect(registration.status()).toBe(200);
  const login = async (credential: string) => {
    await page.goto('/login');
    await page.getByLabel('Mã tổ chức', { exact: true }).fill(tenantSlug);
    await page.getByLabel('Email', { exact: true }).fill(email);
    await page.getByLabel('Mật khẩu', { exact: true }).fill(credential);
    await page.getByRole('button', { name: 'Vào không gian làm việc' }).click();
  };
  const browserTokens = page.waitForResponse(response => response.url().endsWith('/api/auth/login') && response.status() === 200);
  await login(password);
  const original = await (await browserTokens).json();
  await expect(page.getByRole('heading', { name: 'Chào Browser Tester.' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Browser Test Team' })).toBeVisible();
  // Move the browser clock beyond the small test issuer TTL, without changing server time.
  await page.clock.install();
  await page.clock.fastForward((original.expiresIn + 1) * 1000);
  const rotation = page.waitForResponse(response => response.url().endsWith('/api/auth/refresh') && response.status() === 200);
  await page.getByRole('button', { name: 'Làm mới thông tin' }).click();
  const rotated = await (await rotation).json();
  await expect(page.getByRole('status')).toHaveText('Thông tin đã được cập nhật.');
  // Use another browser context for recovery while the first session stays active.
  const recoveryContext = await page.context().browser()!.newContext();
  const recovery = await recoveryContext.newPage();
  await recovery.goto('http://127.0.0.1:4201/forgot-password');
  await recovery.getByLabel('Mã tổ chức', { exact: true }).fill(tenantSlug);
  await recovery.getByLabel('Email', { exact: true }).fill(email);
  await recovery.getByRole('button', { name: 'Gửi mã khôi phục' }).click();
  await expect(recovery.getByRole('status')).toContainText('Nếu tài khoản đủ điều kiện');
  const challenge = await recovery.getByLabel('Mã yêu cầu', { exact: true }).inputValue();
  let code = '';
  await expect.poll(async () => {
    const response = await request.get(`${mailbox}/api/v2/messages`);
    const messages = (await response.json()).items as { Content: { Body: string }; To: { Mailbox: string; Domain: string }[] }[];
    const message = messages.find(item => item.To.some(to => `${to.Mailbox}@${to.Domain}` === email) && item.Content.Body.includes(challenge));
    code = message?.Content.Body.match(/\b\d{8}\b/)?.[0] || '';
    return code.length;
  }, { timeout: 20_000, message: 'Recovery email should arrive in the local mailbox' }).toBe(8);
  await recovery.getByLabel('Mã xác nhận', { exact: true }).fill(code);
  await recovery.getByLabel('Mật khẩu mới', { exact: true }).fill(nextPassword);
  await recovery.getByLabel('Nhập lại mật khẩu mới', { exact: true }).fill(nextPassword);
  await recovery.getByRole('button', { name: 'Cập nhật mật khẩu' }).click();
  await expect(recovery).toHaveURL(/\/login\?reset=1$/);
  const revoked = await request.post(`${gateway}/api/auth/refresh`, { data: { refreshToken: rotated.refreshToken } });
  expect(revoked.status()).toBe(401);
  await recoveryContext.close();
  // A rejected refresh expires the original browser session.
  await page.clock.fastForward((rotated.expiresIn + 1) * 1000);
  await page.getByRole('button', { name: 'Làm mới thông tin' }).click();
  await expect(page).toHaveURL(/\/login\?expired=1$/);
  await login(nextPassword);
  await expect(page).toHaveURL(/\/workspace$/);
  await page.getByRole('button', { name: 'Đăng xuất', exact: true }).click();
  await expect(page).toHaveURL(/\/login$/);
});
