import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  outputDir: './test-results/browser-ui',
  testIgnore: '**/*.live.spec.ts',
  fullyParallel: true,
  forbidOnly: !!process.env['CI'],
  retries: 0,
  reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:4200', trace: 'off', screenshot: 'only-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `"${process.execPath}" node_modules/@angular/cli/bin/ng.js serve --host 127.0.0.1 --port 4200 --proxy-config proxy.conf.cjs`,
    url: 'http://127.0.0.1:4200',
    reuseExistingServer: !process.env['CI'],
    timeout: 120_000,
  },
});
