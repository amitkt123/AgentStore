import { test, expect } from "@playwright/test";

test("buyer can browse, connect, and trigger a run against the real backend", async ({ page }) => {
  await page.goto("/login");
  await page.getByPlaceholder("Email").fill("e2e-buyer@example.com");
  await page.getByPlaceholder("Password").fill("e2e-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/catalog/);

  await page.getByText("E2E Bank Reconciliation").click();
  await expect(page.getByRole("heading", { name: "E2E Bank Reconciliation" })).toBeVisible();

  await page.getByRole("link", { name: "Connect this agent" }).click();
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: "Confirm" }).click();

  await expect(page).toHaveURL(/\/runs/);
  await page.getByRole("button", { name: /Run now/i }).click();
  await expect(page).toHaveURL(/\/runs\/[0-9a-f-]+/);
  await expect(page.getByText("RUNNING")).toBeVisible();
});
