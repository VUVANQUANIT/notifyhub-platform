import {defineConfig,devices} from '@playwright/test';
export default defineConfig({
  testDir:'./e2e',testMatch:'deployment.live.spec.ts',workers:1,retries:0,timeout:120000,
  outputDir:'./test-results/k3d',reporter:'list',
  use:{baseURL:'https://127.0.0.1:8443',ignoreHTTPSErrors:true,trace:'off',screenshot:'off',video:'off'},
  projects:[{name:'chromium',use:{...devices['Desktop Chrome']}}],
});
