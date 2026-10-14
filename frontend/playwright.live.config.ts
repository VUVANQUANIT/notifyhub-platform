import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  // Keep output cleanup away from live service logs (open file handles on Windows).
  outputDir: './test-results/browser-live',
  testMatch: '**/*.live.spec.ts',
  workers: 1,
  retries: 0,
  reporter: 'list',
  timeout: 90_000,
  // Recovery pages contain secrets; never persist traces or screenshots here.
  use: { baseURL: 'http://127.0.0.1:4201', trace: 'off', screenshot: 'off', video: 'off' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `"${process.execPath}" node_modules/@angular/cli/bin/ng.js serve --host 127.0.0.1 --port 4201 --proxy-config proxy.conf.cjs`,
    env: { NOTIFYHUB_GATEWAY_URL: process.env['NOTIFYHUB_GATEWAY_URL'] || 'http://127.0.0.1:18080' },
    url: 'http://127.0.0.1:4201',
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
