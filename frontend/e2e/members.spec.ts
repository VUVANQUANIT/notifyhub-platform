import { expect, Page, test } from '@playwright/test';

const admin = {id:'a1',tenantId:'t1',email:'admin@example.com',displayName:'Minh Anh',role:'ADMIN',enabled:true};
const member = {...admin,id:'u2',email:'member@example.com',displayName:'Ada',role:'VIEWER'};
async function setup(page: Page, role = 'ADMIN') {
  const current = {...admin,role}; const users = [current,{...member}];
  await page.route('**/api/auth/login', route=>route.fulfill({json:{accessToken:'fake-access',refreshToken:'fake-refresh',expiresIn:300,refreshExpiresAt:'2099-01-01T00:00:00Z'}}));
  await page.route('**/api/auth/me', route=>route.fulfill({json:current}));
  await page.route('**/api/auth/tenant', route=>route.fulfill({json:{id:'t1',name:'Acme Studio',slug:'demo-team'}}));
  await page.route('**/api/auth/users**', async route=> {
    const request = route.request(); expect(request.headers()['authorization']).toBe('Bearer fake-access');
    const url = new URL(request.url());
    if (request.method()==='GET') return route.fulfill({json:{items:users,total:users.length,page:Number(url.searchParams.get('page')||0),size:20}});
    if (request.method()==='POST') {
      const {password,...created} = request.postDataJSON(); expect(password).toBe('a long member passphrase'); const user = {...created,id:'u3',tenantId:'t1',enabled:true}; users.push(user); return route.fulfill({status:201,json:user});
    }
    const selected = users.find(user=>user.id===url.pathname.split('/')[4])!; Object.assign(selected,request.postDataJSON()); return route.fulfill({json:selected});
  });
  await page.goto('/login'); await page.getByLabel('Mã tổ chức',{exact:true}).fill('demo-team'); await page.getByLabel('Email',{exact:true}).fill(admin.email); await page.getByLabel('Mật khẩu',{exact:true}).fill('a long admin passphrase'); await page.getByRole('button',{name:'Vào không gian làm việc'}).click(); await expect(page).toHaveURL(/workspace$/);
  return {current,users};
}
async function openMembers(page: Page) { await page.getByRole('link',{name:'Thành viên',exact:true}).click(); await expect(page.getByRole('cell').filter({hasText:'member@example.com'})).toBeVisible(); }
async function fillCreate(page: Page) {
  await page.getByRole('button',{name:'Thêm thành viên',exact:true}).click(); await page.getByLabel('Tên hiển thị',{exact:true}).fill('New Member'); await page.getByLabel('Email thành viên',{exact:true}).fill(' NEW@example.com '); await page.getByLabel('Mật khẩu ban đầu',{exact:true}).fill('a long member passphrase'); await page.getByLabel('Nhập lại mật khẩu',{exact:true}).fill('a long member passphrase');
}

test('ADMIN creates normalized member, confirms role/status changes and can cancel', async ({page})=> {
  await setup(page); await openMembers(page); await fillCreate(page); await page.getByRole('button',{name:'Tạo tài khoản',exact:true}).click(); await expect(page.getByRole('cell').filter({hasText:'new@example.com'})).toBeVisible();
  await page.getByRole('button',{name:'Đổi quyền new@example.com',exact:true}).click(); const dialog=page.getByRole('dialog'); await dialog.getByLabel('Quyền mới',{exact:true}).selectOption('OPERATOR'); await dialog.getByRole('button',{name:'Hủy thay đổi',exact:true}).click(); await expect(dialog).not.toBeVisible();
  await page.getByRole('button',{name:'Đổi quyền new@example.com',exact:true}).click(); await dialog.getByLabel('Quyền mới',{exact:true}).selectOption('OPERATOR'); await dialog.getByRole('button',{name:'Xác nhận thay đổi',exact:true}).click(); await expect(page.getByRole('row').filter({hasText:'new@example.com'})).toContainText('OPERATOR');
  await page.getByRole('button',{name:'Vô hiệu hóa new@example.com',exact:true}).click(); await dialog.getByRole('button',{name:'Xác nhận thay đổi',exact:true}).click(); await expect(page.getByRole('row').filter({hasText:'new@example.com'})).toContainText('Đã vô hiệu hóa');
  await page.getByRole('button',{name:'Kích hoạt new@example.com',exact:true}).click(); await dialog.getByRole('button',{name:'Xác nhận thay đổi',exact:true}).click(); await expect(page.getByRole('row').filter({hasText:'new@example.com'})).toContainText('Đang hoạt động');
  expect(await page.evaluate(()=>({local:localStorage.length,session:sessionStorage.length}))).toEqual({local:0,session:0});
});

for (const role of ['VIEWER','OPERATOR']) test(`${role} has no member navigation and direct route never reads users`,async ({page})=> {
  await setup(page,role); await expect(page.getByRole('link',{name:'Thành viên',exact:true})).toHaveCount(0); let requests=0;
  await page.route('**/api/auth/users**',route=>{requests++; return route.fulfill({status:403,json:{}});});
  await page.evaluate(()=>{history.pushState({},'','/members');dispatchEvent(new PopStateEvent('popstate'));}); await expect(page.getByRole('alert')).toContainText('Chỉ ADMIN'); expect(requests).toBe(0); await expect(page.getByRole('button',{name:'Thêm thành viên'})).toHaveCount(0);
});

test('last-admin 409 is explicit and self-demotion forces a new login after success', async ({page})=> {
  await setup(page); await openMembers(page); let requests=0;
  await page.route('**/api/auth/users/a1/role',route=>{requests++; return route.fulfill(requests===1?{status:409,json:{detail:'Raw server text'}}:{json:{...admin,role:'VIEWER'}});});
  await page.getByRole('button',{name:'Đổi quyền admin@example.com',exact:true}).click(); const dialog=page.getByRole('dialog'); await expect(dialog.getByText(/đăng nhập lại/)).toBeVisible(); await dialog.getByLabel('Quyền mới',{exact:true}).selectOption('VIEWER'); await dialog.getByRole('button',{name:'Xác nhận thay đổi',exact:true}).click(); await expect(dialog.getByRole('alert')).toContainText('ADMIN đang hoạt động'); await expect(page.getByText('Raw server text')).toHaveCount(0);
  await dialog.getByRole('button',{name:'Xác nhận thay đổi',exact:true}).click(); await expect(page).toHaveURL(/login\?expired=1$/);
});

test('uncertain create clears the password and waits for a manual list check; duplicate email is safe',async ({page})=> {
  await setup(page); await openMembers(page); let creates=0;
  await page.route('**/api/auth/users',route=>{creates++; return route.fulfill(creates===1?{status:500,json:{}}:{status:409,json:{detail:'SQL internal information'}});});
  await fillCreate(page); await page.getByRole('button',{name:'Tạo tài khoản',exact:true}).click(); await expect(page.getByRole('alert').filter({hasText:'Chưa xác nhận'})).toBeVisible(); await expect(page.getByLabel('Mật khẩu ban đầu',{exact:true})).toHaveValue(''); await expect(page.getByRole('button',{name:'Tạo tài khoản',exact:true})).toBeDisabled(); expect(creates).toBe(1);
  await page.getByRole('button',{name:'Kiểm tra danh sách',exact:true}).click(); await expect(page.getByRole('button',{name:'Thêm thành viên',exact:true})).toBeEnabled(); await fillCreate(page); await page.getByRole('button',{name:'Tạo tài khoản',exact:true}).click(); await expect(page.getByRole('alert').filter({hasText:'Email đã tồn tại'})).toBeVisible(); await expect(page.getByText('SQL internal information')).toHaveCount(0);
});

test('role revoked while page is open removes member data and write controls after 403',async ({page})=> {
  const {current}=await setup(page); await openMembers(page);
  await page.route('**/api/auth/users/u2/role',route=>{current.role='VIEWER'; return route.fulfill({status:403,json:{}});});
  await page.getByRole('button',{name:'Đổi quyền member@example.com',exact:true}).click(); const dialog=page.getByRole('dialog'); await dialog.getByLabel('Quyền mới',{exact:true}).selectOption('OPERATOR'); await dialog.getByRole('button',{name:'Xác nhận thay đổi',exact:true}).click(); await expect(page.getByRole('alert')).toContainText('không có quyền'); await expect(page.getByRole('cell').filter({hasText:'member@example.com'})).toHaveCount(0); await expect(page.getByRole('button',{name:'Thêm thành viên'})).toHaveCount(0); await expect(page.getByRole('link',{name:'Thành viên',exact:true})).toHaveCount(0);
});

test('list honors total pagination and Retry-After without automatic resubmission',async ({page})=> {
  await setup(page); let reads=0;
  await page.route('**/api/auth/users?*',route=>{reads++; const pageNumber=Number(new URL(route.request().url()).searchParams.get('page')); return route.fulfill(reads===3?{status:429,headers:{'Retry-After':'2'},json:{}}:{json:{items:[admin,member],total:21,page:pageNumber,size:20}});});
  await openMembers(page); await page.getByRole('button',{name:'Trang sau',exact:true}).click(); await expect(page.getByText('Trang 2',{exact:true})).toBeVisible(); await page.getByRole('button',{name:'Làm mới thành viên',exact:true}).click(); await expect(page.getByRole('alert')).toContainText('Vui lòng chờ'); await expect(page.getByRole('button',{name:'Làm mới thành viên',exact:true})).toBeDisabled(); await expect(page.getByRole('button',{name:'Làm mới thành viên',exact:true})).toBeEnabled({timeout:6000}); expect(reads).toBe(3);
});

test('mobile dialog fits, keyboard cancel works and member names render as text',async ({page})=> {
  const {users}=await setup(page); users[1].displayName='<img src=x onerror=alert(1)>'; await page.setViewportSize({width:390,height:844}); await openMembers(page); await expect(page.getByText('<img src=x onerror=alert(1)>',{exact:true})).toBeVisible(); expect(await page.locator('table img').count()).toBe(0);
  await page.getByRole('button',{name:'Đổi quyền member@example.com',exact:true}).click(); await expect(page.getByRole('dialog')).toBeVisible(); expect(await page.evaluate(()=>document.documentElement.scrollWidth <= innerWidth)).toBe(true); await page.screenshot({path:'test-results/member-mobile.png',fullPage:true}); await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).not.toBeVisible();
  await page.setViewportSize({width:1440,height:900}); await page.screenshot({path:'test-results/member-desktop.png',fullPage:true});
});
