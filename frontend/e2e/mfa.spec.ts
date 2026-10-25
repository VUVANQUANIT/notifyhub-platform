import {expect,test} from '@playwright/test';
test('MFA enrollment requires password, confirms code and displays recovery codes only in memory',async({page})=>{
  await page.route('**/api/auth/login',r=>r.fulfill({json:{accessToken:'access',refreshToken:'refresh',expiresIn:300,refreshExpiresAt:'2099-01-01T00:00:00Z'}}));
  await page.route('**/api/auth/me',r=>r.fulfill({json:{id:'u1',tenantId:'t1',email:'user@example.com',displayName:'User',role:'VIEWER',enabled:true}}));
  await page.route('**/api/auth/tenant',r=>r.fulfill({json:{id:'t1',slug:'demo-team',name:'Demo'}}));
  await page.route('**/api/auth/mfa',r=>r.fulfill({json:{enabled:false}}));
  await page.route('**/api/auth/mfa/enrollment',r=>{expect(r.request().postDataJSON()).toEqual({password:'my long unique password'});return r.fulfill({json:{secret:'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ',uri:'otpauth://totp/test',expiresAt:'2099-01-01T00:00:00Z'}});});
  await page.route('**/api/auth/mfa/confirm',r=>{expect(r.request().postDataJSON()).toEqual({code:'123456'});return r.fulfill({json:{recoveryCodes:['r'.repeat(43)]}});});
  await page.goto('/login');await page.getByLabel('Mã tổ chức',{exact:true}).fill('demo-team');await page.getByLabel('Email',{exact:true}).fill('user@example.com');await page.getByLabel('Mật khẩu',{exact:true}).fill('my long unique password');await page.getByLabel('Mã xác thực hai bước (nếu đã bật)').fill('123456');await page.getByRole('button',{name:'Vào không gian làm việc'}).click();await expect(page).toHaveURL(/workspace$/);
  await page.getByRole('link',{name:'Bảo mật tài khoản',exact:true}).click();await page.getByLabel('Mật khẩu hiện tại').fill('my long unique password');await page.getByRole('button',{name:'Thiết lập MFA',exact:true}).click();await expect(page.getByText('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ',{exact:true})).toBeVisible();
  await page.getByLabel('Mã xác thực hoặc mã khôi phục').fill('123456');await page.getByRole('button',{name:'Xác nhận bật MFA',exact:true}).click();await expect(page.getByText('r'.repeat(43),{exact:true})).toBeVisible();expect(await page.evaluate(()=>localStorage.length+sessionStorage.length)).toBe(0);
});
