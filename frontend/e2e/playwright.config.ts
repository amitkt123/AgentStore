import { defineConfig } from "@playwright/test";

const port = Number(process.env.E2E_PORT ?? 3000);

export default defineConfig({
  testDir: ".",
  testMatch: /.*\.spec\.ts/,
  use: { baseURL: `http://localhost:${port}` },
  webServer: {
    command: `npx next dev -p ${port}`,
    cwd: "..",
    port,
    reuseExistingServer: true,
    env: { BACKEND_URL: process.env.BACKEND_URL ?? "http://localhost:8080" },
  },
});
