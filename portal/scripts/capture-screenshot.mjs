import { chromium } from "@playwright/test";

const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
  await page.goto("http://localhost:3000");
  await page.getByRole("button", { name: /Sign in with FieldFlow/ }).click();
  await page.locator('input[name="username"]').fill("operator");
  await page.locator('input[name="password"]').fill(process.env.OPERATOR_PASSWORD ?? "operator-local");
  await page.getByRole("button", { name: "Sign In" }).click();
  await page.locator(".account").waitFor();
  await page.locator(".request-row").filter({ hasText: "Kitchen sink is leaking" }).click();
  await page.locator(".candidate").first().waitFor();
  await page.screenshot({ path: "../docs/screenshots/portal.png", fullPage: true });
} finally {
  await browser.close();
}
