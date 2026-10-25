import { expect, test } from '@playwright/test';

test('real Gateway workflow: email/SMS CSV, worker completion, reporting and another tenant', async ({page,request}) => {
  test.setTimeout(180_000);
  const slug = `campaign-${Date.now()}`;
  const password = 'unique local browser smoke passphrase';
  const registered = await request.post('/api/auth/register', {data:{tenantSlug:slug,tenantName:'Browser Campaign Team',email:'admin@example.com',displayName:'Campaign Admin',password}});
  expect(registered.status()).toBe(200);
  const credentials = await registered.json();
  const login = async (tenant: string, email = 'admin@example.com') => {
    await page.goto('/login'); await page.getByLabel('Mã tổ chức',{exact:true}).fill(tenant); await page.getByLabel('Email',{exact:true}).fill(email); await page.getByLabel('Mật khẩu',{exact:true}).fill(password); await page.getByRole('button',{name:'Vào không gian làm việc'}).click(); await expect(page).toHaveURL(/workspace$/);
  };
  await login(slug);
  let completedId = '';
  for (const channel of ['EMAIL','SMS']) {
    await page.getByRole('link',{name:'Chiến dịch',exact:true}).click(); await page.getByRole('link',{name:'Tạo chiến dịch ↗'}).click();
    await page.getByLabel('Tên chiến dịch').fill(`Browser ${channel} ${slug}`); await page.getByLabel('Kênh gửi').selectOption(channel);
    if (channel === 'EMAIL') await page.getByLabel('Tiêu đề email').fill(`Browser mail ${slug}`);
    await page.getByLabel('Nội dung thông điệp').fill('Hello {{name}}, a local smoke notification.'); await page.getByRole('button',{name:'Lưu bản nháp'}).click();
    await expect(page.getByRole('heading',{name:`Browser ${channel} ${slug}`,exact:true})).toBeVisible(); completedId = page.url().split('/').at(-1)!;
    const csv = channel === 'EMAIL' ? 'email,name\nada@example.com,Ada\nADA@example.com,Duplicate\ninvalid,Bad' : 'phoneNumber,name\n+84901234567,Ada\n+84901234567,Duplicate\ninvalid,Bad';
    await page.getByLabel('File CSV').setInputFiles({name:'recipients.csv',mimeType:'text/csv',buffer:Buffer.from(csv)}); await page.getByRole('button',{name:'Nhập CSV',exact:true}).click();
    await expect(page.locator('.import-result [role="status"]')).toContainText('Hợp lệ: 1'); await expect(page.locator('.import-result [role="status"]')).toContainText('Trùng: 1'); await expect(page.locator('.import-result [role="status"]')).toContainText('Lỗi: 1');
    await page.getByRole('button',{name:'Bắt đầu gửi'}).click(); await expect(page.getByRole('status').filter({hasText:'đã bắt đầu'})).toBeVisible(); await page.getByRole('link',{name:'Xem báo cáo ↗'}).click();
    await expect(async () => {
      const refresh = page.getByRole('button',{name:'Làm mới kết quả'}); if (await refresh.isEnabled()) await refresh.click();
      await expect(page.locator('.status-tag')).toHaveText('Hoàn thành',{timeout:2000}); await expect(page.getByRole('cell',{name:'Thành công',exact:true})).toBeVisible({timeout:2000});
      await expect(page.locator('.metric-grid strong').nth(1)).toHaveText('1'); await expect(page.locator('.metric-grid strong').nth(3)).toHaveText('0');
    }).toPass({timeout:60_000,intervals:[1000,2000,3000]});
  }
  const mailHogUrl = process.env['NOTIFYHUB_MAILHOG_URL'] || 'http://127.0.0.1:18025';
  const mail = await request.get(`${mailHogUrl}/api/v2/search?kind=containing&query=${encodeURIComponent(slug)}`);
  expect(mail.status()).toBe(200); expect((await mail.json()).items.length).toBeGreaterThan(0);
  await page.getByRole('link',{name:'Báo cáo',exact:true}).click(); await expect(page.locator('.metric-grid strong').nth(0)).toHaveText('2');
  const otherSlug = `other-${Date.now()}`; expect((await request.post('/api/auth/register',{data:{tenantSlug:otherSlug,tenantName:'Other Team',email:'admin@example.com',displayName:'Other Admin',password}})).status()).toBe(200);
  await page.getByRole('button',{name:'Đăng xuất',exact:true}).click(); await login(otherSlug);
  await page.evaluate(id => { history.pushState({}, '', `/campaigns/${id}`); window.dispatchEvent(new PopStateEvent('popstate')); },completedId); await expect(page.getByRole('alert')).toContainText('Không tìm thấy dữ liệu');
  // Create a real VIEWER in the original tenant, then exercise its UI and backend write denial.
  const viewer = await request.post('/api/auth/users',{headers:{Authorization:`Bearer ${credentials.accessToken}`},data:{email:'viewer@example.com',displayName:'Viewer',role:'VIEWER',password}}); expect(viewer.status()).toBe(201);
  await page.getByRole('button',{name:'Đăng xuất',exact:true}).click(); await login(slug,'viewer@example.com'); await page.getByRole('link',{name:'Chiến dịch',exact:true}).click(); await expect(page.getByRole('link',{name:'Tạo chiến dịch ↗'})).toHaveCount(0);
  const viewerSession = await request.post('/api/auth/login',{data:{tenantSlug:slug,email:'viewer@example.com',password}}); expect(viewerSession.status()).toBe(200);
  const viewerTokens = await viewerSession.json();
  const denied = await request.post('/api/campaigns',{headers:{Authorization:`Bearer ${viewerTokens.accessToken}`,'Idempotency-Key':crypto.randomUUID()},data:{name:'Viewer cannot write',channel:'SMS',body:'Not permitted'}});
  expect(denied.status()).toBe(403);
});
