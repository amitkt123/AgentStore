import { expect, test } from "@playwright/test";

const widths = [390, 768, 1440];
const pages = ["/home", "/files", "/runs", "/store"];

for (const w of widths) {
  test(`no horizontal page scroll at ${w}px`, async ({ page }) => {
    await page.setViewportSize({ width: w, height: 900 });
    await page.goto("/login");
    await page.getByLabel("Email").fill("e2e-buyer@example.com");
    await page.getByLabel("Password").fill("e2e-password");
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await expect(page).toHaveURL(/\/home$/);
    await page.goto("/store");
    const detail = await page.getByRole("link", { name: /E2E Bank Reconciliation/ }).first().getAttribute("href");
    for (const p of [...pages, detail!, "/runs/R-E2E1"]) {
      await page.goto(p);
      await expect(page.getByRole("navigation", { name: "Main" })).toBeVisible();
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(overflow, `${p} overflows at ${w}px`).toBeLessThanOrEqual(0);
      await page.screenshot({ path: `test-results/${w}${p.replace(/\//g, "-")}.png`, fullPage: true });
    }
  });
}
