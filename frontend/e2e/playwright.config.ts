import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: ".",
  testMatch: /.*\.spec\.ts/,
  use: { baseURL: "http://localhost:3000" },
  webServer: {
    command: "npm run dev",
    cwd: "..",
    port: 3000,
    reuseExistingServer: true,
    env: { BACKEND_URL: process.env.BACKEND_URL ?? "http://localhost:8080" },
  },
});
