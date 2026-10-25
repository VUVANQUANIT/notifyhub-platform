import { expect,test } from '@playwright/test';

for(const role of ['ADMIN','VIEWER'])test(`${role} invitation permissions and lifecycle`,async({page})=>{
  const invitation={id:'i1',email:'new@example.com',displayName:'New',role:'VIEWER',state:'PENDING',mailState:'PENDING',expiresAt:'2099-01-01T00:00:00Z'};
  const items:typeof invitation[]=[];let reads=0;
  await page.route('**/api/auth/login',r=>r.fulfill({json:{accessToken:'access',refreshToken:'refresh',expiresIn:300,refreshExpiresAt:'2099-01-01T00:00:00Z'}}));
  await page.route('**/api/auth/me',r=>r.fulfill({json:{id:'admin',role,enabled:true,email:'admin@example.com',displayName:'Admin',tenantId:'t1'}}));
  await page.route('**/api/auth/tenant',r=>r.fulfill({json:{id:'t1',slug:'demo-team',name:'Demo'}}));
  await page.route('**/api/auth/users/invitations**',async r=>{
    expect(r.request().headers()['authorization']).toBe('Bearer access');
    if(r.request().method()==='GET'){reads++;return r.fulfill({json:items});}
    if(r.request().method()==='POST'){expect(r.request().postDataJSON()).toEqual({email:'new@example.com',displayName:'New',role:'VIEWER'});items.push({...invitation});return r.fulfill({status:202,json:{id:'i1'}});}
    items[0].state='REVOKED';return r.fulfill({status:204});
  });
  await page.goto('/login');await page.getByLabel('Mã tổ chức',{exact:true}).fill('demo-team');await page.getByLabel('Email',{exact:true}).fill('admin@example.com');await page.getByLabel('Mật khẩu',{exact:true}).fill('my long admin password');await page.getByRole('button',{name:'Vào không gian làm việc'}).click();await expect(page).toHaveURL(/workspace$/);
  await page.evaluate(()=>{history.pushState({},'','/invitations');dispatchEvent(new PopStateEvent('popstate'));});
  if(role==='VIEWER'){await expect(page.getByRole('alert')).toContainText('không có quyền');expect(reads).toBe(0);return;}
  await page.getByLabel('Tên người được mời').fill('New');await page.getByLabel('Email nhận lời mời').fill('NEW@example.com');await page.getByRole('button',{name:'Gửi lời mời',exact:true}).click();
  await expect(page.getByRole('cell',{name:'PENDING / PENDING',exact:true})).toBeVisible();await page.getByRole('button',{name:'Thu hồi new@example.com'}).click();await expect(page.getByRole('cell',{name:'REVOKED / PENDING',exact:true})).toBeVisible();
});

test('activation removes fragment and submits credentials once without bearer or storage',async({page})=>{
  const token='a'.repeat(43); let calls=0;
  await page.route('**/api/auth/invitations/accept',route=>{
    calls++; expect(route.request().headers()['authorization']).toBeUndefined();
    expect(route.request().postDataJSON()).toEqual({token,password:'my long unique password'});
    return route.fulfill({status:204});
  });
  await page.goto('/activate#'+token); await expect(page).toHaveURL(/\/activate$/);
  await page.getByLabel('Mật khẩu mới',{exact:true}).fill('my long unique password');
  await page.getByLabel('Nhập lại mật khẩu',{exact:true}).fill('my long unique password');
  await page.getByRole('button',{name:'Kích hoạt tài khoản',exact:true}).click();
  await expect(page.getByRole('status')).toContainText('Đã xác minh email'); expect(calls).toBe(1);
  expect(await page.evaluate(()=>({local:localStorage.length,session:sessionStorage.length}))).toEqual({local:0,session:0});
});
test('expired invitation shows safe error and clears password without automatic retries',async({page})=>{
  let calls=0;
  await page.route('**/api/auth/invitations/accept',route=>{calls++;return route.fulfill({status:401,json:{detail:'raw internal error'}});});
  await page.goto('/activate#'+'b'.repeat(43));
  await page.getByLabel('Mật khẩu mới',{exact:true}).fill('my long unique password'); await page.getByLabel('Nhập lại mật khẩu',{exact:true}).fill('my long unique password');
  await page.getByRole('button',{name:'Kích hoạt tài khoản',exact:true}).click();
  await expect(page.getByRole('alert')).toContainText('hết hạn'); await expect(page.getByLabel('Mật khẩu mới',{exact:true})).toHaveValue(''); expect(calls).toBe(1);
});
test('missing activation token cannot submit',async({page})=>{
  await page.goto('/activate'); await expect(page.getByRole('alert')).toContainText('Thiếu link'); await expect(page.getByRole('button',{name:'Kích hoạt tài khoản'})).toHaveCount(0);
});
