import {expect,test} from '@playwright/test';
import {readFileSync} from 'node:fs';
import {createHmac,randomUUID} from 'node:crypto';

// Only the generated local k3d fixture; never use this test against a shared/production environment.
test('k3d HTTPS/CSP: invitation, MFA, audit and email campaign completion',async({page,request})=>{
  const credentials=JSON.parse(readFileSync('../.env.k3d/admin-login.json','utf8'));
  await page.addInitScript(()=>{(window as any).__cspViolations=[];document.addEventListener('securitypolicyviolation',e=>(window as any).__cspViolations.push(e.violatedDirective));});
  const response=await page.goto('/login');const policy=response?.headers()['content-security-policy']??'';
  expect(policy).toContain("require-trusted-types-for 'script'");expect(policy).toContain("script-src 'self'");
  expect(policy).not.toContain('unsafe-inline');expect(response?.headers()['cache-control']).toContain('no-store');
  await page.getByLabel('Mã tổ chức',{exact:true}).fill(credentials.tenantSlug);await page.getByLabel('Email',{exact:true}).fill(credentials.email);await page.getByLabel('Mật khẩu',{exact:true}).fill(credentials.password);
  const tokenResponse=page.waitForResponse(r=>r.url().endsWith('/api/auth/login')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'Vào không gian làm việc'}).click();const adminToken=(await(await tokenResponse).json()).accessToken;await expect(page).toHaveURL(/workspace$/);
  const headers={Authorization:'Bearer '+adminToken};const email='member-'+randomUUID()+'@example.test';
  expect((await request.post('/api/auth/users/invitations',{headers,data:{email,displayName:'Deployment Member',role:'OPERATOR'}})).status()).toBe(202);
  const mailUrl=process.env['MAILHOG_API_URL']??'http://127.0.0.1:38025';let linkToken='';
  await expect.poll(async()=>{
    const messages=await(await request.get(mailUrl+'/api/v2/search?kind=to&query='+encodeURIComponent(email))).json();
    const content=messages.items?.[0]?.Content?.Body?.replace(/=\r?\n/g,'')??'';linkToken=content.match(/\/activate#([A-Za-z0-9_-]{43})/)?.[1]??'';return linkToken.length;
  },{timeout:30000}).toBe(43);
  await page.getByRole('button',{name:'Đăng xuất',exact:true}).click();await page.goto('/activate#'+linkToken);
  const password='deployment member long passphrase';await page.getByLabel('Mật khẩu mới',{exact:true}).fill(password);await page.getByLabel('Nhập lại mật khẩu',{exact:true}).fill(password);await page.getByRole('button',{name:'Kích hoạt tài khoản',exact:true}).click();await expect(page.getByRole('status')).toContainText('Đã xác minh email');
  await page.getByRole('link',{name:'Đăng nhập',exact:true}).click();
  async function login(code=''){
    await page.getByLabel('Mã tổ chức',{exact:true}).fill(credentials.tenantSlug);await page.getByLabel('Email',{exact:true}).fill(email);await page.getByLabel('Mật khẩu',{exact:true}).fill(password);await page.getByLabel('Mã xác thực hai bước (nếu đã bật)').fill(code);await page.getByRole('button',{name:'Vào không gian làm việc'}).click();
  }
  await login();await expect(page).toHaveURL(/workspace$/);await expect(page.getByRole('link',{name:'Thành viên',exact:true})).toHaveCount(0);
  await page.getByRole('link',{name:'Bảo mật tài khoản',exact:true}).click();await page.getByLabel('Mật khẩu hiện tại').fill(password);await page.getByRole('button',{name:'Thiết lập MFA',exact:true}).click();
  const secret=(await page.locator('code.identifier').textContent())!.trim();
  const alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';let bits='';for(const c of secret)bits+=alphabet.indexOf(c).toString(2).padStart(5,'0');
  const key=Buffer.from(bits.match(/.{8}/g)!.map(b=>parseInt(b,2)));const counter=Buffer.alloc(8);counter.writeBigUInt64BE(BigInt(Math.floor(Date.now()/30000)));const digest=createHmac('sha1',key).update(counter).digest();const offset=digest[digest.length-1]&15;const code=String((digest.readUInt32BE(offset)&0x7fffffff)%1000000).padStart(6,'0');
  await page.getByLabel('Mã xác thực hoặc mã khôi phục').fill(code);await page.getByRole('button',{name:'Xác nhận bật MFA',exact:true}).click();await expect(page.locator('li code')).toHaveCount(8);const recovery=(await page.locator('li code').first().textContent())!.trim();
  await page.getByRole('button',{name:'Đăng xuất',exact:true}).click();await expect(page).toHaveURL(/login/);await login();await expect(page.getByRole('alert')).toContainText('Không thể đăng nhập');
  const memberTokens=page.waitForResponse(r=>r.url().endsWith('/api/auth/login')&&r.request().method()==='POST');await login(recovery);const memberToken=(await(await memberTokens).json()).accessToken;await expect(page).toHaveURL(/workspace$/);
  const memberHeaders={Authorization:'Bearer '+memberToken};
  const campaignResponse=await request.post('/api/campaigns',{headers:{...memberHeaders,'Idempotency-Key':randomUUID()},data:{name:'Deployment check '+randomUUID(),channel:'EMAIL',subject:'Deployment validation',body:'Hello {{name}}',scheduledAt:null}});expect(campaignResponse.status()).toBe(201);const campaign=await campaignResponse.json();
  const imported=await request.post('/api/campaigns/'+campaign.id+'/imports',{headers:memberHeaders,multipart:{file:{name:'recipients.csv',mimeType:'text/csv',buffer:Buffer.from('email,name\n'+email+',Deployment\n')}}});expect(imported.status()).toBe(201);
  expect((await request.post('/api/campaigns/'+campaign.id+'/start',{headers:{...memberHeaders,'Idempotency-Key':randomUUID()}})).status()).toBe(200);
  await expect.poll(async()=>{const result=await request.get('/api/reports/campaigns/'+campaign.id,{headers:memberHeaders});return result.ok()?(await result.json()).status:'PENDING';},{timeout:45000}).toBe('COMPLETED');
  await expect.poll(async()=>{const audit=await request.get('/api/audit',{headers});return audit.ok()?JSON.stringify(await audit.json()):'';},{timeout:30000}).toContain('MFA_ENABLED');
  expect(await page.evaluate(()=>(window as any).__cspViolations)).toEqual([]);expect(await page.evaluate(()=>localStorage.length+sessionStorage.length)).toBe(0);
});
