import { expect, test } from '@playwright/test';

test('real member UI enforces last-admin, refresh revocation, tenant isolation and self-demotion', async ({page,request}) => {
  test.setTimeout(150_000);
  const slug=`members-${Date.now()}`;
  const password='a unique member smoke passphrase';
  const registered=await request.post('/api/auth/register',{data:{tenantSlug:slug,tenantName:'Members Browser Team',email:'admin@example.com',displayName:'Members Admin',password}});
  expect(registered.status()).toBe(200); const adminTokens=await registered.json(); const headers={Authorization:`Bearer ${adminTokens.accessToken}`};
  const adminResponse=await request.get('/api/auth/me',{headers}); expect(adminResponse.status()).toBe(200); const admin=await adminResponse.json();
  const otherResponse=await request.post('/api/auth/register',{data:{tenantSlug:`other-${slug}`,tenantName:'Other Team',email:'other@example.com',displayName:'Other Admin',password}});
  expect(otherResponse.status()).toBe(200); const otherTokens=await otherResponse.json(); const otherHeaders={Authorization:`Bearer ${otherTokens.accessToken}`};
  const other=await (await request.get('/api/auth/me',{headers:otherHeaders})).json();
  const login=async()=>{
    await page.goto('/login'); await page.getByLabel('Mã tổ chức',{exact:true}).fill(slug); await page.getByLabel('Email',{exact:true}).fill('admin@example.com'); await page.getByLabel('Mật khẩu',{exact:true}).fill(password); await page.getByRole('button',{name:'Vào không gian làm việc'}).click(); await expect(page).toHaveURL(/workspace$/);
  };
  const create=async(email:string,role:string)=>{
    await page.getByRole('button',{name:'Thêm thành viên',exact:true}).click(); await page.getByLabel('Tên hiển thị',{exact:true}).fill('Browser Member'); await page.getByLabel('Email thành viên',{exact:true}).fill(email); await page.getByLabel('Quyền truy cập',{exact:true}).selectOption(role); await page.getByLabel('Mật khẩu ban đầu',{exact:true}).fill(password); await page.getByLabel('Nhập lại mật khẩu',{exact:true}).fill(password); await page.getByRole('button',{name:'Tạo tài khoản',exact:true}).click(); await expect(page.getByRole('cell').filter({hasText:email})).toBeVisible();
  };
  const confirm=()=>page.getByRole('dialog').getByRole('button',{name:'Xác nhận thay đổi',exact:true}).click();
  await login(); await page.getByRole('link',{name:'Thành viên',exact:true}).click();
  await page.getByRole('button',{name:'Đổi quyền admin@example.com',exact:true}).click(); await page.getByRole('dialog').getByLabel('Quyền mới',{exact:true}).selectOption('VIEWER'); await confirm(); await expect(page.getByRole('dialog').getByRole('alert')).toContainText('ADMIN đang hoạt động'); await page.getByRole('button',{name:'Hủy thay đổi',exact:true}).click();
  await page.getByRole('button',{name:'Vô hiệu hóa admin@example.com',exact:true}).click(); await confirm(); await expect(page.getByRole('dialog').getByRole('alert')).toContainText('ADMIN đang hoạt động'); await page.getByRole('button',{name:'Hủy thay đổi',exact:true}).click();
  await create('member@example.com','OPERATOR');
  const memberLogin=await request.post('/api/auth/login',{data:{tenantSlug:slug,email:'member@example.com',password}}); expect(memberLogin.status()).toBe(200); const memberTokens=await memberLogin.json();
  await page.getByRole('button',{name:'Đổi quyền member@example.com',exact:true}).click(); await page.getByRole('dialog').getByLabel('Quyền mới',{exact:true}).selectOption('VIEWER'); await confirm(); await expect(page.getByRole('row').filter({hasText:'member@example.com'})).toContainText('VIEWER');
  expect((await request.post('/api/auth/refresh',{data:{refreshToken:memberTokens.refreshToken}})).status()).toBe(401);
  await page.getByRole('button',{name:'Vô hiệu hóa member@example.com',exact:true}).click(); await confirm(); await expect(page.getByRole('row').filter({hasText:'member@example.com'})).toContainText('Đã vô hiệu hóa');
  expect((await request.post('/api/auth/login',{data:{tenantSlug:slug,email:'member@example.com',password}})).status()).toBe(401);
  await page.getByRole('button',{name:'Kích hoạt member@example.com',exact:true}).click(); await confirm(); await expect(page.getByRole('row').filter({hasText:'member@example.com'})).toContainText('Đang hoạt động');
  const reenabled=await request.post('/api/auth/login',{data:{tenantSlug:slug,email:'member@example.com',password}}); expect(reenabled.status()).toBe(200); const viewerTokens=await reenabled.json();
  expect((await request.get('/api/auth/users',{headers:{Authorization:`Bearer ${viewerTokens.accessToken}`}})).status()).toBe(403);
  expect((await request.put(`/api/auth/users/${other.id}/role`,{headers,data:{role:'VIEWER'}})).status()).toBe(404);
  const otherList=await request.get('/api/auth/users',{headers:otherHeaders}); expect(otherList.status()).toBe(200); expect((await otherList.json()).items.some((user:{email:string})=>user.email==='member@example.com')).toBe(false);
  await create('second-admin@example.com','ADMIN'); await page.getByRole('button',{name:'Đổi quyền admin@example.com',exact:true}).click(); await page.getByRole('dialog').getByLabel('Quyền mới',{exact:true}).selectOption('VIEWER'); await confirm(); await expect(page).toHaveURL(/login\?expired=1$/);
  expect((await request.post('/api/auth/refresh',{data:{refreshToken:adminTokens.refreshToken}})).status()).toBe(401); expect((await request.get('/api/auth/users',{headers})).status()).toBe(403);
  await login(); await expect(page.getByRole('link',{name:'Thành viên',exact:true})).toHaveCount(0);
  await page.evaluate(()=>{history.pushState({},'','/members');dispatchEvent(new PopStateEvent('popstate'));}); await expect(page.getByRole('alert')).toContainText('Chỉ ADMIN');
  expect(admin.id).toBeTruthy(); // No credentials, native screenshots or traces are persisted by the live suite.
});
