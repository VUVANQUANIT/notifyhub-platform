import { expect, Page, test } from '@playwright/test';

const id = '11111111-2222-3333-4444-555555555555';
const draft = { id, name: 'Welcome team', channel: 'EMAIL', subject: 'Hello team', body: 'Hello {{name}}', scheduledAt: null, status: 'DRAFT', createdAt: '2026-10-14T10:00:00Z' };
const pageOf = (items: unknown[], page = 0, totalElements = items.length) => ({ items, totalElements, page, size: 20 });
const report = { campaignId: id, status: 'COMPLETED', expected: 1, sent: 1, failed: 0, pending: 0, attempts: 1 };
async function login(page: Page, role = 'ADMIN') {
  await page.route('**/api/auth/login', route => route.fulfill({ json: { accessToken: 'test-access', refreshToken: 'test-refresh', expiresIn: 300, refreshExpiresAt: '2099-01-01T00:00:00Z' } }));
  await page.route('**/api/auth/me', route => route.fulfill({ json: { id: 'u1', role, email: 'admin@example.com', displayName: 'Minh Anh', enabled: true } }));
  await page.route('**/api/auth/tenant', route => route.fulfill({ json: { id: 't1', name: 'Acme Studio', slug: 'demo-team' } }));
  await page.goto('/login');
  await page.getByLabel('Mã tổ chức', {exact:true}).fill('demo-team'); await page.getByLabel('Email',{exact:true}).fill('admin@example.com'); await page.getByLabel('Mật khẩu',{exact:true}).fill('a long demo passphrase');
  await page.getByRole('button',{name:'Vào không gian làm việc'}).click(); await expect(page).toHaveURL(/workspace$/);
}
async function campaigns(page: Page) {
  let started = false; let imported = false;
  await page.route('**/api/campaigns**', async route => {
    const request = route.request(); const url = new URL(request.url());
    expect(request.headers()['authorization']).toBe('Bearer test-access');
    if (url.pathname.endsWith('/imports')) {
      expect(request.headers()['content-type']).toContain('multipart/form-data; boundary=');
      imported = true;
      return route.fulfill({ status: 201, json: { id: 'batch1', status: 'COMPLETED', summary: {totalRows:3,validRows:1,duplicateRows:1,invalidRows:1}, errors: [{rowNumber:4,message:'Invalid email'}], rejectionReason: null } });
    }
    if (url.pathname.endsWith('/recipients')) return route.fulfill({json:pageOf(imported ? [{id:'r1',destination:'ada@example.com',personalization:{name:'Ada'}}] : [])});
    if (url.pathname.endsWith('/start')) { expect(request.headers()['idempotency-key']).toBeTruthy(); started = true; return route.fulfill({json:{...draft,status:'RUNNING'}}); }
    if (request.method() === 'POST') { expect(request.headers()['idempotency-key']).toBeTruthy(); return route.fulfill({status:201,json:{...draft,...request.postDataJSON()}}); }
    return route.fulfill({json:url.pathname === '/api/campaigns' ? pageOf([{...draft,status:started?'RUNNING':'DRAFT'}]) : {...draft,status:started?'RUNNING':'DRAFT'}});
  });
  await page.route('**/api/reports/**', route => route.fulfill({json:route.request().url().includes('/deliveries') ? pageOf([{notificationId:'n1',recipientId:'r1',channel:'EMAIL',status:'SENT',attempts:1,failureCode:null,occurredAt:'2026-10-14T10:00:00Z'}]) : report}));
}

test('create, CSV row feedback, start and delivery report through protected navigation', async ({page}) => {
  await campaigns(page); await login(page);
  await page.getByRole('link',{name:'Chiến dịch',exact:true}).click(); await page.getByRole('link',{name:'Tạo chiến dịch ↗'}).click();
  await page.getByLabel('Tên chiến dịch').fill('Welcome team'); await page.getByLabel('Tiêu đề email').fill('Hello team'); await page.getByLabel('Nội dung thông điệp').fill('Hello {{name}}');
  await page.getByRole('button',{name:'Lưu bản nháp'}).click(); await expect(page.getByRole('heading',{name:'Welcome team',exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:'Bắt đầu gửi'})).toBeDisabled();
  await page.getByLabel('File CSV').setInputFiles({name:'people.csv',mimeType:'text/csv',buffer:Buffer.from('email,name\nada@example.com,Ada')}); await page.getByRole('button',{name:'Nhập CSV',exact:true}).click();
  await expect(page.locator('.import-result [role="status"]')).toContainText('Hợp lệ: 1'); await expect(page.getByText('Invalid email',{exact:true})).toBeVisible(); await expect(page.getByRole('cell',{name:'ada@example.com',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Bắt đầu gửi'}).click(); await expect(page.getByRole('status').filter({hasText:'đã bắt đầu'})).toBeVisible();
  await page.getByRole('link',{name:'Xem báo cáo ↗'}).click(); await expect(page.getByRole('heading',{name:'Kết quả chiến dịch.'})).toBeVisible(); await expect(page.getByRole('cell',{name:'Thành công',exact:true})).toBeVisible();
});

test('VIEWER can browse but cannot create/import/start, including direct create navigation', async ({page}) => {
  await campaigns(page); await login(page,'VIEWER'); await page.getByRole('link',{name:'Chiến dịch',exact:true}).click();
  await expect(page.getByRole('link',{name:'Tạo chiến dịch ↗'})).toHaveCount(0); await page.getByRole('link',{name:'Welcome team',exact:true}).click();
  await expect(page.getByRole('button',{name:'Bắt đầu gửi'})).toHaveCount(0); await expect(page.getByLabel('File CSV')).toHaveCount(0);
  await page.evaluate(() => { history.pushState({}, '', '/campaigns/new'); window.dispatchEvent(new PopStateEvent('popstate')); });
  await expect(page.getByRole('alert')).toContainText('cần quyền'); await expect(page.getByRole('button',{name:'Lưu bản nháp'})).toBeDisabled();
});

test('create retry after ambiguous server error preserves key and original payload', async ({page}) => {
  await campaigns(page); let attempts = 0; let key = ''; let payload: unknown;
  await page.route('**/api/campaigns', async route => {
    attempts++; if (attempts === 1) { key = route.request().headers()['idempotency-key']; payload = route.request().postDataJSON(); return route.fulfill({status:500,json:{}}); }
    expect(route.request().headers()['idempotency-key']).toBe(key); expect(route.request().postDataJSON()).toEqual(payload); return route.fulfill({status:201,json:draft});
  });
  await login(page); await page.evaluate(() => { history.pushState({}, '', '/campaigns/new'); window.dispatchEvent(new PopStateEvent('popstate')); });
  await page.getByLabel('Tên chiến dịch').fill('Welcome team'); await page.getByLabel('Tiêu đề email').fill('Hello team'); await page.getByLabel('Nội dung thông điệp').fill('Hello'); await page.getByRole('button',{name:'Lưu bản nháp'}).click();
  await expect(page.getByRole('alert')).toBeVisible(); await expect(page.getByLabel('Tên chiến dịch')).toBeDisabled(); expect(attempts).toBe(1);
  await page.getByRole('button',{name:'Thử lại tạo chiến dịch'}).click(); await expect(page).toHaveURL(new RegExp(`/campaigns/${id}$`)); expect(attempts).toBe(2);
});

test('rejected CSV is visible and start stays disabled; oversized upload is blocked locally', async ({page}) => {
  await campaigns(page); await page.route('**/api/campaigns/*/imports', route => route.fulfill({status:201,json:{id:'bad',status:'REJECTED',summary:{totalRows:0,validRows:0,invalidRows:0,duplicateRows:0},errors:[],rejectionReason:'Missing email column'}}));
  await page.route('**/api/campaigns/*/recipients*', route => route.fulfill({json:pageOf([])}));
  await login(page); await page.getByRole('link',{name:'Chiến dịch',exact:true}).click(); await page.getByRole('link',{name:'Welcome team',exact:true}).click();
  await page.getByLabel('File CSV').setInputFiles({name:'big.csv',mimeType:'text/csv',buffer:Buffer.alloc(2*1024*1024+1)}); await expect(page.getByRole('alert')).toContainText('2 MiB'); await expect(page.getByRole('button',{name:'Nhập CSV',exact:true})).toBeDisabled();
  await page.getByLabel('File CSV').setInputFiles({name:'bad.csv',mimeType:'text/csv',buffer:Buffer.from('wrong\nvalue')}); await page.getByRole('button',{name:'Nhập CSV',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Kết quả import: File bị từ chối'})).toBeVisible(); await expect(page.getByText('Missing email column')).toBeVisible(); await expect(page.getByRole('button',{name:'Bắt đầu gửi'})).toBeDisabled();
});

test('campaign filter resets pagination; report failure filter and unknown counts are preserved', async ({page}) => {
  await campaigns(page); const queries: URLSearchParams[] = [];
  await page.route('**/api/campaigns?*', route => { const query = new URL(route.request().url()).searchParams; queries.push(query); const page = Number(query.get('page')); return route.fulfill({json:pageOf([draft],page,21)}); });
  await page.route('**/api/reports/campaigns/**', route => {
    const url = new URL(route.request().url()); return route.fulfill({json:url.pathname.endsWith('/deliveries') ? pageOf([]) : {...report,status:'RUNNING',expected:null,pending:null}});
  });
  await login(page); await page.getByRole('link',{name:'Chiến dịch',exact:true}).click(); await page.getByRole('button',{name:'Trang sau'}).click(); await expect(page.getByText('Trang 2',{exact:true})).toBeVisible();
  await page.getByLabel('Trạng thái',{exact:true}).selectOption('RUNNING'); await expect(page.getByText('Trang 1',{exact:true})).toBeVisible(); expect(queries.at(-1)?.get('status')).toBe('RUNNING'); expect(queries.at(-1)?.get('page')).toBe('0');
  await page.getByRole('link',{name:'Welcome team',exact:true}).click(); await page.getByRole('link',{name:'Xem báo cáo ↗'}).click(); await expect(page.locator('.metric-grid strong').first()).toHaveText('—');
  const request = page.waitForRequest(request => request.url().includes('/deliveries') && request.url().includes('status=FAILED')); await page.getByLabel('Kết quả gửi',{exact:true}).selectOption('FAILED'); await request; await expect(page.getByRole('cell',{name:'Chưa có delivery phù hợp.'})).toBeVisible();
});

test('404/403 and Retry-After remain visible with no automatic retry loop', async ({page}) => {
  await campaigns(page); await login(page); let reads = 0;
  await page.route('**/api/campaigns?*', route => { reads++; return route.fulfill({status:503,headers:{'Retry-After':'2'},json:{}}); });
  await page.getByRole('link',{name:'Chiến dịch',exact:true}).click(); await expect(page.getByRole('alert')).toContainText('chờ'); await expect(page.getByRole('button',{name:'Làm mới chiến dịch'})).toBeDisabled(); await expect(page.getByRole('button',{name:'Làm mới chiến dịch'})).toBeEnabled({timeout:6000}); expect(reads).toBe(1);
  await page.route('**/api/campaigns?*', route => route.fulfill({status:403,json:{detail:'Internal stack detail'}})); await page.getByRole('button',{name:'Làm mới chiến dịch'}).click(); await expect(page.getByRole('alert')).toContainText('không có quyền'); await expect(page.getByText('Internal stack detail')).toHaveCount(0);
  await page.route('**/api/reports/campaigns/*', route => route.fulfill({status:404,json:{}})); await page.evaluate(() => { history.pushState({}, '', '/reports/not-visible'); window.dispatchEvent(new PopStateEvent('popstate')); }); await expect(page.getByRole('alert')).toContainText('Không tìm thấy dữ liệu');
});

test('campaigns and reports fit a mobile viewport and untrusted content renders as text', async ({page}) => {
  await campaigns(page); await page.route('**/api/campaigns?*', route => route.fulfill({json:pageOf([{...draft,name:'<img src=x onerror=alert(1)>'}])}));
  await page.setViewportSize({width:390,height:844}); await login(page); await page.getByRole('link',{name:'Chiến dịch',exact:true}).click(); await expect(page.getByRole('link',{name:'<img src=x onerror=alert(1)>'})).toBeVisible(); expect(await page.locator('table img').count()).toBe(0); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('link',{name:'<img src=x onerror=alert(1)>'}).click(); await page.getByRole('link',{name:'Xem báo cáo ↗'}).click(); await expect(page.getByRole('cell',{name:'Thành công',exact:true})).toBeVisible(); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({path:'test-results/campaign-mobile.png',fullPage:true});
});

test('SMS schedule converts local time to UTC and start retry keeps the same key', async ({page}) => {
  await campaigns(page); const scheduled = {...draft,channel:'SMS',subject:null,scheduledAt:new Date('2099-01-01T12:30').toISOString()};
  await page.route('**/api/campaigns', route => { const body = route.request().postDataJSON(); expect(body.scheduledAt).toBe(scheduled.scheduledAt); expect(body.subject).toBeNull(); return route.fulfill({status:201,json:scheduled}); });
  await page.route(`**/api/campaigns/${id}`, route => route.fulfill({json:scheduled}));
  await page.route('**/api/campaigns/*/recipients*', route => route.fulfill({json:pageOf([{id:'r1',destination:'+84901234567',personalization:{}}])}));
  let attempts = 0; let key = '';
  await page.route('**/api/campaigns/*/start', route => { attempts++; if (attempts === 1) { key = route.request().headers()['idempotency-key']; return route.fulfill({status:500,json:{}}); } expect(route.request().headers()['idempotency-key']).toBe(key); return route.fulfill({json:{...scheduled,status:'SCHEDULED'}}); });
  await login(page); await page.evaluate(() => { history.pushState({}, '', '/campaigns/new'); window.dispatchEvent(new PopStateEvent('popstate')); });
  await page.getByLabel('Tên chiến dịch').fill('Scheduled SMS'); await page.getByLabel('Kênh gửi').selectOption('SMS'); await expect(page.getByLabel('Tiêu đề email')).toHaveCount(0); await page.getByLabel('Nội dung thông điệp').fill('Hello'); await page.getByLabel('Lịch gửi (không bắt buộc)').fill('2099-01-01T12:30'); await page.getByRole('button',{name:'Lưu bản nháp'}).click();
  await page.getByRole('button',{name:'Xác nhận lịch gửi'}).click(); await expect(page.getByRole('alert')).toBeVisible(); expect(attempts).toBe(1); await page.getByRole('button',{name:'Xác nhận lịch gửi'}).click(); await expect(page.getByRole('status')).toContainText('được lên lịch'); expect(attempts).toBe(2);
});
