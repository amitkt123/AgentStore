import { expect, test } from "@playwright/test";
import { mkdirSync, writeFileSync } from "fs";
import { join } from "path";

const password = "e2e-password";

async function signIn(page: import("@playwright/test").Page, email: string) {
  await page.goto("/login");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/home$/);
}

test("buyer admin uploads a file, sees the store flow and the plug-in screen", async ({ page }, info) => {
  await signIn(page, "e2e-buyer@example.com");
  const name = `statement-${Date.now()}.csv`;
  const csv = join(info.outputDir, name);
  mkdirSync(info.outputDir, { recursive: true });
  writeFileSync(csv, "date,narration,amount\n2026-08-01,NEFT ACME,48200\n");

  await page.goto("/files");
  await page.getByLabel("Upload file").setInputFiles(csv);
  await expect(page.getByText(name)).toBeVisible();

  await page.goto("/store");
  await page.getByRole("link", { name: /E2E Bank Reconciliation/ }).click();
  await expect(page.getByRole("heading", { level: 1 })).toContainText("E2E Bank Reconciliation");
  // The seed's run already connects this buyer to the agent, so the store offers Run now; the plug-in screen is still reachable.
  await page.goto(page.url().replace("/store/", "/connect/"));
  await expect(page.getByText("Can write: Ledger, only after your approval")).toBeVisible();

  await page.getByLabel(/bank statement/i).selectOption({ label: name });
  await expect(page.getByText(/1 rows, columns match/i)).toBeVisible();
  await expect(page.getByRole("button", { name: /add to workspace/i })).toBeEnabled();
});

test("buyer admin sees a waiting run but cannot decide it", async ({ page }) => {
  await signIn(page, "e2e-buyer@example.com");
  await page.goto("/runs");
  await expect(page.getByRole("link", { name: "R-E2E1", exact: true })).toBeVisible();
  await page.getByRole("link", { name: "R-E2E1", exact: true }).click();
  await expect(page.getByText("Post 142 journal entries for August?", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
});

test("approver approves the gate from the run page and the run moves on", async ({ page }) => {
  await signIn(page, "e2e-approver@example.com");
  await page.goto("/runs/R-E2E1");
  await page.getByRole("button", { name: "Approve" }).click();
  await expect(page.getByText(/You approved this/)).toBeVisible();
  await page.goto("/runs");
  await expect(page.getByRole("link", { name: "R-E2E1", exact: true })).toBeVisible();
});
