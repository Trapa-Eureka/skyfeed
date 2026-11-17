import { expect, test } from "@playwright/test";

// Runs only when the optional CMS is up (`pnpm cms:check` sets CMS=1). The default end-to-end run skips it.
test.skip(process.env.CMS !== "1", "the optional CMS is not running");

test("R-CMS-01 with the CMS running the console shows the operator notices and the site details of the selected sensor", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByTestId("connection")).toHaveAttribute("data-state", "connected");

  const notices = page.getByTestId("notices");
  await expect(notices).toHaveAttribute("data-state", "ok");
  await expect(notices.getByTestId("notice").first()).toContainText("Demo system");
  await expect(notices.getByTestId("notice").first()).toContainText("Not for operational use");

  await page.locator('[data-testid="sensor-row"][data-sensor-id="gs-iloilo"]').getByRole("button", { name: "gs-iloilo" }).click();
  const site = page.getByTestId("sensor-site");
  await expect(site).toContainText("Iloilo ground station");
  await expect(site).toContainText("Cabatuan, Iloilo");

  // The live picture is still there: the CMS adds to the console, it does not replace anything.
  await expect.poll(() => page.locator(".ac").count()).toBeGreaterThan(5);
});
