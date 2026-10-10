import { expect, test, type BrowserContext, type Page } from "@playwright/test";

async function signIn(page: Page, username: string, password: string) {
  await page.goto("/");
  await page.getByRole("button", { name: /Sign in with FieldFlow/ }).click();
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await page.getByRole("button", { name: "Sign In" }).click();
  await expect(page.locator(".account")).toContainText(username);
}

async function accessToken(page: Page) {
  return page.evaluate(() => {
    const key = Object.keys(sessionStorage).find((candidate) => candidate.startsWith("oidc.user:"));
    if (!key) throw new Error("OIDC session is missing");
    return JSON.parse(sessionStorage.getItem(key)!).access_token as string;
  });
}

async function rolePage(context: BrowserContext, username: string, password: string) {
  const page = await context.newPage();
  await signIn(page, username, password);
  return page;
}

test("customer request, operator assignment, technician completion, shared history", async ({ browser, request }) => {
  const customerContext = await browser.newContext();
  const operatorContext = await browser.newContext();
  const technicianContext = await browser.newContext();
  try {
    const customer = await rolePage(customerContext, "customer", process.env.CUSTOMER_PASSWORD ?? "customer-local");
    const uniqueProblem = `Test service leak ${Date.now()}: water below the sink`;
    await customer.getByPlaceholder(/Describe the issue clearly/).fill(uniqueProblem);
    await customer.getByRole("button", { name: /Create request/ }).click();
    await expect(customer.locator(".detail h3")).toHaveText(uniqueProblem);
    const customerToken = await accessToken(customer);
    const customerJobs = await request.get("http://localhost:8081/api/requests", {
      headers: { Authorization: `Bearer ${customerToken}` },
    });
    expect(customerJobs.ok()).toBeTruthy();
    const job = (await customerJobs.json()).find((item: { problem: string }) => item.problem === uniqueProblem);
    expect(job).toBeTruthy();

    const operator = await rolePage(operatorContext, "operator", process.env.OPERATOR_PASSWORD ?? "operator-local");
    await operator.getByRole("button", { name: "Refresh ↻" }).click();
    await operator.locator(".request-row").filter({ hasText: uniqueProblem }).click();
    await expect(operator.locator(".candidates .candidate")).toHaveCount(2);
    await operator.locator(".candidate").filter({ hasText: "Alex Morgan" }).getByRole("button", { name: /Assign/ }).click();
    await expect(operator.locator(".detail-top .status")).toHaveText("ASSIGNED");

    const technician = await rolePage(technicianContext, "alex", process.env.TECHNICIAN_PASSWORD ?? "technician-local");
    const token = await accessToken(technician);
    for (const status of ["ACCEPTED", "EN_ROUTE", "ON_SITE", "COMPLETED"]) {
      const changed = await request.patch(`http://localhost:8081/api/requests/${job.id}/status`, {
        headers: { Authorization: `Bearer ${token}` }, data: { status },
      });
      expect(changed.status()).toBe(200);
    }

    await customer.getByRole("button", { name: "Refresh ↻" }).click();
    await expect(customer.locator(".detail-top .status")).toHaveText("COMPLETED");
    await expect(customer.locator(".timeline .event")).toHaveCount(6);
    await operator.getByRole("button", { name: "Refresh ↻" }).click();
    await expect(operator.locator(".detail-top .status")).toHaveText("COMPLETED");
    await expect(operator.locator(".timeline .event")).toHaveCount(6);
  } finally {
    await customerContext.close();
    await operatorContext.close();
    await technicianContext.close();
  }
});
